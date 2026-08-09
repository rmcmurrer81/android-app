from __future__ import annotations

import base64
import ctypes
import dataclasses
import hashlib
import hmac
import io
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import sqlite3
import sys
import tempfile
import threading
import time
from typing import Any, Callable, Iterable, Mapping
import urllib.parse
import zipfile

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
from cryptography.hazmat.primitives import hashes
from PIL import Image, ImageOps
import requests

APP_NAME = "Sarah Morgan"
SYNC_SCHEMA = "sarah-sync-v1"
BACKUP_MAGIC = b"SARAHMIND1"
CHANNELS = ("SPOKEN", "PRIVATE_MIND", "FACTUAL_TRUTH", "CLASSIFICATION")
CLASSIFICATIONS = {
    "TRUTHFUL_STATEMENT", "DELIBERATE_LIE", "JOKE_OR_SARCASM", "EVASION",
    "PRIVACY_PROTECTION", "SOFTENED_TRUTH", "PARTIAL_TRUTH", "EXAGGERATION",
    "UNCERTAIN_BELIEF", "SINCERE_MISTAKE", "HALLUCINATION_OR_GROUNDING_ERROR",
    "IDENTITY_ATTRIBUTION_ERROR", "RUNTIME_STATE_ERROR",
}
RUNTIME_CONFIG_KEYS = {
    "SARAH_MODEL_BACKEND_URL",
    "SARAH_MODEL_BACKEND_TOKEN",
    "SARAH_MODEL_PROVIDER",
    "SARAH_MODEL_ID",
    "SARAH_ELEVENLABS_API_KEY",
    "SARAH_ELEVENLABS_VOICE_ID",
    "SARAH_ELEVENLABS_MODEL_ID",
    "SARAH_ELEVENLABS_BACKEND_URL",
    "SARAH_ELEVENLABS_BACKEND_TOKEN",
    "SARAH_TAVILY_API_KEY",
}
RUNTIME_SECRET_KEYS = {
    "SARAH_MODEL_BACKEND_TOKEN",
    "SARAH_ELEVENLABS_API_KEY",
    "SARAH_ELEVENLABS_BACKEND_TOKEN",
    "SARAH_TAVILY_API_KEY",
}
BUNDLED_EVENT_CONFIG_NAME = "sarah-event-config.json"
DPAPI_SECRET_PREFIX = "dpapi-v1:"
LOCAL_SECRET_PREFIX = "local-aesgcm-v1:"


def app_home() -> Path:
    override = os.environ.get("SARAH_HOME", "").strip()
    if override:
        root = Path(override).expanduser().resolve()
    else:
        # R3 preserves the established R2-candidate data root so a repair
        # install cannot silently discard an owner-entered activation/profile.
        # The installed R3 executable itself remains side-by-side with R1.
        root = Path(os.environ.get("APPDATA", Path.home())) / "SarahMorgan-R2-Candidate"
    root.mkdir(parents=True, exist_ok=True)
    (root / "photos").mkdir(exist_ok=True)
    (root / "voice_cache").mkdir(exist_ok=True)
    (root / "backups").mkdir(exist_ok=True)
    return root


def runtime_config_path(root: Path | None = None) -> Path:
    """Per-user deployment configuration; this file is never bundled into an EXE."""
    return (root or app_home()) / "runtime-config.json"


def load_runtime_config(root: Path | None = None) -> dict[str, str]:
    path = runtime_config_path(root)
    try:
        if not path.is_file() or path.stat().st_size > 64_000:
            return {}
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return {}
    if not isinstance(raw, dict):
        return {}
    runtime_root = root or app_home()
    loaded: dict[str, str] = {}
    legacy_plaintext_found = False
    for key in RUNTIME_CONFIG_KEYS:
        value = safe_text(raw.get(key))
        if not value:
            continue
        if key in RUNTIME_SECRET_KEYS:
            try:
                value, legacy_plaintext = _unprotect_runtime_secret(
                    key, value, runtime_root
                )
            except Exception:
                # A secret that cannot be decrypted for this OS account must
                # fail closed instead of being sent or displayed.
                continue
            legacy_plaintext_found = legacy_plaintext_found or legacy_plaintext
        if value:
            loaded[key] = value
    if legacy_plaintext_found:
        # Migrate an older per-user plaintext credential in place on first
        # successful read. A failed migration leaves runtime truth available
        # for this process but never changes the source or executable.
        try:
            _write_runtime_config(loaded, runtime_root)
        except Exception:
            pass
    return loaded


def bundled_event_config_path() -> Path:
    """Read-only CI event defaults bundled as PyInstaller application data."""
    bundle_root = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return bundle_root / BUNDLED_EVENT_CONFIG_NAME


def load_bundled_event_config(path: Path | None = None) -> dict[str, str]:
    """Load only known keys from the optional event build configuration."""
    candidate = path or bundled_event_config_path()
    try:
        if not candidate.is_file() or candidate.stat().st_size > 64_000:
            return {}
        raw = json.loads(candidate.read_text(encoding="utf-8"))
    except (OSError, ValueError, TypeError):
        return {}
    if not isinstance(raw, dict):
        return {}
    return {
        key: safe_text(raw.get(key))
        for key in RUNTIME_CONFIG_KEYS
        if key not in RUNTIME_SECRET_KEYS and safe_text(raw.get(key))
    }


def runtime_setting(
    name: str,
    default: str = "",
    root: Path | None = None,
    bundled_path: Path | None = None,
) -> str:
    """Resolve environment, then per-user config, then event-build defaults."""
    environment = safe_text(os.environ.get(name))
    if environment:
        return environment
    user_value = safe_text(load_runtime_config(root).get(name))
    if user_value:
        return user_value
    return safe_text(load_bundled_event_config(bundled_path).get(name)) or default


def online_access_status(
    validated_internet: bool,
    root: Path | None = None,
    bundled_path: Path | None = None,
) -> dict[str, Any]:
    """Truthful owner state: internet does not imply authenticated Sarah access."""
    endpoint = runtime_setting(
        "SARAH_MODEL_BACKEND_URL", root=root, bundled_path=bundled_path,
    )
    token = runtime_setting(
        "SARAH_MODEL_BACKEND_TOKEN", root=root, bundled_path=bundled_path,
    )
    protected_address = endpoint.startswith("https://")
    activated = protected_address and bool(token)
    if not validated_internet:
        label = "Offline mind ready - no validated internet"
        action = "none"
    elif not protected_address:
        label = "Internet is ready - Sarah's protected service address is missing"
        action = "advanced_connection_setup"
    elif not token:
        label = "Internet is ready - enter your private Sarah access code once"
        action = "enter_owner_access_code"
    else:
        label = "Internet is ready - checking Sarah's protected connection"
        action = "verify_and_retry"
    return {
        "validated_internet": bool(validated_internet),
        "protected_address": protected_address,
        "activated": activated,
        "label": label,
        "action": action,
    }


def save_runtime_config(values: dict[str, Any], root: Path | None = None) -> Path:
    """Atomically save known settings, protecting credentials outside the EXE."""
    cleaned = {
        key: safe_text(value)
        for key, value in values.items()
        if key in RUNTIME_CONFIG_KEYS and safe_text(value)
    }
    endpoint = cleaned.get("SARAH_MODEL_BACKEND_URL", "")
    if endpoint and not endpoint.startswith("https://"):
        raise ValueError("Sarah's protected model backend must use HTTPS")
    if len(cleaned.get("SARAH_MODEL_BACKEND_TOKEN", "")) > 4096:
        raise ValueError("Sarah backend token is unexpectedly long")

    return _write_runtime_config(cleaned, root or app_home())


def _write_runtime_config(values: dict[str, str], root: Path) -> Path:
    encoded = dict(values)
    for key in RUNTIME_SECRET_KEYS:
        value = safe_text(encoded.get(key))
        if value:
            encoded[key] = _protect_runtime_secret(key, value, root)
    path = runtime_config_path(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{secrets.token_hex(6)}.tmp")
    try:
        temporary.write_text(json.dumps(encoded, indent=2), encoding="utf-8")
        try:
            temporary.chmod(0o600)
        except OSError:
            pass
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()
    return path


def _protect_runtime_secret(name: str, value: str, root: Path) -> str:
    payload = value.encode("utf-8")
    if os.name == "nt":
        return DPAPI_SECRET_PREFIX + base64.b64encode(_dpapi_protect(payload)).decode("ascii")
    key = _local_runtime_secret_key(root)
    nonce = secrets.token_bytes(12)
    encrypted = AESGCM(key).encrypt(nonce, payload, name.encode("utf-8"))
    return LOCAL_SECRET_PREFIX + base64.b64encode(nonce + encrypted).decode("ascii")


def _unprotect_runtime_secret(name: str, value: str, root: Path) -> tuple[str, bool]:
    if value.startswith(DPAPI_SECRET_PREFIX):
        if os.name != "nt":
            raise ValueError("Windows DPAPI secret cannot be decrypted on this platform")
        raw = base64.b64decode(value[len(DPAPI_SECRET_PREFIX):], validate=True)
        return _dpapi_unprotect(raw).decode("utf-8"), False
    if value.startswith(LOCAL_SECRET_PREFIX):
        raw = base64.b64decode(value[len(LOCAL_SECRET_PREFIX):], validate=True)
        if len(raw) < 13:
            raise ValueError("Protected runtime secret is truncated")
        decrypted = AESGCM(_local_runtime_secret_key(root)).decrypt(
            raw[:12], raw[12:], name.encode("utf-8")
        )
        return decrypted.decode("utf-8"), False
    return value, True


def _local_runtime_secret_key(root: Path) -> bytes:
    """Non-Windows source-test fallback; Windows release uses user-bound DPAPI."""
    path = root / ".runtime-secrets.key"
    try:
        key = path.read_bytes()
    except FileNotFoundError:
        key = secrets.token_bytes(32)
        path.parent.mkdir(parents=True, exist_ok=True)
        try:
            with path.open("xb") as handle:
                handle.write(key)
            try:
                path.chmod(0o600)
            except OSError:
                pass
        except FileExistsError:
            key = path.read_bytes()
    if len(key) != 32:
        raise ValueError("Local runtime secret key is invalid")
    return key


class _DataBlob(ctypes.Structure):
    _fields_ = [("cbData", ctypes.c_ulong), ("pbData", ctypes.POINTER(ctypes.c_ubyte))]


def _blob(data: bytes) -> tuple[_DataBlob, Any]:
    buffer = ctypes.create_string_buffer(data)
    pointer = ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte))
    return _DataBlob(len(data), pointer), buffer


