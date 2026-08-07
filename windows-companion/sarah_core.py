from __future__ import annotations

import base64
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
import tempfile
import threading
import time
from typing import Any, Iterable
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


def app_home() -> Path:
    override = os.environ.get("SARAH_HOME", "").strip()
    if override:
        root = Path(override).expanduser().resolve()
    else:
        root = Path(os.environ.get("APPDATA", Path.home())) / "SarahMorgan"
    root.mkdir(parents=True, exist_ok=True)
    (root / "photos").mkdir(exist_ok=True)
    (root / "voice_cache").mkdir(exist_ok=True)
    (root / "backups").mkdir(exist_ok=True)
    return root


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


@dataclasses.dataclass(frozen=True)
class ChannelResponse:
    spoken: str
    private_mind: str = ""
    factual_truth: str = ""
    classification: str = "TRUTHFUL_STATEMENT"
    structured: bool = False

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
    else:
        spoken = f"I know you are {name}, and I hear that you’re stressed{place}. I’m here with you. {safety} We can take six gentle breaths, talk about anything, play personalized trivia, do a noticing game, or stay quiet together."
    return ChannelResponse(
        spoken,
        f"Sarah is prioritizing identity continuity and emotional steadiness. Detected transport context: {transport}.",
        f"The person used stress or fear language. Sarah has not inspected a vehicle, diagnosed a condition, contacted anyone, or verified safety. Transport context: {transport}.",
        "TRUTHFUL_STATEMENT",
        True,
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
        CREATE TABLE IF NOT EXISTS profiles(person_id TEXT PRIMARY KEY,name TEXT NOT NULL,age INTEGER,hometown TEXT NOT NULL DEFAULT '',interests TEXT NOT NULL DEFAULT '',memory_consent INTEGER NOT NULL DEFAULT 1,updated_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS messages(event_id TEXT PRIMARY KEY,person_id TEXT NOT NULL,role TEXT NOT NULL,content TEXT NOT NULL,device_id TEXT NOT NULL,created_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS memories(memory_id TEXT PRIMARY KEY,person_id TEXT NOT NULL,category TEXT NOT NULL,summary TEXT NOT NULL,source TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(person_id,category,summary));
        CREATE TABLE IF NOT EXISTS trips(trip_id TEXT PRIMARY KEY,person_id TEXT NOT NULL,title TEXT NOT NULL,destination TEXT NOT NULL,status TEXT NOT NULL,notes TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(person_id,title,destination));
        CREATE TABLE IF NOT EXISTS wishes(wish_id TEXT PRIMARY KEY,person_id TEXT NOT NULL,destination TEXT NOT NULL,notes TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(person_id,destination));
        CREATE TABLE IF NOT EXISTS mind_events(event_id TEXT PRIMARY KEY,person_id TEXT NOT NULL,spoken TEXT NOT NULL,private_enc TEXT NOT NULL,factual_enc TEXT NOT NULL,classification TEXT NOT NULL,source TEXT NOT NULL,device_id TEXT NOT NULL,created_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS discoveries(discovery_id TEXT PRIMARY KEY,person_id TEXT NOT NULL,title TEXT NOT NULL,summary TEXT NOT NULL,url TEXT NOT NULL,query_text TEXT NOT NULL,source TEXT NOT NULL,source_time INTEGER NOT NULL,dismissed INTEGER NOT NULL DEFAULT 0,UNIQUE(person_id,url));
        CREATE TABLE IF NOT EXISTS photos(photo_id TEXT PRIMARY KEY,person_id TEXT NOT NULL,sha256 TEXT NOT NULL UNIQUE,local_path TEXT NOT NULL,caption TEXT NOT NULL,trip_id TEXT NOT NULL DEFAULT '',created_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS trusted_devices(device_id TEXT PRIMARY KEY,device_name TEXT NOT NULL,token_hash TEXT NOT NULL,paired_at INTEGER NOT NULL,last_seen INTEGER NOT NULL,revoked INTEGER NOT NULL DEFAULT 0);
        """
        with self.lock, self.connect() as db:
            db.executescript(schema)
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

    def ensure_profile(self, name: str, age: int | None = None, hometown: str = "", interests: str = "", memory_consent: bool = True, db: sqlite3.Connection | None = None) -> str:
        name = safe_text(name) or "Traveler"
        person_id = hashlib.sha256(name.lower().encode()).hexdigest()[:24]
        owns = db is None
        db = db or self.connect()
        try:
            db.execute(
                "INSERT INTO profiles(person_id,name,age,hometown,interests,memory_consent,updated_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(person_id) DO UPDATE SET name=excluded.name,age=COALESCE(excluded.age,profiles.age),hometown=CASE WHEN excluded.hometown<>'' THEN excluded.hometown ELSE profiles.hometown END,interests=CASE WHEN excluded.interests<>'' THEN excluded.interests ELSE profiles.interests END,memory_consent=excluded.memory_consent,updated_at=excluded.updated_at",
                (person_id, name, age, hometown, interests, 1 if memory_consent else 0, now_ms()),
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
        with self.connect() as db:
            row = db.execute("SELECT * FROM profiles WHERE person_id=?", (person_id,)).fetchone()
            return dict(row) if row else {}

    def add_message(self, role: str, content: str, person_id: str | None = None, event_id: str | None = None, created_at: int | None = None, device_id: str | None = None) -> str:
        person_id = person_id or self.get_setting("active_person_id")
        event_id = event_id or secrets.token_hex(16)
        with self.connect() as db:
            db.execute("INSERT OR IGNORE INTO messages VALUES(?,?,?,?,?,?)", (event_id, person_id, role, safe_text(content), device_id or self.device_id, created_at or now_ms()))
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

    def add_discovery(self, title: str, summary: str, url: str, query: str, source: str = "Tavily") -> bool:
        if not url.startswith("https://"):
            return False
        person_id = self.get_setting("active_person_id")
        discovery_id = hashlib.sha256(f"{person_id}|{url}".encode()).hexdigest()
        with self.connect() as db:
            before = db.total_changes
            db.execute("INSERT OR IGNORE INTO discoveries VALUES(?,?,?,?,?,?,?,?,?,?)", (discovery_id, person_id, title, summary, url, query, source, now_ms(), 0, now_ms()))
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
        payload: dict[str, Any] = {
            "schema": SYNC_SCHEMA,
            "device_id": self.device_id,
            "created_at": now_ms(),
            "profile": profile,
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

    def import_sync(self, payload: dict[str, Any]) -> dict[str, int]:
        if payload.get("schema") != SYNC_SCHEMA:
            raise ValueError("Unsupported Sarah sync schema")
        counts = {"messages": 0, "memories": 0, "trips": 0, "wishes": 0, "mind_events": 0, "discoveries": 0, "photos": 0}
        profile = payload.get("profile") or {}
        person_id = self.ensure_profile(
            profile.get("name", "Traveler"), profile.get("age"),
            profile.get("hometown", ""), profile.get("interests", ""),
            as_bool(profile.get("memory_consent", 1), True),
        )
        source_device = safe_text(payload.get("device_id")) or "unknown"
        with self.connect() as db:
            for row in payload.get("messages", []):
                before = db.total_changes
                db.execute("INSERT OR IGNORE INTO messages VALUES(?,?,?,?,?,?)", (row.get("event_id") or row.get("id") or secrets.token_hex(16), person_id, row.get("role", "user"), row.get("content", ""), row.get("device_id", source_device), int(row.get("created_at", now_ms()))))
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
                db.execute("INSERT OR IGNORE INTO discoveries VALUES(?,?,?,?,?,?,?,?,?,?)", (discovery_id, person_id, row.get("title", "Possible match"), row.get("summary", ""), url, row.get("query_text", row.get("query", "")), row.get("source", "sync"), int(row.get("source_time", now_ms())), int(row.get("dismissed", 0)), int(row.get("created_at", now_ms()))))
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
    def __init__(self, api_key: str | None = None):
        self.api_key = safe_text(api_key or os.environ.get("SARAH_TAVILY_API_KEY"))

    @property
    def configured(self) -> bool:
        return bool(self.api_key)

    def search(self, query: str, limit: int = 5) -> list[dict[str, str]]:
        if not self.configured:
            return []
        response = requests.post(
            "https://api.tavily.com/search",
            json={"api_key": self.api_key, "query": query, "search_depth": "advanced", "max_results": max(1, min(8, limit)), "include_answer": False, "include_raw_content": False},
            timeout=60,
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
    hometown = safe_text(profile.get("hometown")) if nearby_enabled else ""
    queries: list[str] = []
    if destination:
        if "power rangers" in interests.lower() and "new zealand" in destination.lower():
            queries.append("Power Rangers filming locations New Zealand Auckland current official visitor information")
        queries.append(f"{interests} in {destination} filming locations museums current events official tickets visitor information")
    if hometown:
        queries.append(f"{interests} events appearances signings exhibitions near {hometown} official tickets")
    return queries


class ElevenLabsVoice:
    def __init__(self, root: Path | None = None):
        self.root = root or app_home()
        self.api_key = safe_text(os.environ.get("SARAH_ELEVENLABS_API_KEY"))
        self.voice_id = safe_text(os.environ.get("SARAH_ELEVENLABS_VOICE_ID"))
        self.model = safe_text(os.environ.get("SARAH_ELEVENLABS_MODEL_ID")) or "eleven_multilingual_v2"

    @property
    def configured(self) -> bool:
        return bool(self.api_key and self.voice_id)

    def synthesize(self, text: str) -> Path:
        normalized = re.sub(r"\s+", " ", safe_text(text))[:9000]
        digest = hashlib.sha256(f"{self.voice_id}|{normalized}".encode()).hexdigest()
        target = self.root / "voice_cache" / f"{digest}.mp3"
        if target.is_file():
            return target
        if not self.configured:
            raise RuntimeError("ElevenLabs is not configured")
        url = f"https://api.elevenlabs.io/v1/text-to-speech/{urllib.parse.quote(self.voice_id)}/stream?output_format=mp3_44100_128"
        response = requests.post(
            url,
            headers={"xi-api-key": self.api_key, "Accept": "audio/mpeg"},
            json={"text": normalized, "model_id": self.model, "voice_settings": {"stability": 0.55, "similarity_boost": 0.78, "style": 0.25, "use_speaker_boost": True}},
            timeout=120,
        )
        response.raise_for_status()
        target.write_bytes(response.content)
        return target


class ModelClient:
    def __init__(self, database: SarahDatabase):
        self.db = database

    def respond(self, message: str) -> ChannelResponse:
        profile = self.db.active_profile()
        if is_stress_or_fear(message):
            age = profile.get("age")
            age_group = "child" if isinstance(age, int) and age < 13 else "teen" if isinstance(age, int) and age < 18 else "adult"
            return universal_calm(profile.get("name", "Traveler"), age_group, transport_context(message))
        endpoint = safe_text(os.environ.get("SARAH_MODEL_BACKEND_URL"))
        token = safe_text(os.environ.get("SARAH_MODEL_BACKEND_TOKEN"))
        if endpoint:
            prompt = self._prompt(message)
            response = requests.post(endpoint, headers={"Authorization": f"Bearer {token}"} if token else {}, json={"message": message, "system": prompt, "store": False}, timeout=120)
            response.raise_for_status()
            data = response.json()
            raw = data.get("text") or data.get("response") or data.get("output_text") or ""
            return ChannelResponse.parse(raw)
        ollama = safe_text(os.environ.get("SARAH_OLLAMA_URL"))
        if ollama:
            response = requests.post(ollama.rstrip("/") + "/api/chat", json={"model": safe_text(os.environ.get("SARAH_OLLAMA_MODEL")) or "qwen3.5:9b", "stream": False, "messages": [{"role": "system", "content": self._prompt(message)}, {"role": "user", "content": message}]}, timeout=180)
            response.raise_for_status()
            return ChannelResponse.parse(response.json().get("message", {}).get("content", ""))
        return ChannelResponse(
            "I’m with you. My stronger connected or local model is not configured on this computer yet, but I can still save trips, organize photos, use offline calm support, pair with your phone, and work from saved information.",
            "Sarah wants to remain useful without pretending a model answered.",
            "No connected model or local Ollama endpoint is configured. No research, booking or external action occurred.",
            "TRUTHFUL_STATEMENT",
            True,
        )

    def _prompt(self, message: str) -> str:
        profile = self.db.active_profile()
        trips = self.db.list_rows("trips", limit=20)
        memories = self.db.list_rows("memories", limit=40)
        return (
            "You are Sarah Morgan, an original adult synthetic travel companion. Be warm, steady, curious, practical and lightly funny. Travel is optional. Current message overrides stale destination context. Never claim a booking, purchase, call, notification, ticket, price, event, location or completed action without verified tool evidence. Universal calm support applies to planes, trains, buses, boats, cars and ordinary stress. Keep identities separate. "
            + ChannelResponse.prompt_contract()
            + "\nACTIVE PROFILE: " + json.dumps(profile, ensure_ascii=False)
            + "\nTRIPS: " + json.dumps(trips, ensure_ascii=False)
            + "\nAPPROVED MEMORIES: " + json.dumps(memories, ensure_ascii=False)
        )
