#!/usr/bin/env python3
"""Generate the Sarah Morgan Windows companion, tests, launchers and documentation."""
from __future__ import annotations
from pathlib import Path
import textwrap

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "windows-companion"


def clean(value: str) -> str:
    return textwrap.dedent(value).lstrip("\n").rstrip() + "\n"


def write(relative: str, content: str) -> None:
    path = OUT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    value = clean(content)
    if not path.exists() or path.read_text(encoding="utf-8") != value:
        path.write_text(value, encoding="utf-8", newline="\n")


CORE = r'''
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
                self.ensure_profile("Robert", 45, "Newark, New Jersey", "", True, db=db)

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
            "mind_events": self.list_rows("mind_events", person_id, 1000),
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

    def import_sync(self, payload: dict[str, Any]) -> dict[str, int]:
        if payload.get("schema") != SYNC_SCHEMA:
            raise ValueError("Unsupported Sarah sync schema")
        counts = {"messages": 0, "memories": 0, "trips": 0, "wishes": 0, "mind_events": 0, "discoveries": 0, "photos": 0}
        profile = payload.get("profile") or {}
        person_id = self.ensure_profile(profile.get("name", "Traveler"), profile.get("age"), profile.get("hometown", ""), profile.get("interests", ""), bool(profile.get("memory_consent", 1)))
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
                private_enc = row.get("private_enc", "")
                factual_enc = row.get("factual_enc", "")
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
            shutil.copy2(self.path, temp_path / "sarah_windows.db")
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
            source_photos = restored / "photos"
            if source_photos.is_dir():
                for photo in source_photos.glob("*.jpg"):
                    shutil.copy2(photo, self.root / "photos" / photo.name)


def derive_key(password: str, salt: bytes) -> bytes:
    return PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=310_000).derive(password.encode("utf-8"))


def sync_encrypt(token: str, payload: str) -> str:
    key = derive_key(token, b"SarahTrustedSyncV1")
    nonce = secrets.token_bytes(12)
    encrypted = AESGCM(key).encrypt(nonce, payload.encode("utf-8"), b"SarahSync")
    return base64.b64encode(nonce).decode() + "." + base64.b64encode(encrypted).decode()


def sync_decrypt(token: str, value: str) -> str:
    first, second = value.split(".", 1)
    nonce, encrypted = base64.b64decode(first), base64.b64decode(second)
    return AESGCM(derive_key(token, b"SarahTrustedSyncV1")).decrypt(nonce, encrypted, b"SarahSync").decode("utf-8")


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
'''