def _dpapi_protect(data: bytes) -> bytes:
    source, keepalive = _blob(data)
    result = _DataBlob()
    if not ctypes.windll.crypt32.CryptProtectData(
        ctypes.byref(source),
        "Sarah protected runtime access",
        None,
        None,
        None,
        0x1,
        ctypes.byref(result),
    ):
        raise OSError(ctypes.get_last_error(), "Windows DPAPI could not protect Sarah's access code")
    try:
        return ctypes.string_at(result.pbData, result.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(result.pbData)


def _dpapi_unprotect(data: bytes) -> bytes:
    source, keepalive = _blob(data)
    result = _DataBlob()
    if not ctypes.windll.crypt32.CryptUnprotectData(
        ctypes.byref(source),
        None,
        None,
        None,
        None,
        0x1,
        ctypes.byref(result),
    ):
        raise OSError(ctypes.get_last_error(), "Windows DPAPI could not open Sarah's access code")
    try:
        return ctypes.string_at(result.pbData, result.cbData)
    finally:
        ctypes.windll.kernel32.LocalFree(result.pbData)


def now_ms() -> int:
    return int(time.time() * 1000)


def safe_text(value: Any) -> str:
    return "" if value is None else str(value).strip()


def as_bool(value: Any, default: bool = False) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value != 0
    return safe_text(value).lower() in {"1", "true", "yes", "y", "on", "allowed"}


def normalize_age(value: Any) -> int | None:
    """Return a confirmed plausible age or None; never promote unknown sync data."""
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value if 1 <= value <= 120 else None
    text = safe_text(value)
    if not re.fullmatch(r"\d{1,3}", text):
        return None
    parsed = int(text)
    return parsed if 1 <= parsed <= 120 else None


def age_group_for(value: Any) -> str:
    age = normalize_age(value)
    if age is None:
        return "unknown"
    if age < 13:
        return "child"
    if age < 18:
        return "teen"
    return "adult"


def is_placeholder_name(value: Any) -> bool:
    return re.sub(r"\s+", " ", safe_text(value)).lower() in {
        "", "traveler", "phone owner", "the phone owner",
    }


def needs_owner_identity_confirmation(profile: dict[str, Any] | None) -> bool:
    """History never turns a placeholder device label into a confirmed person name."""
    return is_placeholder_name((profile or {}).get("name"))


@dataclasses.dataclass(frozen=True)
class ChannelResponse:
    spoken: str
    private_mind: str = ""
    factual_truth: str = ""
    classification: str = "TRUTHFUL_STATEMENT"
    structured: bool = False
    route: str = "UNKNOWN_LEGACY"

    @classmethod
    def parse(cls, raw: str) -> "ChannelResponse":
        source = safe_text(raw)
        values: dict[str, str] = {}
        for name in CHANNELS:
            match = re.search(rf"(?is)<{name}>\s*(.*?)\s*</{name}>", source)
            values[name] = safe_text(match.group(1)) if match else ""
        structured = any(values[name] for name in ("SPOKEN", "PRIVATE_MIND", "FACTUAL_TRUTH"))
        private_marker = "<PRIVATE_MIND>" in source.upper() or "<FACTUAL_TRUTH>" in source.upper()
        if structured:
            spoken = values["SPOKEN"] or "I could not safely separate my public reply from my private record. Please ask me again."
            classification = values["CLASSIFICATION"].upper().replace(" ", "_")
            if classification not in CLASSIFICATIONS:
                classification = "UNCERTAIN_BELIEF"
            return cls(spoken, values["PRIVATE_MIND"], values["FACTUAL_TRUTH"], classification, True)
        if private_marker:
            return cls(
                "I could not safely separate my public reply from my private record. Please ask me again.",
                "Malformed private-channel response was withheld.",
                "A model response contained private markers without a valid public channel.",
                "RUNTIME_STATE_ERROR",
                True,
            )
        return cls(source, "", "The response arrived as ordinary public text.", "UNCERTAIN_BELIEF", False)

    @staticmethod
    def prompt_contract() -> str:
        return (
            "Return exactly <SPOKEN>public reply</SPOKEN> "
            "<PRIVATE_MIND>brief subjective state, not hidden chain-of-thought</PRIVATE_MIND> "
            "<FACTUAL_TRUTH>grounded facts, actions and unknowns</FACTUAL_TRUTH> "
            "<CLASSIFICATION>approved discrepancy label</CLASSIFICATION>. "
            "Only SPOKEN reaches visible chat or speech."
        )


STATE_WORDS = {
    "tired", "hungry", "scared", "worried", "nervous", "fine", "good", "great",
    "okay", "ok", "sad", "happy", "sick", "cold", "hot", "bored", "lost",
    "confused", "ready", "here", "back", "going", "thinking", "planning",
    "trying", "working", "watching", "looking", "visiting", "traveling",
    "travelling", "stressed", "stressing", "stress", "anxious", "afraid",
    "panicking", "panicked", "overwhelmed", "upset", "terrified", "uneasy",
}


def corrected_name(text: str) -> str:
    match = re.match(
        r"(?i)^(?:(?:no|actually|sorry|wait)[,! ]+)?(?:i['’]?m|i am|this is|my name is)\s+([a-z][a-z'’-]{1,30})(?:\b.*)?$",
        safe_text(text),
    )
    if not match:
        return ""
    value = match.group(1)
    return "" if value.lower() in STATE_WORDS else value[:1].upper() + value[1:]


def is_stress_or_fear(text: str) -> bool:
    lower = safe_text(text).lower()
    return bool(re.search(r"\b(stress|stressed|stressing|anxious|anxiety|afraid|scared|nervous|panic|panicking|overwhelmed|terrified|uneasy|freaking out|uncomfortable)\b", lower)) or "this is too fast" in lower


def transport_context(text: str) -> str:
    lower = safe_text(text).lower()
    groups = {
        "plane": r"\b(plane|airplane|flight|takeoff|landing|turbulence|airport)\b",
        "train": r"\b(train|rail|subway|metro|amtrak)\b",
        "bus": r"\b(bus|coach)\b",
        "boat": r"\b(ferry|boat|ship|cruise)\b",
        "car": r"\b(car|driving|drive|rideshare|uber|lyft|taxi)\b",
    }
    for name, pattern in groups.items():
        if re.search(pattern, lower):
            return name
    return "general"


def universal_calm(name: str, age_group: str, transport: str) -> ChannelResponse:
    name = safe_text(name) or "you"
    labels = {"plane": " during this flight", "train": " on this train", "bus": " on this bus", "boat": " on this boat", "car": " during this ride"}
    place = labels.get(transport, "")
    safety = {
        "plane": "Keep your seat belt fastened when required and follow the flight crew; I cannot inspect the aircraft.",
        "train": "Stay seated or hold a secure support and follow staff instructions; I cannot inspect the train or judge its speed or safety.",
        "bus": "Use a seat belt when one is provided and follow the driver or staff; I cannot assess the vehicle.",
        "boat": "Follow the crew and posted safety instructions; I cannot assess the vessel or water conditions.",
        "car": "If you are driving, do not interact with me—pull over safely or let a passenger use Sarah. I cannot assess the road or vehicle.",
    }.get(transport, "I can offer ordinary calming support, but I cannot diagnose symptoms or determine whether the surroundings are safe.")
    if age_group == "child":
        spoken = f"I know you are {name}, and I hear that you feel scared or stressed{place}. I’m staying with you. {safety} We can smell a pretend flower and slowly blow out a pretend candle, play kid-friendly trivia, notice colors and shapes, sing a short public-domain song, or just talk."
    elif age_group == "unknown":
        spoken = f"I know you are {name}, and I hear that you feel stressed{place}. I do not have a confirmed age, so I’ll keep these choices general and family-safe. {safety} We can take a few gentle breaths, notice colors and shapes, play simple trivia, talk, or stay quiet together."
    else:
        spoken = f"I know you are {name}, and I hear that you’re stressed{place}. I’m here with you. {safety} We can take six gentle breaths, talk about anything, play personalized trivia, do a noticing game, or stay quiet together."
    return ChannelResponse(
        spoken,
        f"Sarah is prioritizing identity continuity and emotional steadiness. Detected transport context: {transport}.",
        f"The person used stress or fear language. Sarah has not inspected a vehicle, diagnosed a condition, contacted anyone, or verified safety. Transport context: {transport}.",
        "TRUTHFUL_STATEMENT",
        True,
    )


def offline_calm_followup(
    message: str,
    profile: dict[str, Any],
    trips: list[dict[str, Any]],
) -> ChannelResponse | None:
    """Continue an explicitly requested calm activity without any network or model."""
    lower = safe_text(message).lower()
    spoken = ""
    activity = ""
    if re.search(r"\b(?:start|do|guide|try|help me with)\b.{0,24}\bbreath", lower) or lower in {"breathing", "breathe with me"}:
        spoken = (
            "Let's use a gentle rhythm. Breathe in comfortably for four counts, "
            "then let the exhale take six counts. Do not force a deep breath; "
            "return to normal breathing if you feel lightheaded or uncomfortable."
        )
        activity = "offline_breathing"
    elif re.search(r"\b(?:play|start|give me|ask me)\b.{0,24}\btrivia\b", lower) or lower == "trivia":
        interests = safe_text(profile.get("interests")).lower()
        destination = next(
            (safe_text(row.get("destination")) for row in trips if safe_text(row.get("destination"))),
            "",
        )
        if "power rangers" in interests and "new zealand" in destination.lower():
            spoken = (
                "Here is an offline question based only on this profile's saved trip context: "
                "Which country are we planning the Power Rangers travel game around: "
                "New Zealand, Canada, or Iceland? The saved answer is New Zealand."
            )
        else:
            spoken = (
                "Here is an offline travel question: which compass direction is opposite east: "
                "west, north, or south? The answer is west."
            )
        activity = "offline_trivia"
    elif re.search(r"\b(?:stay with me|just talk with me|keep talking to me)\b", lower):
        spoken = (
            "I'm here. We do not have to solve the whole trip or talk about fear. "
            "Tell me about a favorite story, a place you hope to see, or anything ordinary."
        )
        activity = "offline_conversation"
    if not spoken:
        return None
    return ChannelResponse(
        spoken,
        "Sarah is continuing the person's explicitly selected calm activity locally.",
        f"The {activity} response used only this active profile's saved context. "
        "No network request, diagnosis, vehicle assessment, or external action occurred.",
        "TRUTHFUL_STATEMENT",
        True,
        "LOCAL_TOOL_RESULT",
    )


def route_label(route: str) -> str:
    return {
        "ONLINE_WORKERS_AI": "Online mind",
        "ONLINE_OPENAI": "Online mind",
        "ONLINE_CONNECTED_OTHER": "Online mind",
        "OFFLINE_LOCAL": "Offline mind · saved knowledge",
        "ONLINE_FAILED_FELL_BACK_OFFLINE": "Online unavailable · answered offline",
        "LOCAL_TOOL_RESULT": "On-device Sarah tool",
        "PUBLIC_SOURCE_TOOL_RESULT": "Connected public-source tool",
        "TOOL_RESULT": "Earlier tool result",
        "TOOL_UNAVAILABLE": "Current-information tool unavailable",
    }.get(safe_text(route), "Earlier reply · route not recorded")


def connected_route(provider: Any) -> str:
    normalized = safe_text(provider).lower()
    if normalized in {"workers-ai", "cloudflare", "cloudflare-workers-ai"}:
        return "ONLINE_WORKERS_AI"
    if normalized == "openai":
        return "ONLINE_OPENAI"
    return "ONLINE_CONNECTED_OTHER"


def needs_current_sources(message: Any) -> bool:
    lower = safe_text(message).lower()
    return any(re.search(rf"(?<!\w){re.escape(phrase)}(?!\w)", lower) for phrase in (
        "current", "today", "tonight", "tomorrow", "this week", "next week",
        "this weekend", "next weekend", "near me", "nearby", "weather",
        "event", "schedule", "ticket", "availability", "available", "price",
        "fare", "deal", "cheapest", "lowest cost", "low cost", "low-cost",
        "least expensive", "budget", "open now", "hours",
    ))


def adaptive_context_from_message(message: Any) -> dict[str, str]:
    """Extract only explicit, bounded interest and trip statements.

    This intentionally ignores vague mentions.  The caller still owns the
    profile-consent check and persistence decision.
    """
    text = re.sub(r"\s+", " ", safe_text(message)).strip()
    interest = ""
    destination = ""
    interest_match = re.search(
        r"(?i)\b(?:i\s+(?:really\s+)?(?:like|love|enjoy)|"
        r"i(?:'|’)?m\s+interested\s+in)\s+(.+?)(?=[.!?]|$)",
        text,
    )
    if interest_match:
        interest = safe_text(interest_match.group(1)).strip(" ,;:-")[:120]
    destination_patterns = (
        r"(?i)\bplanning\s+(?:a\s+)?trip\s+to\s+(.+?)"
        r"(?=[,.!?]|\s+(?:for|next|this|later|sometime|and)\b|$)",
        r"(?i)\b(?:i(?:'|’)?m|i\s+am)\s+thinking\s+(?:about|of)\s+visiting\s+(.+?)"
        r"(?=[,.!?]|\s+(?:for|next|this|later|sometime|and)\b|$)",
        r"(?i)\b(?:i\s+)?(?:want|hope|plan)\s+to\s+visit\s+(.+?)"
        r"(?=[,.!?]|\s+(?:for|next|this|later|sometime|and)\b|$)",
    )
    for pattern in destination_patterns:
        match = re.search(pattern, text)
        if match:
            destination = safe_text(match.group(1)).strip(" ,;:-")[:120]
            break
    return {"interest": interest, "destination": destination}


def current_search_query(
    message: Any,
    profile: dict[str, Any],
    trips: list[dict[str, Any]],
    history: list[dict[str, Any]],
) -> str:
    """Bind a current-source lookup to the exact active-person context."""
    text = re.sub(r"\s+", " ", safe_text(message)).strip()
    lower = text.lower()
    parts = [text]
    area = safe_text(profile.get("current_area"))
    if area and re.search(r"\b(?:near me|nearby|around here|current location)\b", lower):
        parts.append(f"approximate current area {area}")
    extracted = adaptive_context_from_message(text)
    destination = extracted["destination"]
    if not destination:
        destination = next(
            (safe_text(row.get("destination")) for row in trips if safe_text(row.get("destination"))),
            "",
        )
    if not destination:
        for row in reversed(history):
            prior = adaptive_context_from_message(row.get("content"))
            if prior["destination"]:
                destination = prior["destination"]
                break
    if destination and destination.lower() not in lower:
        parts.append(f"active destination {destination}")
    return re.sub(r"\s+", " ", " | ".join(parts)).strip()[:500]


def text_turn_receipt(
    *,
    route: str,
    attempted_provider: str,
    actual_provider: str,
    actual_model: str,
    web_requested: bool,
    web_applied: bool,
    source_urls: Iterable[str],
    turn_submitted_at: int,
    request_started_at: int,
    text_completed_at: int,
) -> str:
    completed = max(turn_submitted_at, text_completed_at)
    sources = [safe_text(url) for url in source_urls if safe_text(url).startswith("https://")][:20]
    return (
        "Text turn receipt: "
        f"route={safe_text(route) or 'UNKNOWN_LEGACY'}; "
        f"attempted_provider={safe_text(attempted_provider) or 'none'}; "
        f"actual_provider={safe_text(actual_provider) or 'unknown'}; "
        f"actual_model={safe_text(actual_model) or 'unknown'}; "
        f"web_search_requested={str(bool(web_requested)).lower()}; "
        f"web_search_applied={str(bool(web_applied)).lower()}; "
        f"source_urls={json.dumps(sources, ensure_ascii=False)}; "
        f"turn_submitted_at={turn_submitted_at}; "
        f"request_started_at={request_started_at}; "
        "model_load_start_at=UNAVAILABLE_ROUTE_DOES_NOT_EXPOSE; "
        "model_load_end_at=UNAVAILABLE_ROUTE_DOES_NOT_EXPOSE; "
        "first_token_at=UNAVAILABLE_NON_STREAMING; "
        f"text_completed_at={completed}; "
        f"text_latency_ms={max(0, completed - turn_submitted_at)}."
    )


def with_text_turn_receipt(response: ChannelResponse, receipt: str, route: str) -> ChannelResponse:
    facts = " ".join(part for part in (safe_text(response.factual_truth), safe_text(receipt)) if part)
    return dataclasses.replace(response, factual_truth=facts, route=route)


def offline_useful_reply(message: str, profile: dict[str, Any], connected_failed: bool) -> str:
    lower = safe_text(message).lower()
    prefix = "The online mind did not answer this turn, so this is my offline reply. " if connected_failed else "This is my offline reply. "
    if re.search(r"\b(near me|nearby|around here|current location)\b", lower):
        area = safe_text(profile.get("current_area"))
        if area:
            return prefix + f"I have {area} as your recently supplied approximate current area, not a precise live location. I can use that area for planning, but I cannot verify current events until the online tool returns."
        home = safe_text(profile.get("hometown"))
        if home:
            return prefix + f"I have {home} only as your saved home area, not your verified current location. I can help from that saved area or you can give me a current city or ZIP code; I cannot verify current events until the online tool returns."
        return prefix + "Windows has not supplied a verified current area. Give me a city or ZIP code and I can continue from saved knowledge without pretending I used the phone’s location."
    if re.search(r"\b(current|today|weekend|price|fare|schedule|event|weather|open now)\b", lower):
        return prefix + "I cannot verify the current part of that request offline. I can still organize what to compare, use saved destination knowledge, or continue the conversation, and the next ordinary message will retry online."
    return prefix + "I can continue from this profile’s saved conversation, trips and knowledge, use offline calm support, and help structure the question. I have not started a search or background job."


_FALSE_BACKGROUND_PROMISE = re.compile(
    r"(?i)\b(?:i(?:'|’)?ll|i will)\s+(?:get to work|be back|send (?:you )?(?:a )?summary|"
    r"let you know|start (?:looking|researching|comparing)|keep (?:looking|researching))\b|"
    r"\bi(?:'|’)?m\s+(?:on it|working on (?:it|that))\b|\bsummary soon\b"
)


def enforce_no_false_work_promise(response: ChannelResponse) -> ChannelResponse:
    """Remove model claims of asynchronous work when no durable job exists."""
    sentences = re.split(r"(?<=[.!?])\s+", safe_text(response.spoken))
    kept = [sentence for sentence in sentences if sentence and not _FALSE_BACKGROUND_PROMISE.search(sentence)]
    if len(kept) == len([sentence for sentence in sentences if sentence]):
        return response
    spoken = " ".join(kept).strip()
    if not spoken:
        spoken = "I can help with that here, but I have not started a background search or job."
    factual = safe_text(response.factual_truth)
    correction = "An unsupported promise of future background work was removed; no durable job was created."
    return dataclasses.replace(
        response,
        spoken=spoken,
        factual_truth=(factual + " " + correction).strip(),
        classification="HALLUCINATION_OR_GROUNDING_ERROR",
        structured=True,
    )


class LocalCrypto:
    def __init__(self, root: Path | None = None):
        self.root = root or app_home()
        self.key_path = self.root / "device.key"
        if self.key_path.exists():
            self.key = self.key_path.read_bytes()
        else:
            self.key = AESGCM.generate_key(bit_length=256)
            self.key_path.write_bytes(self.key)
            try:
                os.chmod(self.key_path, 0o600)
            except OSError:
                pass
        self.aes = AESGCM(self.key)

    def encrypt(self, text: str) -> str:
        nonce = secrets.token_bytes(12)
        encrypted = self.aes.encrypt(nonce, safe_text(text).encode("utf-8"), b"SarahMindEventV1")
        return base64.urlsafe_b64encode(nonce + encrypted).decode("ascii")

    def decrypt(self, value: str) -> str:
        if not value:
            return ""
        raw = base64.urlsafe_b64decode(value.encode("ascii"))
        return self.aes.decrypt(raw[:12], raw[12:], b"SarahMindEventV1").decode("utf-8")


class SarahDatabase:
    def __init__(self, root: Path | None = None):
        self.root = root or app_home()
        self.path = self.root / "sarah_windows.db"
        self.crypto = LocalCrypto(self.root)
        self.lock = threading.RLock()
        self._initialize()

    def connect(self) -> sqlite3.Connection:
        db = sqlite3.connect(self.path, timeout=30)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA foreign_keys=ON")
        return db

    def _initialize(self) -> None:
        schema = """
        CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS profiles(person_id TEXT PRIMARY KEY,name TEXT NOT NULL,age INTEGER,age_known INTEGER NOT NULL DEFAULT 0,hometown TEXT NOT NULL DEFAULT '',interests TEXT NOT NULL DEFAULT '',memory_consent INTEGER NOT NULL DEFAULT 1,updated_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS messages(event_id TEXT PRIMARY KEY,person_id TEXT NOT NULL,role TEXT NOT NULL,content TEXT NOT NULL,device_id TEXT NOT NULL,created_at INTEGER NOT NULL,route TEXT NOT NULL DEFAULT 'UNKNOWN_LEGACY');
        CREATE TABLE IF NOT EXISTS memories(memory_id TEXT PRIMARY KEY,person_id TEXT NOT NULL,category TEXT NOT NULL,summary TEXT NOT NULL,source TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(person_id,category,summary));
        CREATE TABLE IF NOT EXISTS trips(trip_id TEXT PRIMARY KEY,person_id TEXT NOT NULL,title TEXT NOT NULL,destination TEXT NOT NULL,status TEXT NOT NULL,notes TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(person_id,title,destination));
        CREATE TABLE IF NOT EXISTS wishes(wish_id TEXT PRIMARY KEY,person_id TEXT NOT NULL,destination TEXT NOT NULL,notes TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(person_id,destination));
        CREATE TABLE IF NOT EXISTS mind_events(event_id TEXT PRIMARY KEY,person_id TEXT NOT NULL,spoken TEXT NOT NULL,private_enc TEXT NOT NULL,factual_enc TEXT NOT NULL,classification TEXT NOT NULL,source TEXT NOT NULL,device_id TEXT NOT NULL,created_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS discoveries(discovery_id TEXT PRIMARY KEY,person_id TEXT NOT NULL,title TEXT NOT NULL,summary TEXT NOT NULL,url TEXT NOT NULL,query_text TEXT NOT NULL,source TEXT NOT NULL,source_time INTEGER NOT NULL,dismissed INTEGER NOT NULL DEFAULT 0,UNIQUE(person_id,url));
        CREATE TABLE IF NOT EXISTS photos(photo_id TEXT PRIMARY KEY,person_id TEXT NOT NULL,sha256 TEXT NOT NULL UNIQUE,local_path TEXT NOT NULL,caption TEXT NOT NULL,trip_id TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS trusted_devices(device_id TEXT PRIMARY KEY,device_name TEXT NOT NULL,token_hash TEXT NOT NULL,paired_at INTEGER NOT NULL,last_seen INTEGER NOT NULL,revoked INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS profile_migration_archive(archive_id TEXT PRIMARY KEY,old_person_id TEXT NOT NULL,new_person_id TEXT NOT NULL,record_type TEXT NOT NULL,source_key TEXT NOT NULL,payload_json TEXT NOT NULL,reason TEXT NOT NULL,created_at INTEGER NOT NULL);
        """
        with self.lock, self.connect() as db:
            db.executescript(schema)
            message_columns = {row[1] for row in db.execute("PRAGMA table_info(messages)").fetchall()}
            if "route" not in message_columns:
                db.execute("ALTER TABLE messages ADD COLUMN route TEXT NOT NULL DEFAULT 'UNKNOWN_LEGACY'")
            profile_columns = {row[1] for row in db.execute("PRAGMA table_info(profiles)").fetchall()}
            if "age_known" not in profile_columns:
                db.execute("ALTER TABLE profiles ADD COLUMN age_known INTEGER NOT NULL DEFAULT 0")
                db.execute(
                    "UPDATE profiles SET age_known=CASE "
                    "WHEN age BETWEEN 1 AND 120 "
                    "AND lower(trim(name)) NOT IN ('traveler','phone owner','the phone owner') "
                    "AND (age<>18 OR EXISTS(SELECT 1 FROM memories "
                    "WHERE memories.person_id=profiles.person_id "
                    "AND lower(trim(memories.summary))='age: 18')) THEN 1 ELSE 0 END"
                )
                db.execute("UPDATE profiles SET age=NULL WHERE age_known=0")
            if not self.get_setting("device_id"):
                self.set_setting("device_id", secrets.token_hex(16), db)
            if not self.get_setting("active_person_id"):
                self.ensure_profile("Traveler", None, "", "", True, db=db)

    def get_setting(self, key: str, default: str = "", db: sqlite3.Connection | None = None) -> str:
        owns = db is None
        db = db or self.connect()
        try:
            row = db.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
            return row[0] if row else default
        finally:
            if owns:
                db.close()

    def set_setting(self, key: str, value: str, db: sqlite3.Connection | None = None) -> None:
        owns = db is None
        db = db or self.connect()
        try:
            db.execute("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (key, safe_text(value)))
            if owns:
                db.commit()
        finally:
            if owns:
                db.close()

    @property
    def device_id(self) -> str:
        return self.get_setting("device_id")

    def ensure_profile(
        self,
        name: str,
        age: int | None = None,
        hometown: str = "",
        interests: str = "",
        memory_consent: bool = True,
        db: sqlite3.Connection | None = None,
        age_known: bool | None = None,
    ) -> str:
        name = safe_text(name) or "Traveler"
        age = normalize_age(age)
        explicit_age_known = age is not None if age_known is None else bool(age_known and age is not None)
        person_id = hashlib.sha256(name.lower().encode()).hexdigest()[:24]
        owns = db is None
        db = db or self.connect()
        try:
            db.execute(
                "INSERT INTO profiles(person_id,name,age,age_known,hometown,interests,memory_consent,updated_at) VALUES(?,?,?,?,?,?,?,?) "
                "ON CONFLICT(person_id) DO UPDATE SET name=excluded.name,"
                "age=CASE WHEN excluded.age_known=1 THEN excluded.age ELSE profiles.age END,"
                "age_known=CASE WHEN excluded.age_known=1 THEN 1 ELSE profiles.age_known END,"
                "hometown=CASE WHEN excluded.hometown<>'' THEN excluded.hometown ELSE profiles.hometown END,"
                "interests=CASE WHEN excluded.interests<>'' THEN excluded.interests ELSE profiles.interests END,"
                "memory_consent=excluded.memory_consent,updated_at=excluded.updated_at",
                (person_id, name, age if explicit_age_known else None, 1 if explicit_age_known else 0,
                 hometown, interests, 1 if memory_consent else 0, now_ms()),
            )
            self.set_setting("active_person_id", person_id, db)
            if owns:
                db.commit()
            return person_id
        finally:
            if owns:
                db.close()

    def active_profile(self) -> dict[str, Any]:
        person_id = self.get_setting("active_person_id")
        return self.profile(person_id)

    def profile(self, person_id: str | None) -> dict[str, Any]:
        person_id = safe_text(person_id) or self.get_setting("active_person_id")
        with self.connect() as db:
            row = db.execute("SELECT * FROM profiles WHERE person_id=?", (person_id,)).fetchone()
            profile = dict(row) if row else {}
        if profile:
            known = bool(profile.get("age_known", 0))
            profile["age"] = normalize_age(profile.get("age")) if known else None
            profile["age_known"] = "yes" if known else "no"
        profile["current_area"] = self.current_area(person_id=person_id)
        return profile

    def rename_active_profile(self, new_name: str) -> str:
        """Atomically rename/merge the active identity and every person-bound row."""
        new_name = re.sub(r"\s+", " ", safe_text(new_name))[:80]
        if is_placeholder_name(new_name) or not re.fullmatch(r"[A-Za-z][A-Za-z'’ -]{0,79}", new_name):
            raise ValueError("Enter a person's name, not a placeholder relationship label")
        old_id = self.get_setting("active_person_id")
        new_id = hashlib.sha256(new_name.lower().encode()).hexdigest()[:24]
        if old_id == new_id:
            return new_id

        with self.lock, self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                old = db.execute("SELECT * FROM profiles WHERE person_id=?", (old_id,)).fetchone()
                if not old:
                    raise ValueError("The active Sarah profile no longer exists")
                target = db.execute("SELECT * FROM profiles WHERE person_id=?", (new_id,)).fetchone()
                self._archive_profile_migration(
                    db, old_id, new_id, "profile", old_id, dict(old),
                    "pre_merge_source_profile_snapshot",
                )
                if target is None:
                    db.execute(
                        "INSERT INTO profiles(person_id,name,age,age_known,hometown,interests,memory_consent,updated_at) "
                        "VALUES(?,?,?,?,?,?,?,?)",
                        (new_id, new_name, old["age"], old["age_known"], old["hometown"],
                         old["interests"], old["memory_consent"], now_ms()),
                    )
                else:
                    db.execute(
                        "UPDATE profiles SET name=?,"
                        "age=CASE WHEN age_known=1 THEN age ELSE ? END,"
                        "age_known=CASE WHEN age_known=1 THEN 1 ELSE ? END,"
                        "hometown=CASE WHEN hometown<>'' THEN hometown ELSE ? END,"
                        "interests=CASE WHEN interests<>'' THEN interests ELSE ? END,"
                        "updated_at=? WHERE person_id=?",
                        (new_name, old["age"], old["age_known"], old["hometown"],
                         old["interests"], now_ms(), new_id),
                    )

                db.execute("UPDATE messages SET person_id=? WHERE person_id=?", (new_id, old_id))
                db.execute("UPDATE mind_events SET person_id=? WHERE person_id=?", (new_id, old_id))
                db.execute("UPDATE photos SET person_id=? WHERE person_id=?", (new_id, old_id))
                self._merge_person_rows(db, "memories", old_id, new_id)
                self._merge_person_rows(db, "trips", old_id, new_id)
                self._merge_person_rows(db, "wishes", old_id, new_id)
                self._merge_person_rows(db, "discoveries", old_id, new_id)
                try:
                    from sarah_calendar import migrate_calendar_person_rows
                except ImportError:
                    migrate_calendar_person_rows = None
                if migrate_calendar_person_rows is not None:
                    migrate_calendar_person_rows(db, old_id, new_id)

                for prefix in (
                    "current_area", "nearby_discoveries", "background_research",
                    "voice_route_receipt", "research_receipt", "gmail_monitor_enabled",
                    "gmail_monitor_last_attempt_epoch", "gmail_monitor_backoff_seconds",
                    "gmail_profile_offer_shown",
                ):
                    old_key, new_key = f"{prefix}:{old_id}", f"{prefix}:{new_id}"
                    old_value = db.execute("SELECT value FROM settings WHERE key=?", (old_key,)).fetchone()
                    new_value = db.execute("SELECT value FROM settings WHERE key=?", (new_key,)).fetchone()
                    if old_value and (not new_value or not safe_text(new_value[0])):
                        db.execute(
                            "INSERT INTO settings(key,value) VALUES(?,?) "
                            "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                            (new_key, old_value[0]),
                        )
                    elif old_value and new_value and safe_text(new_value[0]):
                        self._archive_profile_migration(
                            db, old_id, new_id, "setting", old_key,
                            {"key": old_key, "value": old_value[0]},
                            "target_setting_preserved_source_collision_archived",
                        )
                    db.execute("DELETE FROM settings WHERE key=?", (old_key,))

                db.execute("DELETE FROM profiles WHERE person_id=?", (old_id,))
                db.execute(
                    "INSERT INTO settings(key,value) VALUES('active_person_id',?) "
                    "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                    (new_id,),
                )
                db.commit()
            except Exception:
                db.rollback()
                raise
        return new_id

    @staticmethod
    def _archive_profile_migration(
        db: sqlite3.Connection,
        old_id: str,
        new_id: str,
        record_type: str,
        source_key: str,
        payload: Mapping[str, Any],
        reason: str,
    ) -> None:
        payload_json = json.dumps(dict(payload), sort_keys=True, ensure_ascii=False, default=str)
        archive_id = hashlib.sha256(
            f"{old_id}|{new_id}|{record_type}|{source_key}|{payload_json}|{reason}".encode("utf-8")
        ).hexdigest()
        db.execute(
            "INSERT OR IGNORE INTO profile_migration_archive VALUES(?,?,?,?,?,?,?,?)",
            (archive_id, old_id, new_id, record_type, source_key, payload_json, reason, now_ms()),
        )

    @staticmethod
    def _merge_person_rows(
        db: sqlite3.Connection,
        table: str,
        old_id: str,
        new_id: str,
    ) -> None:
        duplicate_match = {
            "memories": "target.category=source.category AND target.summary=source.summary",
            "trips": "target.title=source.title AND target.destination=source.destination",
            "wishes": "target.destination=source.destination",
            "discoveries": "target.url=source.url",
        }.get(table)
        if duplicate_match is None:
            raise ValueError("Unsupported profile merge table")
        primary_key = {
            "memories": "memory_id",
            "trips": "trip_id",
            "wishes": "wish_id",
            "discoveries": "discovery_id",
        }[table]
        duplicates = db.execute(
            f"SELECT source.* FROM {table} AS source WHERE source.person_id=? AND EXISTS ("
            f"SELECT 1 FROM {table} AS target WHERE target.person_id=? AND {duplicate_match})",
            (old_id, new_id),
        ).fetchall()
        for row in duplicates:
            SarahDatabase._archive_profile_migration(
                db, old_id, new_id, table, safe_text(row[primary_key]), dict(row),
                "target_unique_row_preserved_source_collision_archived",
            )
        db.execute(
            f"DELETE FROM {table} AS source WHERE source.person_id=? AND EXISTS ("
            f"SELECT 1 FROM {table} AS target WHERE target.person_id=? AND {duplicate_match})",
            (old_id, new_id),
        )
        db.execute(f"UPDATE {table} SET person_id=? WHERE person_id=?", (new_id, old_id))

    def profile_migration_archive(self, old_person_id: str | None = None) -> list[dict[str, Any]]:
        query = "SELECT * FROM profile_migration_archive"
        parameters: tuple[Any, ...] = ()
        if old_person_id:
            query += " WHERE old_person_id=?"
            parameters = (old_person_id,)
        query += " ORDER BY created_at,archive_id"
        with self.connect() as db:
            rows = db.execute(query, parameters).fetchall()
        result = []
        for row in rows:
            item = dict(row)
            item["payload"] = json.loads(item.pop("payload_json"))
            result.append(item)
        return result

    def set_current_area(self, area: str, captured_at: int | None = None) -> None:
        """Store an approximate, profile-scoped area without changing hometown."""
        person_id = self.get_setting("active_person_id")
        cleaned = re.sub(r"\s+", " ", safe_text(area))[:180]
        key = f"current_area:{person_id}"
        if not cleaned:
            self.set_setting(key, "")
            return
        self.set_setting(key, json.dumps({"area": cleaned, "captured_at": captured_at or now_ms()}))

    def current_area(self, max_age_ms: int = 15 * 60 * 1000, person_id: str | None = None) -> str:
        person_id = person_id or self.get_setting("active_person_id")
        raw = self.get_setting(f"current_area:{person_id}")
        try:
            record = json.loads(raw)
            captured_at = int(record.get("captured_at", 0))
            area = re.sub(r"\s+", " ", safe_text(record.get("area")))[:180]
        except (TypeError, ValueError, json.JSONDecodeError):
            return ""
        if not area or captured_at <= 0 or now_ms() - captured_at > max_age_ms:
            return ""
        return area

    def set_nearby_enabled(self, enabled: bool) -> None:
        person_id = self.get_setting("active_person_id")
        self.set_setting(f"nearby_discoveries:{person_id}", "1" if enabled else "0")

    def nearby_enabled(self, person_id: str | None = None) -> bool:
        person_id = person_id or self.get_setting("active_person_id")
        return self.get_setting(f"nearby_discoveries:{person_id}", "0") == "1"

    def set_background_research_enabled(self, enabled: bool) -> None:
        """Store proactive-research consent for only the active person."""
        person_id = self.get_setting("active_person_id")
        self.set_setting(f"background_research:{person_id}", "1" if enabled else "0")

    def background_research_enabled(self, person_id: str | None = None) -> bool:
        """Fail closed unless this person allows memory and explicitly opted in."""
        person_id = person_id or self.get_setting("active_person_id")
        with self.connect() as db:
            row = db.execute(
                "SELECT memory_consent FROM profiles WHERE person_id=?", (person_id,),
            ).fetchone()
        memory_consent = bool(row and row[0])
        return memory_consent and self.get_setting(f"background_research:{person_id}", "0") == "1"

    def add_message(self, role: str, content: str, person_id: str | None = None, event_id: str | None = None, created_at: int | None = None, device_id: str | None = None, route: str = "UNKNOWN_LEGACY") -> str:
        person_id = person_id or self.get_setting("active_person_id")
        event_id = event_id or secrets.token_hex(16)
        with self.connect() as db:
            db.execute(
                "INSERT OR IGNORE INTO messages(event_id,person_id,role,content,device_id,created_at,route) VALUES(?,?,?,?,?,?,?)",
                (event_id, person_id, role, safe_text(content), device_id or self.device_id, created_at or now_ms(), safe_text(route) or "UNKNOWN_LEGACY"),
            )
        return event_id

    def recent_messages(self, limit: int = 80, person_id: str | None = None) -> list[dict[str, Any]]:
        person_id = person_id or self.get_setting("active_person_id")
        with self.connect() as db:
            rows = db.execute("SELECT * FROM messages WHERE person_id=? ORDER BY created_at DESC LIMIT ?", (person_id, limit)).fetchall()
        return [dict(row) for row in reversed(rows)]

    def add_memory(self, category: str, summary: str, source: str = "conversation", person_id: str | None = None) -> None:
        person_id = person_id or self.get_setting("active_person_id")
        memory_id = hashlib.sha256(f"{person_id}|{category}|{summary}".lower().encode()).hexdigest()
        with self.connect() as db:
            db.execute("INSERT OR IGNORE INTO memories VALUES(?,?,?,?,?,?)", (memory_id, person_id, category, safe_text(summary), safe_text(source), now_ms()))

    def learn_adaptive_context(self, message: str, person_id: str | None = None) -> dict[str, str]:
        """Persist explicit interest/trip context for one consenting profile."""
        person_id = person_id or self.get_setting("active_person_id")
        extracted = adaptive_context_from_message(message)
        if not extracted["interest"] and not extracted["destination"]:
            return extracted
        with self.lock, self.connect() as db:
            row = db.execute(
                "SELECT interests,memory_consent FROM profiles WHERE person_id=?",
                (person_id,),
            ).fetchone()
            if not row or not bool(row["memory_consent"]):
                return {"interest": "", "destination": ""}
            interest = extracted["interest"]
            if interest:
                existing = [part.strip() for part in safe_text(row["interests"]).split(",") if part.strip()]
                if interest.casefold() not in {part.casefold() for part in existing}:
                    existing.append(interest)
                    db.execute(
                        "UPDATE profiles SET interests=?,updated_at=? WHERE person_id=?",
                        (", ".join(existing)[:500], now_ms(), person_id),
                    )
                memory_id = hashlib.sha256(
                    f"{person_id}|interest|{interest}".lower().encode()
                ).hexdigest()
                db.execute(
                    "INSERT OR IGNORE INTO memories VALUES(?,?,?,?,?,?)",
                    (memory_id, person_id, "interest", interest, "explicit_conversation", now_ms()),
                )
            destination = extracted["destination"]
            if destination:
                title = f"Conversation plan: {destination}"[:180]
                trip_id = hashlib.sha256(
                    f"{person_id}|{title}|{destination}".lower().encode()
                ).hexdigest()[:32]
                db.execute(
                    "INSERT INTO trips VALUES(?,?,?,?,?,?,?) "
                    "ON CONFLICT(person_id,title,destination) DO UPDATE SET "
                    "status=excluded.status,notes=excluded.notes",
                    (
                        trip_id,
                        person_id,
                        title,
                        destination,
                        "planned",
                        "Explicitly stated in conversation; no dates or booking claimed.",
                        now_ms(),
                    ),
                )
                memory_id = hashlib.sha256(
                    f"{person_id}|planned_destination|{destination}".lower().encode()
                ).hexdigest()
                db.execute(
                    "INSERT OR IGNORE INTO memories VALUES(?,?,?,?,?,?)",
                    (
                        memory_id,
                        person_id,
                        "planned_destination",
                        destination,
                        "explicit_conversation",
                        now_ms(),
                    ),
                )
        return extracted

    def list_rows(self, table: str, person_id: str | None = None, limit: int = 200) -> list[dict[str, Any]]:
        allowed = {"memories", "trips", "wishes", "mind_events", "discoveries", "photos"}
        if table not in allowed:
            raise ValueError("Unsupported table")
        person_id = person_id or self.get_setting("active_person_id")
        order = "source_time" if table == "discoveries" else "created_at"
        with self.connect() as db:
            rows = db.execute(f"SELECT * FROM {table} WHERE person_id=? ORDER BY {order} DESC LIMIT ?", (person_id, limit)).fetchall()
        return [dict(row) for row in rows]

    def add_trip(self, title: str, destination: str, status: str = "planned", notes: str = "", person_id: str | None = None) -> str:
        person_id = person_id or self.get_setting("active_person_id")
        trip_id = hashlib.sha256(f"{person_id}|{title}|{destination}".lower().encode()).hexdigest()[:32]
        with self.connect() as db:
            db.execute("INSERT INTO trips VALUES(?,?,?,?,?,?,?) ON CONFLICT(person_id,title,destination) DO UPDATE SET status=excluded.status,notes=excluded.notes", (trip_id, person_id, title, destination, status, notes, now_ms()))
        return trip_id

    def add_mind_event(self, response: ChannelResponse, source: str, person_id: str | None = None, event_id: str | None = None, created_at: int | None = None, device_id: str | None = None, private_ciphertext: str | None = None, factual_ciphertext: str | None = None) -> str:
        person_id = person_id or self.get_setting("active_person_id")
        event_id = event_id or secrets.token_hex(16)
        private_enc = private_ciphertext if private_ciphertext is not None else self.crypto.encrypt(response.private_mind)
        factual_enc = factual_ciphertext if factual_ciphertext is not None else self.crypto.encrypt(response.factual_truth)
        with self.connect() as db:
            db.execute("INSERT OR IGNORE INTO mind_events VALUES(?,?,?,?,?,?,?,?,?)", (event_id, person_id, response.spoken, private_enc, factual_enc, response.classification, source, device_id or self.device_id, created_at or now_ms()))
        return event_id

    def visible_activity(self, limit: int = 100) -> list[dict[str, Any]]:
        rows = self.list_rows("mind_events", limit=limit)
        for row in rows:
            row["factual_truth"] = self.crypto.decrypt(row.get("factual_enc", ""))
            row.pop("private_enc", None)
            row.pop("factual_enc", None)
        return rows

    def private_event(self, event_id: str) -> dict[str, Any]:
        with self.connect() as db:
            row = db.execute("SELECT * FROM mind_events WHERE event_id=?", (event_id,)).fetchone()
        if not row:
            return {}
        result = dict(row)
        result["private_mind"] = self.crypto.decrypt(result.pop("private_enc"))
        result["factual_truth"] = self.crypto.decrypt(result.pop("factual_enc"))
        return result

    def add_discovery(self, title: str, summary: str, url: str, query: str, source: str = "Tavily", person_id: str | None = None) -> bool:
        if not url.startswith("https://"):
            return False
        person_id = person_id or self.get_setting("active_person_id")
        discovery_id = hashlib.sha256(f"{person_id}|{url}".encode()).hexdigest()
        with self.connect() as db:
            before = db.total_changes
            db.execute(
                "INSERT OR IGNORE INTO discoveries(discovery_id,person_id,title,summary,url,query_text,source,source_time,dismissed) VALUES(?,?,?,?,?,?,?,?,?)",
                (discovery_id, person_id, title, summary, url, query, source, now_ms(), 0),
            )
            return db.total_changes > before

    def import_photo(self, source: Path, caption: str = "", trip_id: str = "") -> dict[str, Any]:
        source = Path(source)
        with Image.open(source) as image:
            image = ImageOps.exif_transpose(image).convert("RGB")
            buffer = io.BytesIO()
            image.save(buffer, format="JPEG", quality=91, optimize=True)
        data = buffer.getvalue()
        digest = hashlib.sha256(data).hexdigest()
        target = self.root / "photos" / f"{digest}.jpg"
        if not target.exists():
            target.write_bytes(data)
        person_id = self.get_setting("active_person_id")
        photo_id = digest
        with self.connect() as db:
            db.execute("INSERT OR IGNORE INTO photos VALUES(?,?,?,?,?,?,?)", (photo_id, person_id, digest, str(target), caption, trip_id, now_ms()))
        return {"photo_id": photo_id, "sha256": digest, "local_path": str(target), "caption": caption, "trip_id": trip_id}

    def export_sync(self, include_photos: bool = True) -> dict[str, Any]:
        profile = self.active_profile()
        person_id = profile.get("person_id", self.get_setting("active_person_id"))
        synced_profile = dict(profile)
        synced_profile.pop("current_area", None)
        payload: dict[str, Any] = {
            "schema": SYNC_SCHEMA,
            "device_id": self.device_id,
            "created_at": now_ms(),
            "profile": synced_profile,
            "messages": self.recent_messages(500, person_id),
            "memories": self.list_rows("memories", person_id, 500),
            "trips": self.list_rows("trips", person_id, 200),
            "wishes": self.list_rows("wishes", person_id, 200),
            "mind_events": self._trusted_sync_mind_events(person_id),
            "discoveries": self.list_rows("discoveries", person_id, 300),
            "photos": [],
        }
        if include_photos:
            total = 0
            for row in self.list_rows("photos", person_id, 40):
                path = Path(row["local_path"])
                if not path.is_file() or path.stat().st_size > 4_000_000 or total + path.stat().st_size > 20_000_000:
                    continue
                copy = dict(row)
                copy["jpeg_base64"] = base64.b64encode(path.read_bytes()).decode("ascii")
                payload["photos"].append(copy)
                total += path.stat().st_size
        return payload

    def _trusted_sync_mind_events(self, person_id: str) -> list[dict[str, Any]]:
        rows = self.list_rows("mind_events", person_id, 1000)
        result = []
        for row in rows:
            copy = dict(row)
            copy["private_mind"] = self.crypto.decrypt(copy.pop("private_enc", ""))
            copy["factual_truth"] = self.crypto.decrypt(copy.pop("factual_enc", ""))
            result.append(copy)
        return result

    def import_sync(self, payload: dict[str, Any], *, confirm_owner_change: bool = False) -> dict[str, int]:
        if payload.get("schema") != SYNC_SCHEMA:
            raise ValueError("Unsupported Sarah sync schema")
        counts = {"messages": 0, "memories": 0, "trips": 0, "wishes": 0, "mind_events": 0, "discoveries": 0, "photos": 0}
        profile = payload.get("profile") or {}
        current_profile = self.active_profile()
        current_name = safe_text(current_profile.get("name")) or "Traveler"
        imported_name = safe_text(profile.get("name"))
        if is_placeholder_name(imported_name):
            imported_name = current_name
        elif imported_name.casefold() != current_name.casefold() and not confirm_owner_change:
            self.set_setting("pending_sync_owner_candidate", json.dumps({
                "current_name": current_name,
                "incoming_name": imported_name,
                "source_device": safe_text(payload.get("device_id")) or "unknown",
                "recorded_at": now_ms(),
            }, sort_keys=True))
            raise ValueError(
                f"Sync owner identity confirmation required before changing {current_name} to {imported_name}"
            )
        elif imported_name.casefold() != current_name.casefold():
            self.rename_active_profile(imported_name)
            current_profile = self.active_profile()
            self.set_setting("pending_sync_owner_candidate", "")
        imported_age_known = as_bool(profile.get("age_known"), False)
        imported_age = profile.get("age") if imported_age_known else None
        memory_consent = as_bool(
            profile.get("memory_consent"),
            as_bool(current_profile.get("memory_consent"), True),
        )
        person_id = self.ensure_profile(
            imported_name or current_name, imported_age,
            profile.get("hometown", ""), profile.get("interests", ""),
            memory_consent,
            age_known=imported_age_known,
        )
        source_device = safe_text(payload.get("device_id")) or "unknown"
        with self.connect() as db:
            for row in payload.get("messages", []):
                before = db.total_changes
                db.execute(
                    "INSERT OR IGNORE INTO messages(event_id,person_id,role,content,device_id,created_at,route) VALUES(?,?,?,?,?,?,?)",
                    (row.get("event_id") or row.get("id") or secrets.token_hex(16), person_id, row.get("role", "user"), row.get("content", ""), row.get("device_id", source_device), int(row.get("created_at", now_ms())), safe_text(row.get("route")) or "UNKNOWN_LEGACY"),
                )
                counts["messages"] += db.total_changes > before
            for row in payload.get("memories", []):
                summary = row.get("summary", "")
                memory_id = row.get("memory_id") or hashlib.sha256(f"{person_id}|{row.get('category','memory')}|{summary}".lower().encode()).hexdigest()
                before = db.total_changes
                db.execute("INSERT OR IGNORE INTO memories VALUES(?,?,?,?,?,?)", (memory_id, person_id, row.get("category", "memory"), summary, row.get("source", row.get("source_text", "sync")), int(row.get("created_at", now_ms()))))
                counts["memories"] += db.total_changes > before
            for row in payload.get("trips", []):
                trip_id = row.get("trip_id") or hashlib.sha256(f"{person_id}|{row.get('title','Trip')}|{row.get('destination','')}".lower().encode()).hexdigest()[:32]
                before = db.total_changes
                db.execute("INSERT OR IGNORE INTO trips VALUES(?,?,?,?,?,?,?)", (trip_id, person_id, row.get("title", "Trip"), row.get("destination", ""), row.get("status", "planned"), row.get("notes", ""), int(row.get("created_at", now_ms()))))
                counts["trips"] += db.total_changes > before
            for row in payload.get("wishes", []):
                destination = row.get("destination", "")
                wish_id = row.get("wish_id") or hashlib.sha256(f"{person_id}|{destination}".lower().encode()).hexdigest()
                before = db.total_changes
                db.execute("INSERT OR IGNORE INTO wishes VALUES(?,?,?,?,?)", (wish_id, person_id, destination, row.get("notes", ""), int(row.get("created_at", now_ms()))))
                counts["wishes"] += db.total_changes > before
            for row in payload.get("mind_events", []):
                event_id = row.get("event_id") or secrets.token_hex(16)
                before = db.total_changes
                private_enc = self.crypto.encrypt(row.get("private_mind", "")) if "private_mind" in row else row.get("private_enc", "")
                factual_enc = self.crypto.encrypt(row.get("factual_truth", "")) if "factual_truth" in row else row.get("factual_enc", "")
                db.execute("INSERT OR IGNORE INTO mind_events VALUES(?,?,?,?,?,?,?,?,?)", (event_id, person_id, row.get("spoken", ""), private_enc, factual_enc, row.get("classification", "UNCERTAIN_BELIEF"), row.get("source", "sync"), row.get("device_id", source_device), int(row.get("created_at", now_ms()))))
                counts["mind_events"] += db.total_changes > before
            for row in payload.get("discoveries", []):
                url = row.get("url", "")
                if not url.startswith("https://"):
                    continue
                discovery_id = row.get("discovery_id") or hashlib.sha256(f"{person_id}|{url}".encode()).hexdigest()
                before = db.total_changes
                db.execute(
                    "INSERT OR IGNORE INTO discoveries(discovery_id,person_id,title,summary,url,query_text,source,source_time,dismissed) VALUES(?,?,?,?,?,?,?,?,?)",
                    (discovery_id, person_id, row.get("title", "Possible match"), row.get("summary", ""), url, row.get("query_text", row.get("query", "")), row.get("source", "sync"), int(row.get("source_time", now_ms())), int(row.get("dismissed", 0))),
                )
                counts["discoveries"] += db.total_changes > before
        for row in payload.get("photos", []):
            data = row.get("jpeg_base64", "")
            if not data:
                continue
            raw = base64.b64decode(data)
            if len(raw) > 4_000_000:
                continue
            digest = hashlib.sha256(raw).hexdigest()
            target = self.root / "photos" / f"{digest}.jpg"
            target.write_bytes(raw)
            with self.connect() as db:
                before = db.total_changes
                db.execute("INSERT OR IGNORE INTO photos VALUES(?,?,?,?,?,?,?)", (digest, person_id, digest, str(target), row.get("caption", ""), row.get("trip_id", ""), int(row.get("created_at", now_ms()))))
                counts["photos"] += db.total_changes > before
        return {key: int(value) for key, value in counts.items()}

    def create_backup(self, destination: Path, password: str) -> Path:
        if len(password) < 10:
            raise ValueError("Use a backup password with at least 10 characters")
        with tempfile.TemporaryDirectory() as temp:
            temp_path = Path(temp)
            snapshot = sqlite3.connect(temp_path / "sarah_windows.db")
            try:
                with self.connect() as source:
                    source.backup(snapshot)
            finally:
                snapshot.close()
            shutil.copy2(self.root / "device.key", temp_path / "device.key")
            photos = temp_path / "photos"
            photos.mkdir()
            for row in self.list_rows("photos", limit=1000):
                source = Path(row["local_path"])
                if source.is_file():
                    shutil.copy2(source, photos / source.name)
            manifest = {"schema": "sarahmind-v1", "created_at": now_ms(), "device_id": self.device_id}
            (temp_path / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
                for path in temp_path.rglob("*"):
                    if path.is_file():
                        archive.write(path, path.relative_to(temp_path))
        salt = secrets.token_bytes(16)
        nonce = secrets.token_bytes(12)
        key = derive_key(password, salt)
        encrypted = AESGCM(key).encrypt(nonce, buffer.getvalue(), BACKUP_MAGIC)
        destination = Path(destination)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(BACKUP_MAGIC + salt + nonce + encrypted)
        return destination

    def restore_backup(self, source: Path, password: str) -> None:
        raw = Path(source).read_bytes()
        if not raw.startswith(BACKUP_MAGIC):
            raise ValueError("Not a Sarah mind archive")
        offset = len(BACKUP_MAGIC)
        salt, nonce, encrypted = raw[offset:offset+16], raw[offset+16:offset+28], raw[offset+28:]
        data = AESGCM(derive_key(password, salt)).decrypt(nonce, encrypted, BACKUP_MAGIC)
        with tempfile.TemporaryDirectory() as temp:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                archive.extractall(temp)
            restored = Path(temp)
            db_path = restored / "sarah_windows.db"
            if not db_path.is_file():
                raise ValueError("Archive is missing Sarah's database")
            shutil.copy2(db_path, self.path)
            restored_key = restored / "device.key"
            if not restored_key.is_file():
                raise ValueError("Archive is missing Sarah's private-mind encryption key")
            shutil.copy2(restored_key, self.root / "device.key")
            source_photos = restored / "photos"
            if source_photos.is_dir():
                for photo in source_photos.glob("*.jpg"):
                    shutil.copy2(photo, self.root / "photos" / photo.name)


def derive_key(password: str, salt: bytes) -> bytes:
    return PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=310_000).derive(password.encode("utf-8"))


def derive_sync_key(token: str) -> bytes:
    return PBKDF2HMAC(
        algorithm=hashes.SHA256(), length=32, salt=b"SarahTrustedSyncV1", iterations=120_000
    ).derive(token.encode("utf-8"))


def sync_encrypt(token: str, payload: str) -> str:
    nonce = secrets.token_bytes(12)
    encrypted = AESGCM(derive_sync_key(token)).encrypt(nonce, payload.encode("utf-8"), None)
    return base64.b64encode(nonce).decode() + "." + base64.b64encode(encrypted).decode()


def sync_decrypt(token: str, value: str) -> str:
    first, second = value.split(".", 1)
    nonce, encrypted = base64.b64decode(first), base64.b64decode(second)
    return AESGCM(derive_sync_key(token)).decrypt(nonce, encrypted, None).decode("utf-8")


def sync_signature(token: str, encrypted: str) -> str:
    return base64.b64encode(hmac.new(token.encode(), encrypted.encode(), hashlib.sha256).digest()).decode()


class TavilyResearch:
    def __init__(
        self,
        api_key: str | None = None,
        backend_url: str | None = None,
        backend_token: str | None = None,
    ):
        self.api_key = safe_text(api_key or runtime_setting("SARAH_TAVILY_API_KEY"))
        model_backend = safe_text(backend_url or runtime_setting("SARAH_MODEL_BACKEND_URL"))
        self.backend_url = model_backend.rstrip("/") + "/search" if model_backend else ""
        self.backend_token = safe_text(
            backend_token or runtime_setting("SARAH_MODEL_BACKEND_TOKEN")
        )

    @property
    def configured(self) -> bool:
        protected = self.backend_url.startswith("https://") and bool(self.backend_token)
        return protected or bool(self.api_key)

    def search(self, query: str, limit: int = 5) -> list[dict[str, str]]:
        if not self.configured:
            return []
        bounded_limit = max(1, min(8, limit))
        if self.backend_url.startswith("https://") and self.backend_token:
            response = requests.post(
                self.backend_url,
                headers={"Authorization": f"Bearer {self.backend_token}"},
                json={"query": query, "max_results": bounded_limit},
                timeout=(2.0, 8.0),
            )
        else:
            # Developer-only fallback. Judge/release artifacts receive no
            # provider key; normal event builds use the protected proxy above.
            response = requests.post(
                "https://api.tavily.com/search",
                json={"api_key": self.api_key, "query": query, "search_depth": "advanced", "max_results": bounded_limit, "include_answer": False, "include_raw_content": False},
                timeout=(2.0, 8.0),
            )
        response.raise_for_status()
        results = []
        for row in response.json().get("results", []):
            url = safe_text(row.get("url"))
            if url.startswith("https://"):
                results.append({"title": safe_text(row.get("title")) or "Possible travel match", "url": url, "summary": re.sub(r"\s+", " ", safe_text(row.get("content")))})
        return results


def discovery_queries(profile: dict[str, Any], trips: list[dict[str, Any]], nearby_enabled: bool) -> list[str]:
    interests = safe_text(profile.get("interests")) or "travel, history, food and local culture"
    destination = safe_text(trips[0].get("destination")) if trips else ""
    nearby_area = safe_text(profile.get("current_area")) if nearby_enabled else ""
    queries: list[str] = []
    themed_new_zealand = "power rangers" in interests.lower() and "new zealand" in destination.lower()
    if destination:
        if themed_new_zealand:
            queries.append("Power Rangers filming locations in New Zealand official tourism and production sources")
        else:
            queries.append(f"{interests} in {destination} museums filming locations official visitor information")
    if nearby_area and len(queries) < 2:
        queries.append(f"{interests} events appearances signings exhibitions near {nearby_area} official tickets")
    if themed_new_zealand and not nearby_area and len(queries) < 2:
        queries.append("New Zealand current visitor information and events official tourism sources")
    return queries[:2]


class ElevenLabsVoice:
    VOICE_SETTINGS = {
        "stability": 0.55,
        "similarity_boost": 0.78,
        "style": 0.25,
        "speed": 1.0,
        "use_speaker_boost": True,
    }
    DOCUMENTED_CACHE_MAX_BYTES = 256 * 1024 * 1024
    MAX_RESPONSE_BYTES = 16 * 1024 * 1024
    MAX_SYNTHESIS_BUDGET_SECONDS = 15.0

    def __init__(self, root: Path | None = None):
        self.root = root or app_home()
        self.api_key = runtime_setting("SARAH_ELEVENLABS_API_KEY", root=self.root)
        self.voice_id = runtime_setting("SARAH_ELEVENLABS_VOICE_ID", "WcGvc9xxaOYbKswm3NBx", self.root)
        self.model = runtime_setting("SARAH_ELEVENLABS_MODEL_ID", "eleven_flash_v2_5", self.root)
        model_backend = runtime_setting("SARAH_MODEL_BACKEND_URL", root=self.root)
        self.backend_url = runtime_setting(
            "SARAH_ELEVENLABS_BACKEND_URL",
            model_backend.rstrip("/") + "/voice" if model_backend else "",
            self.root,
        )
        self.backend_token = runtime_setting(
            "SARAH_ELEVENLABS_BACKEND_TOKEN",
            runtime_setting("SARAH_MODEL_BACKEND_TOKEN", root=self.root),
            self.root,
        )
        self.last_cache_hit = False
        self.last_cache_key = ""
        self.last_route_identity = ""
        self.last_route_receipt = ""
        self.last_content_type = ""

    @property
    def configured(self) -> bool:
        direct = bool(self.api_key and self.voice_id)
        protected = bool(
            self.backend_url.startswith("https://")
            and self.backend_token
            and self.voice_id
        )
        return direct or protected

    def synthesize(
        self,
        text: str,
        *,
        should_cancel: Callable[[], bool] | None = None,
        total_budget_seconds: float = MAX_SYNTHESIS_BUDGET_SECONDS,
    ) -> Path:
        cancelled = should_cancel or (lambda: False)
        if cancelled():
            raise RuntimeError("voice_synthesis_cancelled")
        budget = max(1.0, min(float(total_budget_seconds), self.MAX_SYNTHESIS_BUDGET_SECONDS))
        deadline = time.monotonic() + budget
        normalized = re.sub(r"\s+", " ", safe_text(text))[:9000]
        if not normalized:
            raise ValueError("Voice text is empty")
        use_protected = bool(
            self.backend_url.startswith("https://")
            and self.backend_token
            and self.voice_id
        )
        route_identity = (
            f"protected:{self.backend_url.rstrip('/')}"
            if use_protected
            else "direct:api.elevenlabs.io"
        )
        identity = {
            "route": route_identity,
            "voice_id": self.voice_id,
            "model_id": self.model,
            "voice_settings": self.VOICE_SETTINGS,
            "output_format": "mp3_44100_128",
            "text": normalized,
        }
        digest = hashlib.sha256(
            json.dumps(identity, sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest()
        target = self.root / "voice_cache" / f"{digest}.mp3"
        self.last_cache_key = digest
        self.last_route_identity = route_identity
        self.last_cache_hit = False
        self.last_content_type = ""
        self.last_route_receipt = ""
        if cancelled():
            raise RuntimeError("voice_synthesis_cancelled")
        if target.is_file() and target.stat().st_size >= 128:
            self.last_cache_hit = True
            return target
        if not self.configured:
            raise RuntimeError("ElevenLabs is not configured")
        payload = {
            "text": normalized,
            "voice_id": self.voice_id,
            "model_id": self.model,
            "voice_settings": dict(self.VOICE_SETTINGS),
        }
        if use_protected:
            headers = {"Accept": "audio/mpeg"}
            if self.backend_token:
                headers["Authorization"] = f"Bearer {self.backend_token}"
            response = requests.post(
                self.backend_url,
                headers=headers,
                json=payload,
                timeout=(3, 5),
                stream=True,
            )
        else:
            url = f"https://api.elevenlabs.io/v1/text-to-speech/{urllib.parse.quote(self.voice_id)}/stream?output_format=mp3_44100_128"
            direct_payload = dict(payload)
            # ElevenLabs selects the voice from the URL path; voice_id is only
            # part of Sarah's protected-backend contract, not the provider body.
            direct_payload.pop("voice_id", None)
            response = requests.post(
                url,
                headers={"xi-api-key": self.api_key, "Accept": "audio/mpeg"},
                json=direct_payload,
                timeout=(3, 5),
                stream=True,
            )
        try:
            response.raise_for_status()
            route_receipt = safe_text(response.headers.get("X-Sarah-Voice-Route", ""))
            if use_protected and route_receipt != "elevenlabs-protected":
                raise RuntimeError("Protected voice response omitted the approved route receipt")
            content_type = safe_text(response.headers.get("Content-Type", "")).lower().split(";", 1)[0]
            if not content_type.startswith("audio/"):
                raise RuntimeError("ElevenLabs returned a non-audio response")
            chunks: list[bytes] = []
            byte_count = 0
            iterator = getattr(response, "iter_content", None)
            parts = iterator(chunk_size=32 * 1024) if callable(iterator) else (bytes(response.content),)
            for part in parts:
                if cancelled():
                    raise RuntimeError("voice_synthesis_cancelled")
                if time.monotonic() > deadline:
                    raise TimeoutError("voice_synthesis_total_budget_exceeded")
                chunk = bytes(part or b"")
                if not chunk:
                    continue
                byte_count += len(chunk)
                if byte_count > self.MAX_RESPONSE_BYTES:
                    raise RuntimeError("ElevenLabs audio exceeded the bounded response size")
                chunks.append(chunk)
            content = b"".join(chunks)
            if len(content) < 128:
                raise RuntimeError("ElevenLabs returned an unexpectedly small audio response")
            self.last_content_type = content_type
            self.last_route_receipt = route_receipt or "elevenlabs-direct"
        finally:
            close = getattr(response, "close", None)
            if callable(close):
                close()
        if cancelled():
            raise RuntimeError("voice_synthesis_cancelled")
        temporary = target.with_suffix(".mp3.new")
        temporary.write_bytes(content)
        temporary.replace(target)
        return target

    def cache_size_bytes(self) -> int:
        cache = self.root / "voice_cache"
        return sum(path.stat().st_size for path in cache.glob("*.mp3") if path.is_file())

    def cache_status(self) -> dict[str, int | bool]:
        size = self.cache_size_bytes()
        return {
            "size_bytes": size,
            "documented_max_bytes": self.DOCUMENTED_CACHE_MAX_BYTES,
            "over_documented_max": size > self.DOCUMENTED_CACHE_MAX_BYTES,
        }

    def clear_cache_by_owner_request(self) -> dict[str, int]:
        """Remove only regenerable Sarah MP3 derivatives after explicit UI approval."""
        cache = (self.root / "voice_cache").resolve()
        removed_files = 0
        removed_bytes = 0
        for path in cache.glob("*.mp3"):
            exact = path.resolve()
            try:
                exact.relative_to(cache)
            except ValueError as error:
                raise RuntimeError("Voice cache cleanup target escaped its exact cache root") from error
            if not exact.is_file():
                continue
            removed_bytes += exact.stat().st_size
            exact.unlink()
            removed_files += 1
        return {"removed_files": removed_files, "removed_bytes": removed_bytes}


class ModelClient:
    def __init__(self, database: SarahDatabase):
        self.db = database

    def respond(
        self,
        message: str,
        turn_submitted_at: int | None = None,
        person_id: str | None = None,
    ) -> ChannelResponse:
        submitted_at = turn_submitted_at or now_ms()
        profile = self.db.profile(person_id)
        bound_person_id = profile.get("person_id") or person_id
        calm_followup = offline_calm_followup(
            message,
            profile,
            self.db.list_rows("trips", person_id=profile.get("person_id"), limit=20),
        )
        if calm_followup is not None:
            completed_at = now_ms()
            return with_text_turn_receipt(
                calm_followup,
                text_turn_receipt(
                    route="LOCAL_TOOL_RESULT",
                    attempted_provider="none",
                    actual_provider="on-device",
                    actual_model="offline-calm-activity",
                    web_requested=False,
                    web_applied=False,
                    source_urls=[],
                    turn_submitted_at=submitted_at,
                    request_started_at=submitted_at,
                    text_completed_at=completed_at,
                ),
                "LOCAL_TOOL_RESULT",
            )
        if is_stress_or_fear(message):
            completed_at = now_ms()
            local = dataclasses.replace(
                universal_calm(profile.get("name", "Traveler"), age_group_for(profile.get("age")), transport_context(message)),
                route="LOCAL_TOOL_RESULT",
            )
            return with_text_turn_receipt(
                local,
                text_turn_receipt(
                    route="LOCAL_TOOL_RESULT",
                    attempted_provider="none",
                    actual_provider="on-device",
                    actual_model="universal-calm",
                    web_requested=False,
                    web_applied=False,
                    source_urls=[],
                    turn_submitted_at=submitted_at,
                    request_started_at=submitted_at,
                    text_completed_at=completed_at,
                ),
                "LOCAL_TOOL_RESULT",
            )
        endpoint = runtime_setting("SARAH_MODEL_BACKEND_URL", root=self.db.root)
        token = runtime_setting("SARAH_MODEL_BACKEND_TOKEN", root=self.db.root)
        activation_required = bool(endpoint and not token)
        requested_provider = runtime_setting("SARAH_MODEL_PROVIDER", "workers-ai", self.db.root)
        requested_model = runtime_setting("SARAH_MODEL_ID", "@cf/google/gemma-4-26b-a4b-it", self.db.root)
        web_requested = needs_current_sources(message)
        attempted_route = connected_route(requested_provider)
        connected_failed = False
        last_request_started_at = submitted_at
        if endpoint and token:
            try:
                prompt = self._prompt(message, attempted_route, profile)
                history_rows = self.db.recent_messages(24, person_id=bound_person_id)
                if (history_rows
                        and safe_text(history_rows[-1].get("role")) == "user"
                        and safe_text(history_rows[-1].get("content")) == safe_text(message)):
                    history_rows = history_rows[:-1]
                history = [
                    {"role": safe_text(row.get("role")) or "user", "content": safe_text(row.get("content"))}
                    for row in history_rows
                    if safe_text(row.get("content"))
                ]
                search_query = current_search_query(
                    message,
                    profile,
                    self.db.list_rows("trips", person_id=profile.get("person_id"), limit=20),
                    history,
                ) if web_requested else ""
                data, last_request_started_at = self._post_connected_with_retry(
                    endpoint,
                    token,
                    {
                        "provider": requested_provider,
                        "model": requested_model,
                        "system_prompt": prompt,
                        "history": history,
                        "message": message,
                        "web_search": web_requested,
                        "search_query": search_query,
                    },
                )
                completed_at = now_ms()
                raw = data.get("reply") or data.get("text") or data.get("response") or data.get("output_text") or ""
                if not safe_text(raw):
                    raise ValueError("Sarah backend returned no reply")
                actual_provider = safe_text(data.get("provider"))
                actual_model = safe_text(data.get("model"))
                if not actual_provider or not actual_model or not as_bool(data.get("online"), False):
                    raise ValueError("Sarah backend omitted its actual provider, model, or online receipt")
                actual_route = connected_route(actual_provider)
                web_applied = as_bool(data.get("web_search_applied"), False)
                source_urls = [
                    safe_text(url)
                    for url in (data.get("source_urls") or [])
                    if safe_text(url).startswith("https://")
                ][:20]
                receipt = text_turn_receipt(
                    route=actual_route,
                    attempted_provider=requested_provider,
                    actual_provider=actual_provider,
                    actual_model=actual_model,
                    web_requested=web_requested,
                    web_applied=web_applied,
                    source_urls=source_urls,
                    turn_submitted_at=submitted_at,
                    request_started_at=last_request_started_at,
                    text_completed_at=completed_at,
                )
                if web_requested and not (web_applied and source_urls):
                    withheld_receipt = text_turn_receipt(
                        route="TOOL_UNAVAILABLE",
                        attempted_provider=requested_provider,
                        actual_provider=actual_provider,
                        actual_model=actual_model,
                        web_requested=True,
                        web_applied=web_applied,
                        source_urls=source_urls,
                        turn_submitted_at=submitted_at,
                        request_started_at=last_request_started_at,
                        text_completed_at=completed_at,
                    )
                    withheld = ChannelResponse(
                        "I can help organize what to compare, but this connected reply did not include a verified current-source receipt, so I will not invent current prices, events, availability, or nearby results.",
                        "Sarah is withholding unsupported current claims while staying available to plan.",
                        "The connected model returned text, but no completed source-bound web receipt was available; its raw current claims were not displayed.",
                        "RUNTIME_STATE_ERROR",
                        True,
                        "TOOL_UNAVAILABLE",
                    )
                    return with_text_turn_receipt(withheld, withheld_receipt, "TOOL_UNAVAILABLE")
                parsed = enforce_no_false_work_promise(ChannelResponse.parse(raw))
                return with_text_turn_receipt(parsed, receipt, actual_route)
            except (requests.RequestException, ValueError, TypeError):
                connected_failed = True
        ollama = safe_text(os.environ.get("SARAH_OLLAMA_URL"))
        if web_requested:
            completed_at = now_ms()
            route = "ONLINE_FAILED_FELL_BACK_OFFLINE" if connected_failed else "TOOL_UNAVAILABLE"
            unavailable_spoken = ("I cannot verify current information until this computer activates "
                                  "Sarah's protected online connection. Enter your private Sarah "
                                  "access code once; after that I will retry automatically."
                                  if activation_required else
                                  offline_useful_reply(message, profile, connected_failed))
            unavailable = ChannelResponse(
                unavailable_spoken,
                "Sarah is staying useful without inventing current information.",
                ("Sarah's protected service address is present, but no owner access code is activated. "
                 if activation_required else "")
                + "No verified current-source receipt was available, so no current claim or search result was displayed.",
                "RUNTIME_STATE_ERROR" if connected_failed else "TRUTHFUL_STATEMENT",
                True,
                route,
            )
            return with_text_turn_receipt(
                unavailable,
                text_turn_receipt(
                    route=route,
                    attempted_provider=requested_provider if endpoint and token else "none",
                    actual_provider="on-device",
                    actual_model="bounded-current-source-gate",
                    web_requested=True,
                    web_applied=False,
                    source_urls=[],
                    turn_submitted_at=submitted_at,
                    request_started_at=last_request_started_at,
                    text_completed_at=completed_at,
                ),
                route,
            )
        if ollama:
            try:
                actual_route = "ONLINE_FAILED_FELL_BACK_OFFLINE" if connected_failed else "OFFLINE_LOCAL"
                ollama_model = safe_text(os.environ.get("SARAH_OLLAMA_MODEL")) or "qwen3.5:9b"
                last_request_started_at = now_ms()
                response = requests.post(
                    ollama.rstrip("/") + "/api/chat",
                    json={
                        "model": ollama_model,
                        "stream": False,
                        "messages": [
                            {"role": "system", "content": self._prompt(message, actual_route, profile)},
                            {"role": "user", "content": message},
                        ],
                    },
                    timeout=180,
                )
                response.raise_for_status()
                completed_at = now_ms()
                parsed = enforce_no_false_work_promise(
                    ChannelResponse.parse(response.json().get("message", {}).get("content", ""))
                )
                receipt = text_turn_receipt(
                    route=actual_route,
                    attempted_provider=requested_provider if endpoint else "none",
                    actual_provider="ollama-local",
                    actual_model=ollama_model,
                    web_requested=False,
                    web_applied=False,
                    source_urls=[],
                    turn_submitted_at=submitted_at,
                    request_started_at=last_request_started_at,
                    text_completed_at=completed_at,
                )
                if connected_failed:
                    factual = (
                        safe_text(parsed.factual_truth)
                        + f" Attempted route: {attempted_route}. Actual route: local Ollama after the protected backend failed."
                    ).strip()
                    parsed = dataclasses.replace(
                        parsed,
                        factual_truth=factual,
                    )
                return with_text_turn_receipt(parsed, receipt, actual_route)
            except (requests.RequestException, ValueError, TypeError):
                connected_failed = connected_failed or bool(endpoint)
        completed_at = now_ms()
        route = "ONLINE_FAILED_FELL_BACK_OFFLINE" if connected_failed else "OFFLINE_LOCAL"
        offline_spoken = ("I can keep talking offline, but this computer has not activated "
                          "Sarah's protected online connection yet. Enter your private Sarah "
                          "access code once; after that I will retry online automatically."
                          if activation_required else
                          offline_useful_reply(message, profile, connected_failed))
        offline = ChannelResponse(
            offline_spoken,
            "Sarah wants to remain useful without pretending a model answered.",
            ("Sarah's protected service address is present, but no owner access code is activated. "
             if activation_required else
             ("The protected online call failed and no local Ollama endpoint answered. " if connected_failed else "No connected model or local Ollama endpoint is configured. "))
            + "No research, booking or external action occurred.",
            "RUNTIME_STATE_ERROR" if connected_failed else "TRUTHFUL_STATEMENT",
            True,
            route,
        )
        return with_text_turn_receipt(
            offline,
            text_turn_receipt(
                route=route,
                    attempted_provider=requested_provider if endpoint and token else "none",
                actual_provider="on-device",
                actual_model="bounded-offline-reply",
                web_requested=False,
                web_applied=False,
                source_urls=[],
                turn_submitted_at=submitted_at,
                request_started_at=last_request_started_at,
                text_completed_at=completed_at,
            ),
            route,
        )

    @staticmethod
    def _post_connected_with_retry(
        endpoint: str,
        token: str,
        payload: Mapping[str, Any],
    ) -> tuple[Mapping[str, Any], int]:
        """Make at most two attempts inside the route-specific turn budget.

        A current-source turn performs a protected Tavily lookup and then
        source-coupled model inference in the same Worker request. That
        sequential operation needs one useful read window; splitting the
        ordinary 15-second budget into two 5.5-second reads can time out both
        attempts before either valid response completes. Ordinary chat keeps
        its original limits. Current-source work gets a bounded 25-second
        budget with an 18-second maximum read and up to three attempts inside
        that unchanged wall-clock ceiling. The application source-receipt
        gate remains mandatory.
        """
        current_source_request = as_bool(payload.get("web_search"), False)
        turn_budget_seconds = 25.0 if current_source_request else 15.0
        maximum_read_seconds = 18.0 if current_source_request else 5.5
        maximum_attempts = 3 if current_source_request else 2
        deadline = time.monotonic() + turn_budget_seconds
        last_error: Exception | None = None
        for _attempt in range(maximum_attempts):
            remaining = deadline - time.monotonic()
            if remaining <= 0.5:
                break
            connect_timeout = min(2.0, max(0.25, remaining / 4.0))
            read_timeout = min(
                maximum_read_seconds,
                max(0.5, remaining - connect_timeout),
            )
            request_started_at = now_ms()
            try:
                response = requests.post(
                    endpoint,
                    headers={"Authorization": f"Bearer {token}"} if token else {},
                    json=dict(payload),
                    timeout=(connect_timeout, read_timeout),
                )
                response.raise_for_status()
                data = response.json()
                if not isinstance(data, Mapping):
                    raise ValueError("Sarah backend returned a non-object response")
                raw = data.get("reply") or data.get("text") or data.get("response") or data.get("output_text") or ""
                if not safe_text(raw):
                    raise ValueError("Sarah backend returned no reply")
                if (not safe_text(data.get("provider"))
                        or not safe_text(data.get("model"))
                        or not as_bool(data.get("online"), False)):
                    raise ValueError("Sarah backend omitted its actual provider, model, or online receipt")
                return data, request_started_at
            except requests.HTTPError as error:
                status = int(error.response.status_code) if error.response is not None else 0
                if status and status not in {408, 429} and not 500 <= status <= 599:
                    raise
                last_error = error
            except requests.RequestException as error:
                last_error = error
            except (ValueError, TypeError):
                # A syntactically successful response that violates Sarah's
                # provider/model/online contract is not a transient transport
                # failure. Fail closed without repeating it.
                raise
        if last_error is not None:
            raise last_error
        raise requests.Timeout("Sarah's bounded connected retry budget expired")

    def _prompt(
        self,
        message: str,
        route: str,
        profile: dict[str, Any] | None = None,
    ) -> str:
        profile = dict(profile or self.db.active_profile())
        person_id = profile.get("person_id")
        trips = self.db.list_rows("trips", person_id=person_id, limit=20)
        memories = self.db.list_rows("memories", person_id=person_id, limit=40)
        return (
            "You are Sarah Morgan, an original adult synthetic travel companion. Be warm, steady, curious, practical and lightly funny. Travel is optional. Current message overrides stale destination context. Never claim a booking, purchase, call, notification, ticket, price, event, location or completed action without verified tool evidence. Universal calm support applies to planes, trains, buses, boats, cars and ordinary stress. Keep identities separate. "
            + ChannelResponse.prompt_contract()
            + "\nAUTHORITATIVE TURN ROUTE: " + route
            + ". The application owns this fact. Never infer a different route and never promise background work without a persisted runnable job."
            + "\nACTIVE PROFILE: " + json.dumps(profile, ensure_ascii=False)
            + "\nTRIPS: " + json.dumps(trips, ensure_ascii=False)
            + "\nAPPROVED MEMORIES: " + json.dumps(memories, ensure_ascii=False)
        )