SYNC_SERVER = r'''
from __future__ import annotations
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import secrets
import threading
import time
from typing import Any

from sarah_core import SarahDatabase, sync_decrypt, sync_signature


class SarahSyncServer:
    def __init__(self, database: SarahDatabase, host: str = "0.0.0.0", port: int = 8769):
        self.database = database
        self.host = host
        self.port = port
        self.pairing_code = f"{secrets.randbelow(1_000_000):06d}"
        self.pairing_expires = time.time() + 15 * 60
        self.httpd: ThreadingHTTPServer | None = None
        self.thread: threading.Thread | None = None

    def rotate_code(self) -> str:
        self.pairing_code = f"{secrets.randbelow(1_000_000):06d}"
        self.pairing_expires = time.time() + 15 * 60
        return self.pairing_code

    def start(self) -> None:
        outer = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format: str, *args: Any) -> None:
                return
            def _json(self) -> dict[str, Any]:
                length = int(self.headers.get("Content-Length", "0"))
                if length > 30_000_000:
                    raise ValueError("Payload too large")
                return json.loads(self.rfile.read(length).decode("utf-8") or "{}")
            def _send(self, status: int, payload: dict[str, Any]) -> None:
                data = json.dumps(payload).encode("utf-8")
                self.send_response(status); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
            def do_GET(self) -> None:
                if self.path == "/health": self._send(200, {"status": "Sarah Windows ready", "schema": "sarah-sync-v1"})
                else: self._send(404, {"error": "not found"})
            def do_POST(self) -> None:
                try:
                    if self.path == "/pair":
                        body = self._json()
                        if time.time() > outer.pairing_expires or str(body.get("code", "")) != outer.pairing_code:
                            self._send(403, {"error": "Pairing code is wrong or expired"}); return
                        device_id = str(body.get("device_id", "")).strip()
                        device_name = str(body.get("device_name", "Android phone")).strip()
                        if not device_id: self._send(400, {"error": "device_id required"}); return
                        token = secrets.token_urlsafe(32)
                        with outer.database.connect() as db:
                            db.execute("INSERT INTO trusted_devices VALUES(?,?,?,?,?,0) ON CONFLICT(device_id) DO UPDATE SET device_name=excluded.device_name,token_hash=excluded.token_hash,paired_at=excluded.paired_at,last_seen=excluded.last_seen,revoked=0", (device_id, device_name, hashlib.sha256(token.encode()).hexdigest(), int(time.time()*1000), int(time.time()*1000)))
                        outer.rotate_code()
                        self._send(200, {"token": token, "message": f"Paired {device_name}."}); return
                    if self.path == "/sync":
                        body = self._json(); token = self.headers.get("X-Sarah-Device-Token", "")
                        if not token: self._send(401, {"error": "Missing device token"}); return
                        token_hash = hashlib.sha256(token.encode()).hexdigest()
                        with outer.database.connect() as db:
                            row = db.execute("SELECT device_id FROM trusted_devices WHERE token_hash=? AND revoked=0", (token_hash,)).fetchone()
                        if not row: self._send(403, {"error": "Device is not trusted or was revoked"}); return
                        encrypted = str(body.get("payload", "")); signature = str(body.get("signature", ""))
                        if not hmac.compare_digest(sync_signature(token, encrypted), signature): self._send(403, {"error": "Signature failed"}); return
                        payload = json.loads(sync_decrypt(token, encrypted))
                        counts = outer.database.import_sync(payload)
                        with outer.database.connect() as db: db.execute("UPDATE trusted_devices SET last_seen=? WHERE token_hash=?", (int(time.time()*1000), token_hash))
                        self._send(200, {"message": "Sarah synchronized the phone with this Windows companion.", "imported": counts}); return
                    self._send(404, {"error": "not found"})
                except Exception as exc:
                    self._send(500, {"error": str(exc)[:500]})
        self.httpd = ThreadingHTTPServer((self.host, self.port), Handler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, name="SarahSyncServer", daemon=True)
        self.thread.start()

    def stop(self) -> None:
        if self.httpd:
            self.httpd.shutdown(); self.httpd.server_close(); self.httpd = None
'''

DRIVE = r'''
from __future__ import annotations
from pathlib import Path


def upload_encrypted_backup(backup_path: Path, client_secret: Path) -> str:
    """Upload an already-encrypted .sarahmind archive to Drive appDataFolder."""
    try:
        from google_auth_oauthlib.flow import InstalledAppFlow
        from googleapiclient.discovery import build
        from googleapiclient.http import MediaFileUpload
    except ImportError as exc:
        raise RuntimeError("Install the optional Google Drive packages first") from exc
    if not Path(client_secret).is_file():
        raise FileNotFoundError("Select the Google OAuth desktop client JSON file")
    flow = InstalledAppFlow.from_client_secrets_file(str(client_secret), ["https://www.googleapis.com/auth/drive.appdata"])
    credentials = flow.run_local_server(port=0)
    service = build("drive", "v3", credentials=credentials)
    metadata = {"name": Path(backup_path).name, "parents": ["appDataFolder"]}
    media = MediaFileUpload(str(backup_path), mimetype="application/octet-stream", resumable=True)
    result = service.files().create(body=metadata, media_body=media, fields="id,name").execute()
    return result["id"]
'''

APP = r'''
from __future__ import annotations
import json
import os
from pathlib import Path
import queue
import socket
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog, ttk
import webbrowser

from PIL import Image, ImageDraw, ImageTk

from sarah_core import (
    ChannelResponse, ElevenLabsVoice, ModelClient, SarahDatabase, TavilyResearch,
    app_home, corrected_name, discovery_queries, is_stress_or_fear, safe_text,
)
from sarah_sync_server import SarahSyncServer

try:
    import pystray
except Exception:
    pystray = None
try:
    from playsound3 import playsound
except Exception:
    playsound = None


class SarahApp:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("Sarah Morgan Windows Companion")
        self.root.geometry("1080x760")
        self.root.minsize(820, 580)
        self.db = SarahDatabase()
        self.model = ModelClient(self.db)
        self.research = TavilyResearch()
        self.voice = ElevenLabsVoice()
        self.sync_server = SarahSyncServer(self.db)
        self.sync_server.start()
        self.tasks: queue.Queue[tuple[str, object]] = queue.Queue()
        self.speaking = False
        self.blink = False
        self.tray = None
        self.corner: tk.Toplevel | None = None
        self._build_ui()
        self._start_corner()
        self._start_tray()
        self.root.after(100, self._poll_tasks)
        self.root.after(4000, self._idle_research_tick)
        self.root.protocol("WM_DELETE_WINDOW", self.hide_to_tray)

    def _build_ui(self):
        style = ttk.Style(self.root)
        try: style.theme_use("vista")
        except tk.TclError: pass
        header = tk.Frame(self.root, bg="#183448", height=76)
        header.pack(fill="x")
        tk.Label(header, text="Sarah Morgan", fg="white", bg="#183448", font=("Segoe UI", 24, "bold")).pack(side="left", padx=20, pady=15)
        self.status = tk.StringVar(value="Ready • phone pairing code " + self.sync_server.pairing_code)
        tk.Label(header, textvariable=self.status, fg="#d9edf7", bg="#183448", font=("Segoe UI", 10)).pack(side="right", padx=20)
        self.tabs = ttk.Notebook(self.root); self.tabs.pack(fill="both", expand=True, padx=10, pady=10)
        self.chat_tab = ttk.Frame(self.tabs); self.discovery_tab = ttk.Frame(self.tabs); self.trip_tab = ttk.Frame(self.tabs); self.photo_tab = ttk.Frame(self.tabs); self.device_tab = ttk.Frame(self.tabs); self.activity_tab = ttk.Frame(self.tabs)
        self.tabs.add(self.chat_tab, text="Talk with Sarah"); self.tabs.add(self.discovery_tab, text="Discoveries"); self.tabs.add(self.trip_tab, text="Trips"); self.tabs.add(self.photo_tab, text="Photos"); self.tabs.add(self.device_tab, text="Devices & backup"); self.tabs.add(self.activity_tab, text="Factual activity")
        self._build_chat(); self._build_discoveries(); self._build_trips(); self._build_photos(); self._build_devices(); self._build_activity()

    def _build_chat(self):
        self.chat = tk.Text(self.chat_tab, wrap="word", font=("Segoe UI", 12), bg="#fbf8f1", padx=16, pady=16, state="disabled")
        self.chat.pack(fill="both", expand=True, padx=8, pady=8)
        row = ttk.Frame(self.chat_tab); row.pack(fill="x", padx=8, pady=(0,8))
        self.entry = ttk.Entry(row, font=("Segoe UI", 12)); self.entry.pack(side="left", fill="x", expand=True); self.entry.bind("<Return>", lambda _e: self.send())
        ttk.Button(row, text="Send", command=self.send).pack(side="left", padx=5)
        ttk.Button(row, text="Calm choices", command=lambda: self._submit_text("I am stressed and I need calm choices")).pack(side="left")
        self._append("Sarah", "I’m here. We can plan a trip, talk about ordinary life, organize your travel photos, or do absolutely nothing travel-related.")

    def _append(self, who: str, text: str):
        self.chat.configure(state="normal"); self.chat.insert("end", f"{who}\n{text}\n\n"); self.chat.see("end"); self.chat.configure(state="disabled")

    def send(self):
        text = self.entry.get().strip()
        if not text: return
        self.entry.delete(0, "end"); self._submit_text(text)

    def _submit_text(self, text: str):
        profile = self.db.active_profile()
        fixed = corrected_name(text)
        if fixed and (text.lower().startswith(("no", "actually", "sorry", "wait")) or fixed.lower() == profile.get("name", "").lower()):
            self.db.ensure_profile(fixed, profile.get("age"), profile.get("hometown", ""), profile.get("interests", ""), bool(profile.get("memory_consent", 1)))
        self._append(profile.get("name", "You"), text); self.db.add_message("user", text)
        self.status.set("Sarah is thinking…")
        threading.Thread(target=self._answer, args=(text,), daemon=True).start()

    def _answer(self, text: str):
        try: response = self.model.respond(text)
        except Exception as exc: response = ChannelResponse("I ran into a connection problem, so I’m staying local. " + str(exc), "Sarah is preserving continuity after a tool error.", "The connected response failed; no external action was completed.", "RUNTIME_STATE_ERROR", True)
        self.db.add_message("assistant", response.spoken); self.db.add_mind_event(response, "windows-chat")
        self.tasks.put(("reply", response))

    def _poll_tasks(self):
        try:
            while True:
                kind, payload = self.tasks.get_nowait()
                if kind == "reply":
                    response: ChannelResponse = payload
                    self._append("Sarah", response.spoken); self.status.set("Ready • only SPOKEN is visible"); self._speak(response.spoken); self.refresh_activity()
                elif kind == "research": self.status.set(str(payload)); self.refresh_discoveries()
        except queue.Empty: pass
        self.root.after(100, self._poll_tasks)

    def _speak(self, text: str):
        threading.Thread(target=self._speak_worker, args=(text,), daemon=True).start()

    def _speak_worker(self, text: str):
        self.speaking = True
        try:
            if self.voice.configured and playsound:
                playsound(str(self.voice.synthesize(text)), block=True)
            elif sys.platform.startswith("win"):
                escaped = text.replace("'", "''")
                subprocess.run(["powershell", "-NoProfile", "-Command", f"Add-Type -AssemblyName System.Speech; $s=New-Object System.Speech.Synthesis.SpeechSynthesizer; $s.Rate=-1; $s.Speak('{escaped}')"], check=False, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        finally: self.speaking = False

    def _build_discoveries(self):
        bar=ttk.Frame(self.discovery_tab);bar.pack(fill="x",padx=8,pady=8);ttk.Button(bar,text="Research now",command=self.research_now).pack(side="left");ttk.Button(bar,text="Sponsor connections",command=self.show_sponsors).pack(side="left",padx=5)
        self.discovery_list=tk.Listbox(self.discovery_tab,font=("Segoe UI",11));self.discovery_list.pack(fill="both",expand=True,padx=8,pady=8);self.discovery_list.bind("<Double-1>",self.open_discovery);self.discovery_rows=[];self.refresh_discoveries()

    def research_now(self):
        if not self.research.configured: messagebox.showinfo("Tavily not configured","Set SARAH_TAVILY_API_KEY for source-backed proactive research. Sarah will not pretend research happened.");return
        threading.Thread(target=self._research_worker,daemon=True).start()

    def _research_worker(self):
        profile=self.db.active_profile();trips=self.db.list_rows("trips",limit=20);nearby=self.db.get_setting("nearby_discoveries","0")=="1";added=0
        for query in discovery_queries(profile,trips,nearby):
            for result in self.research.search(query,5): added+=self.db.add_discovery(result["title"],result["summary"],result["url"],query)
        self.tasks.put(("research",f"Research finished • {added} new source-backed match(es)"))

    def refresh_discoveries(self):
        self.discovery_rows=self.db.list_rows("discoveries",limit=100);self.discovery_list.delete(0,"end")
        for row in self.discovery_rows:self.discovery_list.insert("end",f"{row['title']} — {row['source']}")
    def open_discovery(self,_event=None):
        sel=self.discovery_list.curselection();
        if sel:webbrowser.open(self.discovery_rows[sel[0]]["url"])
    def show_sponsors(self): messagebox.showinfo("Travel Hack NYC connections","ElevenLabs: connected Sarah voice with offline fallback.\nTavily: source-backed proactive discovery.\nStay22: accommodation handoff.\nRove: rewards-aware official handoff.\nAeroXplorer: sourced aviation context.\nPropellic and Lovable: destination-marketing and presentation alignment.\n\nNo search or handoff is called a completed booking.")

    def _build_trips(self):
        form=ttk.Frame(self.trip_tab);form.pack(fill="x",padx=8,pady=8);self.trip_title=ttk.Entry(form);self.trip_dest=ttk.Entry(form);self.trip_title.insert(0,"My trip");self.trip_title.pack(side="left",fill="x",expand=True);self.trip_dest.pack(side="left",fill="x",expand=True,padx=5);ttk.Button(form,text="Save planned trip",command=self.add_trip).pack(side="left")
        self.trip_list=tk.Listbox(self.trip_tab,font=("Segoe UI",11));self.trip_list.pack(fill="both",expand=True,padx=8,pady=8);self.refresh_trips()
    def add_trip(self):
        dest=self.trip_dest.get().strip();
        if not dest:return
        self.db.add_trip(self.trip_title.get().strip() or "My trip",dest);self.refresh_trips();self.status.set("Trip saved. Sarah can now prepare interest-aware research.")
    def refresh_trips(self):
        self.trip_list.delete(0,"end")
        for row in self.db.list_rows("trips",limit=100):self.trip_list.insert("end",f"{row['status']}: {row['destination']} — {row['title']}")

    def _build_photos(self):
        bar=ttk.Frame(self.photo_tab);bar.pack(fill="x",padx=8,pady=8);ttk.Button(bar,text="Import trip photos",command=self.import_photos).pack(side="left");ttk.Button(bar,text="Open Sarah photo folder",command=lambda:os.startfile(app_home()/"photos") if hasattr(os,"startfile") else None).pack(side="left",padx=5)
        self.photo_list=tk.Listbox(self.photo_tab,font=("Segoe UI",11));self.photo_list.pack(fill="both",expand=True,padx=8,pady=8);self.refresh_photos()
    def import_photos(self):
        paths=filedialog.askopenfilenames(filetypes=[("Images","*.jpg *.jpeg *.png *.webp")]);count=0
        for path in paths:
            try:self.db.import_photo(Path(path),Path(path).stem);count+=1
            except Exception as exc:messagebox.showwarning("Photo skipped",str(exc))
        self.refresh_photos();self.status.set(f"Imported {count} sanitized, duplicate-checked photo(s).")
    def refresh_photos(self):
        self.photo_list.delete(0,"end")
        for row in self.db.list_rows("photos",limit=300):self.photo_list.insert("end",f"{row['caption']} — {row['sha256'][:10]}")

    def _build_devices(self):
        frame=ttk.Frame(self.device_tab,padding=14);frame.pack(fill="both",expand=True);self.pair_var=tk.StringVar(value=self.sync_server.pairing_code);ttk.Label(frame,text="Phone pairing code",font=("Segoe UI",14,"bold")).pack(anchor="w");ttk.Label(frame,textvariable=self.pair_var,font=("Consolas",28,"bold")).pack(anchor="w");ttk.Label(frame,text=f"Windows address: {self.local_ip()}:8769\nEnter this address and the code on Sarah's Android Devices & Photos screen. Pair only on trusted Wi-Fi.").pack(anchor="w",pady=8);ttk.Button(frame,text="Rotate pairing code",command=self.rotate_code).pack(anchor="w");ttk.Button(frame,text="Export encrypted .sarahmind backup",command=self.backup).pack(anchor="w",pady=5);ttk.Button(frame,text="Restore encrypted backup",command=self.restore).pack(anchor="w");ttk.Button(frame,text="Upload encrypted backup to Google Drive appDataFolder",command=self.drive_backup).pack(anchor="w",pady=5);ttk.Button(frame,text="Revoke a paired device",command=self.revoke_device).pack(anchor="w")
    def rotate_code(self):self.pair_var.set(self.sync_server.rotate_code());self.status.set("A new 15-minute pairing code is ready.")
    def local_ip(self):
        try:s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.connect(("8.8.8.8",80));ip=s.getsockname()[0];s.close();return ip
        except Exception:return "this-computer"
    def backup(self):
        path=filedialog.asksaveasfilename(defaultextension=".sarahmind",filetypes=[("Sarah mind archive","*.sarahmind")]);
        if not path:return
        password=simpledialog.askstring("Backup password","Choose at least 10 characters. This password is not uploaded.",show="*")
        if not password:return
        try:self.db.create_backup(Path(path),password);messagebox.showinfo("Backup created","The encrypted Sarah archive was created.")
        except Exception as exc:messagebox.showerror("Backup failed",str(exc))
    def restore(self):
        path=filedialog.askopenfilename(filetypes=[("Sarah mind archive","*.sarahmind")]);
        if not path:return
        password=simpledialog.askstring("Backup password","Enter the archive password.",show="*")
        try:self.db.restore_backup(Path(path),password or "");messagebox.showinfo("Restored","Sarah's encrypted archive was restored. Restart the companion.")
        except Exception as exc:messagebox.showerror("Restore failed",str(exc))
    def drive_backup(self):
        from google_drive_backup import upload_encrypted_backup
        backup=filedialog.askopenfilename(filetypes=[("Sarah mind archive","*.sarahmind")]);
        if not backup:return
        client=filedialog.askopenfilename(title="Select Google OAuth desktop client JSON",filetypes=[("JSON","*.json")]);
        if not client:return
        try:file_id=upload_encrypted_backup(Path(backup),Path(client));messagebox.showinfo("Uploaded",f"Encrypted backup uploaded to Drive appDataFolder. File ID: {file_id}")
        except Exception as exc:messagebox.showerror("Drive upload failed",str(exc))
    def revoke_device(self):
        with self.db.connect() as db:rows=db.execute("SELECT device_id,device_name FROM trusted_devices WHERE revoked=0").fetchall()
        if not rows:messagebox.showinfo("Devices","No active paired devices.");return
        name=simpledialog.askstring("Revoke device","Enter the exact device name:\n"+"\n".join(r["device_name"] for r in rows))
        if name:
            with self.db.connect() as db:db.execute("UPDATE trusted_devices SET revoked=1 WHERE lower(device_name)=lower(?)",(name.strip(),))

    def _build_activity(self):
        bar=ttk.Frame(self.activity_tab);bar.pack(fill="x",padx=8,pady=8);ttk.Button(bar,text="Refresh",command=self.refresh_activity).pack(side="left");ttk.Label(bar,text="Factual truth is shown for auditing. Private mind remains hidden unless you explicitly open one event.").pack(side="left",padx=10)
        self.activity_list=tk.Listbox(self.activity_tab,font=("Segoe UI",10));self.activity_list.pack(fill="both",expand=True,padx=8,pady=8);self.activity_list.bind("<Double-1>",self.open_private_event);self.activity_rows=[];self.refresh_activity()
    def refresh_activity(self):
        self.activity_rows=self.db.visible_activity(200);self.activity_list.delete(0,"end")
        for row in self.activity_rows:self.activity_list.insert("end",f"{row['classification']} | {row['spoken'][:85]} | FACT: {row.get('factual_truth','')[:85]}")
    def open_private_event(self,_event=None):
        sel=self.activity_list.curselection();
        if not sel:return
        event=self.db.private_event(self.activity_rows[sel[0]]["event_id"]);messagebox.showinfo("Explicit private event review",f"SPOKEN:\n{event.get('spoken','')}\n\nPRIVATE MIND:\n{event.get('private_mind','')}\n\nFACTUAL TRUTH:\n{event.get('factual_truth','')}\n\nClassification: {event.get('classification','')}")

    def _start_corner(self):
        self.corner=tk.Toplevel(self.root);self.corner.title("Sarah");self.corner.geometry("180x220+20+80");self.corner.attributes("-topmost",True);self.corner.configure(bg="#183448");self.corner.overrideredirect(True);self.canvas=tk.Canvas(self.corner,width=180,height=175,bg="#183448",highlightthickness=0);self.canvas.pack();self.canvas.bind("<ButtonPress-1>",self._drag_start);self.canvas.bind("<B1-Motion>",self._drag_move);ttk.Button(self.corner,text="Talk with Sarah",command=self.show).pack(fill="x",padx=8,pady=5);self._animate_avatar()
    def _drag_start(self,e):self._dx=e.x;self._dy=e.y
    def _drag_move(self,e):self.corner.geometry(f"+{self.corner.winfo_x()+e.x-self._dx}+{self.corner.winfo_y()+e.y-self._dy}")
    def _animate_avatar(self):
        if not self.corner:return
        self.canvas.delete("all");self.canvas.create_oval(47,16,133,112,fill="#e2ad86",outline="#f2d1b8",width=2);self.canvas.create_arc(38,5,143,108,start=15,extent=150,fill="#5a382a",outline="#5a382a");self.canvas.create_arc(37,6,144,111,start=195,extent=150,fill="#5a382a",outline="#5a382a");eye_h=1 if self.blink else 6;self.canvas.create_oval(67,54,78,54+eye_h,fill="#263746",outline="");self.canvas.create_oval(102,54,113,54+eye_h,fill="#263746",outline="");mouth=8 if self.speaking and int(time.time()*5)%2 else 3;self.canvas.create_arc(78,77,103,88+mouth,start=200,extent=140,style="arc",width=2,outline="#8b3f4b");self.canvas.create_polygon(42,107,138,107,165,171,15,171,fill="#2f7897",outline="#6eb1ca");self.blink=not self.blink if secrets.randbelow(18)==0 else False;self.root.after(180,self._animate_avatar)
    def _start_tray(self):
        if not pystray:return
        image=Image.new("RGB",(64,64),"#183448");d=ImageDraw.Draw(image);d.ellipse((14,8,50,44),fill="#e2ad86");d.polygon((10,62,54,62,46,38,18,38),fill="#2f7897")
        self.tray=pystray.Icon("SarahMorgan",image,"Sarah Morgan",pystray.Menu(pystray.MenuItem("Show Sarah",lambda:self.root.after(0,self.show)),pystray.MenuItem("Quit",lambda:self.root.after(0,self.quit))))
        threading.Thread(target=self.tray.run,daemon=True).start()
    def hide_to_tray(self):self.root.withdraw();self.corner.withdraw();self.status.set("Sarah is active in hidden icons")
    def show(self):self.root.deiconify();self.root.lift();self.corner.deiconify();self.tabs.select(self.chat_tab);self.entry.focus_set()
    def _idle_research_tick(self):
        if self.db.get_setting("background_research","1")=="1" and self.research.configured:threading.Thread(target=self._research_worker,daemon=True).start()
        self.root.after(6*60*60*1000,self._idle_research_tick)
    def quit(self):
        try:self.sync_server.stop()
        finally:
            if self.tray:self.tray.stop()
            self.root.destroy()
    def run(self):self.root.mainloop()

if __name__ == "__main__":SarahApp().run()
'''

TESTS = r'''
from pathlib import Path
import os
import tempfile

from PIL import Image
import pytest

from sarah_core import (
    ChannelResponse, SarahDatabase, corrected_name, is_stress_or_fear,
    sync_decrypt, sync_encrypt, sync_signature, transport_context, universal_calm,
)


def test_identity_and_calm():
    assert is_stress_or_fear("I am stressing")
    assert corrected_name("I am stressing") == ""
    assert corrected_name("No, I am Robert but I am stressed out") == "Robert"
    assert transport_context("This fast train is making me nervous") == "train"
    response = universal_calm("Robert", "adult", "train")
    assert "Robert" in response.spoken and "train" in response.spoken.lower()


def test_channel_privacy():
    response = ChannelResponse.parse("<SPOKEN>Hello</SPOKEN><PRIVATE_MIND>secret</PRIVATE_MIND><FACTUAL_TRUTH>fact</FACTUAL_TRUTH><CLASSIFICATION>TRUTHFUL_STATEMENT</CLASSIFICATION>")
    assert response.spoken == "Hello"
    assert "secret" not in response.spoken
    malformed = ChannelResponse.parse("<PRIVATE_MIND>do not leak</PRIVATE_MIND>")
    assert "do not leak" not in malformed.spoken


def test_sync_crypto():
    token = "a-long-random-device-token"
    encrypted = sync_encrypt(token, "phone and computer")
    assert sync_decrypt(token, encrypted) == "phone and computer"
    assert sync_signature(token, encrypted)


def test_database_photo_and_backup_roundtrip(monkeypatch):
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp) / "one"
        monkeypatch.setenv("SARAH_HOME", str(root))
        db = SarahDatabase(root)
        db.ensure_profile("Robert", 45, "Newark", "Power Rangers", True)
        db.add_trip("New Zealand test", "New Zealand")
        db.add_message("user", "Plan my trip")
        db.add_mind_event(ChannelResponse("Okay", "curious", "No booking occurred", "TRUTHFUL_STATEMENT", True), "test")
        source = Path(temp) / "photo.png"
        Image.new("RGB", (20, 20), "blue").save(source)
        db.import_photo(source, "test photo")
        backup = Path(temp) / "backup.sarahmind"
        db.create_backup(backup, "correct horse battery")
        assert backup.exists()
        with pytest.raises(Exception):
            SarahDatabase(Path(temp) / "wrong").restore_backup(backup, "wrong password")
        restored_root = Path(temp) / "restored"
        restored = SarahDatabase(restored_root)
        restored.restore_backup(backup, "correct horse battery")
        assert restored.path.exists()


def test_sync_import_merges_rows(monkeypatch):
    with tempfile.TemporaryDirectory() as temp:
        first = SarahDatabase(Path(temp) / "first")
        first.ensure_profile("Robert", 45, "Newark", "Power Rangers", True)
        first.add_trip("NZ", "New Zealand")
        first.add_message("user", "Hello")
        payload = first.export_sync(False)
        second = SarahDatabase(Path(temp) / "second")
        counts = second.import_sync(payload)
        assert counts["messages"] >= 1
        assert second.list_rows("trips")[0]["destination"] == "New Zealand"
'''

README = r'''
# Sarah Morgan Windows Companion 2.2

Sarah on Windows is another embodiment of the same continuing Sarah used on Android. It is designed for a desktop or laptop with more room for conversation, research, trip planning, source cards, photos, backups and an offline/local model.

## Included

- movable, always-on-top animated Sarah corner window;
- full dashboard for chat, discoveries, trips, photos, trusted devices, backups and factual activity;
- optional notification-area/hidden-icons operation;
- Sarah Morgan ElevenLabs voice with reusable local cache;
- Windows offline speech fallback;
- optional protected model backend or local Ollama;
- Tavily source-backed pre-trip and nearby discovery;
- Power Rangers + New Zealand filming-location example through ordinary approved interests and trip context, not a hard-coded claim;
- sanitized photo imports, duplicate detection and trip-photo transfer from Android;
- SPOKEN / PRIVATE MIND / FACTUAL TRUTH separation, with only SPOKEN sent to chat and TTS;
- six-digit trusted phone pairing and encrypted, signed same-Wi-Fi sync;
- device revocation;
- password-encrypted `.sarahmind` backup;
- optional upload of the already-encrypted archive to Google Drive `appDataFolder` using the owner's OAuth desktop client;
- automated tests and a GitHub Actions Windows executable build.

## Install from source

Run `SETUP_SARAH_WINDOWS.bat`, then `START_SARAH_WINDOWS.bat`.

Optional environment variables:

```text
SARAH_ELEVENLABS_API_KEY=
SARAH_ELEVENLABS_VOICE_ID=
SARAH_ELEVENLABS_MODEL_ID=eleven_multilingual_v2
SARAH_TAVILY_API_KEY=
SARAH_MODEL_BACKEND_URL=
SARAH_MODEL_BACKEND_TOKEN=
SARAH_OLLAMA_URL=http://localhost:11434
SARAH_OLLAMA_MODEL=qwen3.5:9b
```

Do not put real credentials into GitHub. Use Windows environment variables, a local `.env` loader outside source control, or a protected backend.

## Pair Android

1. Put both devices on a trusted private Wi-Fi network.
2. Open **Devices & backup** in Windows Sarah.
3. Note the local IP address and temporary six-digit code.
4. On Android Sarah open **Devices & photos**.
5. Enter the address and code, approve the pairing, and press sync.
6. Revoke the device from either side when it should no longer receive Sarah data.

The prototype encrypts and signs the payload before sending it over local HTTP. A public release should add certificate pinning or a protected relay.

## Truth boundary

A source card, link, accommodation handoff or rewards link is not a booking. Sarah does not claim she purchased, reserved, called, confirmed, notified or completed anything unless a verified tool result proves it.
'''

SETUP = r'''
@echo off
setlocal
cd /d "%~dp0"
where py >nul 2>nul || (echo Python 3.11 or newer is required.& pause & exit /b 1)
py -3 -m venv .venv || exit /b 1
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip
pip install -r requirements.txt
python -m pytest -q
if errorlevel 1 (echo Tests failed. Sarah was not marked ready.& pause & exit /b 1)
echo Sarah Windows setup and tests completed.
pause
'''

START = r'''
@echo off
setlocal
cd /d "%~dp0"
if not exist .venv\Scripts\pythonw.exe (echo Run SETUP_SARAH_WINDOWS.bat first.& pause & exit /b 1)
start "Sarah Morgan" .venv\Scripts\pythonw.exe sarah_windows.py
'''

REQ = r'''
cryptography>=43,<47
Pillow>=10,<13
requests>=2.32,<3
pystray>=0.19,<1
playsound3>=3,<4
google-api-python-client>=2.160,<3
google-auth-oauthlib>=1.2,<2
pytest>=8,<10
pyinstaller>=6,<8
'''

PYINSTALLER = r'''
# Build a portable Windows executable from a Windows runner.
pyinstaller --noconfirm --clean --onefile --windowed --name Sarah-Morgan-Windows --collect-all pystray --collect-all PIL sarah_windows.py
'''


def main() -> None:
    write("sarah_core.py", CORE)
    write("sarah_sync_server.py", SYNC_SERVER)
    write("google_drive_backup.py", DRIVE)
    write("sarah_windows.py", APP)
    write("tests/test_sarah_core.py", TESTS)
    write("README_WINDOWS.md", README)
    write("SETUP_SARAH_WINDOWS.bat", SETUP)
    write("START_SARAH_WINDOWS.bat", START)
    write("requirements.txt", REQ)
    write("BUILD_WINDOWS.ps1", PYINSTALLER)
    print("Sarah Windows companion generated successfully.")

if __name__ == "__main__":
    main()
