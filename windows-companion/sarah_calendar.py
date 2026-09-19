from __future__ import annotations

import hashlib
import json
import re
import secrets
import time
from datetime import datetime
from typing import Any, Mapping

from sarah_core import SarahDatabase, now_ms, safe_text


PROPOSAL_SCHEMA = "sarah-email-event-proposal-v1"
CALENDAR_SCHEMA = "sarah-owner-calendar-v1"
MAX_FIELD = 2_000
MAX_PROPOSALS_PER_SCAN = 50
ALLOWED_KINDS = {"event", "flight", "train", "bus", "lodging", "other"}


class SarahCalendarError(RuntimeError):
    """Calendar data could not be verified or safely changed."""


def _bounded(value: Any, limit: int = MAX_FIELD) -> str:
    return " ".join(safe_text(value).split())[:limit]


def _canonical(value: Mapping[str, Any]) -> bytes:
    return json.dumps(
        dict(value), sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")


def _sha256(value: bytes | str) -> str:
    raw = value.encode("utf-8") if isinstance(value, str) else value
    return hashlib.sha256(raw).hexdigest()


def _kind_for(text: str) -> str:
    value = text.lower()
    if any(word in value for word in ("flight", "airline", "boarding", "airport")):
        return "flight"
    if any(word in value for word in ("train", "amtrak", "rail")):
        return "train"
    if any(word in value for word in ("bus", "coach", "greyhound")):
        return "bus"
    if any(word in value for word in ("hotel", "room", "check-in", "check in")):
        return "lodging"
    if any(word in value for word in ("event", "ticket", "concert", "convention", "festival", "show")):
        return "event"
    return "other"


def _suggested_explicit_time(text: str) -> str:
    """Return only an explicit ISO-like date found in the visible metadata.

    An email's Date header is when the message was sent, not when an event
    happens, so it is deliberately excluded from this inference.
    """

    match = re.search(
        r"\b(20\d{2})-(\d{2})-(\d{2})(?:[ T](\d{1,2}):(\d{2})(?:\s*(AM|PM))?)?\b",
        text,
        flags=re.IGNORECASE,
    )
    if not match:
        return ""
    year, month, day = (int(match.group(index)) for index in (1, 2, 3))
    hour = int(match.group(4) or 9)
    minute = int(match.group(5) or 0)
    meridiem = (match.group(6) or "").upper()
    if meridiem:
        if hour < 1 or hour > 12:
            return ""
        hour = (hour % 12) + (12 if meridiem == "PM" else 0)
    try:
        value = datetime(year, month, day, hour, minute)
    except ValueError:
        return ""
    return value.strftime("%Y-%m-%d %H:%M")


def _suggested_labeled_time(text: str, *, ending: bool) -> str:
    """Return a role-labelled time without discarding an explicit UTC offset."""

    labels = (
        r"(?:arriv(?:al|e|es|ing)?|end(?:s|ing)?)"
        if ending
        else r"(?:depart(?:ure|s|ing)?|leav(?:e|es|ing)|start(?:s|ing)?|begin(?:s|ning)?)"
    )
    match = re.search(
        rf"\b{labels}\b[^\r\n]{{0,48}}?"
        r"\b(20\d{2}-\d{2}-\d{2}(?:[ T]\d{1,2}:\d{2}"
        r"(?::\d{2}(?:\.\d{1,9})?)?(?:\s*(?:AM|PM)|Z|[+-]\d{2}:\d{2})?))",
        text,
        flags=re.IGNORECASE,
    )
    if not match:
        return ""
    value = match.group(1).strip()
    if value.upper().endswith("Z") or re.search(r"[+-]\d{2}:\d{2}$", value):
        try:
            return datetime.fromisoformat(value.replace("Z", "+00:00")).isoformat()
        except ValueError:
            return ""
    return _suggested_explicit_time(value)


def build_email_event_proposal(
    row: Mapping[str, Any],
    *,
    account_email: str,
    fetched_at_ms: int | None = None,
) -> dict[str, Any]:
    """Create a source-bound proposal; this never creates a calendar event."""

    message_id = _bounded(row.get("message_id"), 512)
    if not message_id:
        raise SarahCalendarError("Email candidate has no opaque Gmail message ID")
    source = {
        "source": "gmail.readonly",
        "account_sha256": _sha256(_bounded(account_email).lower()),
        "message_id": message_id,
        "thread_id": _bounded(row.get("thread_id"), 512),
        "internal_date_ms": _bounded(row.get("internal_date_ms"), 64),
        "from": _bounded(row.get("from"), 512),
        "subject": _bounded(row.get("subject"), 1_000),
        "email_date_header": _bounded(row.get("date"), 512),
        "fetched_at_ms": int(fetched_at_ms if fetched_at_ms is not None else now_ms()),
        "message_modified": False,
        "calendar_saved": False,
        "attendance_claimed": False,
    }
    source_identity = {
        "source": source["source"],
        "account_sha256": source["account_sha256"],
        "message_id": source["message_id"],
    }
    source_hash = _sha256(_canonical(source_identity))
    visible = source["subject"]
    return {
        "schema": PROPOSAL_SCHEMA,
        "source_hash": source_hash,
        "source": source,
        "title": source["subject"] or "Email event or travel item",
        "kind": _kind_for(visible),
        "suggested_start_local": (
            _suggested_labeled_time(visible, ending=False)
            or _suggested_explicit_time(visible)
        ),
        "suggested_end_local": _suggested_labeled_time(visible, ending=True),
        "status": "pending_owner_decision",
    }


def parse_owner_local_time(value: str) -> tuple[int, str]:
    text = _bounded(value, 80)
    try:
        explicit = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        explicit = None
    if explicit is not None and explicit.tzinfo is not None:
        return int(explicit.timestamp() * 1000), explicit.isoformat()
    for fmt in ("%Y-%m-%d %H:%M", "%Y-%m-%d %I:%M %p", "%Y-%m-%d"):
        try:
            parsed = datetime.strptime(text, fmt)
        except ValueError:
            continue
        candidates: list[int] = []
        for is_dst in (0, 1):
            try:
                epoch_seconds = int(time.mktime((
                    parsed.year, parsed.month, parsed.day, parsed.hour,
                    parsed.minute, parsed.second, -1, -1, is_dst,
                )))
            except (OverflowError, OSError, ValueError):
                continue
            round_trip = time.localtime(epoch_seconds)
            if (
                round_trip.tm_year == parsed.year
                and round_trip.tm_mon == parsed.month
                and round_trip.tm_mday == parsed.day
                and round_trip.tm_hour == parsed.hour
                and round_trip.tm_min == parsed.minute
                and round_trip.tm_sec == parsed.second
                and round_trip.tm_isdst == is_dst
            ):
                candidates.append(epoch_seconds)
        candidates = sorted(set(candidates))
        if not candidates:
            raise SarahCalendarError(
                "That local time does not exist because of a clock change; enter an ISO time with its UTC offset"
            )
        if len(candidates) > 1:
            raise SarahCalendarError(
                "That local time occurs twice because of a clock change; enter an ISO time with its UTC offset"
            )
        local = datetime.fromtimestamp(candidates[0]).astimezone()
        return candidates[0] * 1000, local.isoformat()
    raise SarahCalendarError("Enter the date as YYYY-MM-DD HH:MM")


def migrate_calendar_person_rows(connection: Any, old_person_id: str, new_person_id: str) -> None:
    """Migrate optional calendar tables inside SarahDatabase's rename transaction."""

    tables = {
        row[0]
        for row in connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table'"
        ).fetchall()
    }
    required = {
        "email_event_proposals",
        "email_event_proposal_history",
        "owner_calendar_events",
        "owner_calendar_history",
        "owner_calendar_reminders",
    }
    if not required.issubset(tables):
        return
    old_id = _bounded(old_person_id, 128)
    new_id = _bounded(new_person_id, 128)
    for row in connection.execute(
        "SELECT proposal_id,source_hash FROM email_event_proposals WHERE person_id=?",
        (old_id,),
    ).fetchall():
        target = connection.execute(
            "SELECT proposal_id FROM email_event_proposals WHERE person_id=? AND source_hash=?",
            (new_id, row["source_hash"]),
        ).fetchone()
        if target is None:
            connection.execute(
                "UPDATE email_event_proposals SET person_id=? WHERE proposal_id=?",
                (new_id, row["proposal_id"]),
            )
            continue
        target_id = target["proposal_id"]
        connection.execute(
            "UPDATE email_event_proposal_history SET proposal_id=?,person_id=? WHERE proposal_id=?",
            (target_id, new_id, row["proposal_id"]),
        )
        connection.execute(
            "UPDATE owner_calendar_events SET source_proposal_id=? WHERE source_proposal_id=?",
            (target_id, row["proposal_id"]),
        )
        connection.execute(
            "DELETE FROM email_event_proposals WHERE proposal_id=?",
            (row["proposal_id"],),
        )
    for table in (
        "email_event_proposal_history",
        "owner_calendar_events",
        "owner_calendar_history",
        "owner_calendar_reminders",
    ):
        connection.execute(
            f"UPDATE {table} SET person_id=? WHERE person_id=?",
            (new_id, old_id),
        )


class SarahCalendarStore:
    """Profile-isolated proposals, owner calendar, and opt-in reminders."""

    def __init__(self, database: SarahDatabase):
        self.db = database
        self._initialize()

    def _initialize(self) -> None:
        schema = """
        CREATE TABLE IF NOT EXISTS email_event_proposals(
            proposal_id TEXT PRIMARY KEY, person_id TEXT NOT NULL,
            source_hash TEXT NOT NULL, payload_enc TEXT NOT NULL,
            status TEXT NOT NULL, created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL, UNIQUE(person_id,source_hash));
        CREATE TABLE IF NOT EXISTS email_event_proposal_history(
            history_id TEXT PRIMARY KEY, proposal_id TEXT NOT NULL,
            person_id TEXT NOT NULL, status TEXT NOT NULL,
            owner_action TEXT NOT NULL, payload_enc TEXT NOT NULL,
            created_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS owner_calendar_events(
            event_id TEXT PRIMARY KEY, person_id TEXT NOT NULL,
            source_proposal_id TEXT NOT NULL, source_hash TEXT NOT NULL,
            payload_enc TEXT NOT NULL, start_at_ms INTEGER NOT NULL,
            end_at_ms INTEGER, status TEXT NOT NULL,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS owner_calendar_history(
            history_id TEXT PRIMARY KEY, event_id TEXT NOT NULL,
            person_id TEXT NOT NULL, action TEXT NOT NULL,
            owner_action TEXT NOT NULL, payload_enc TEXT NOT NULL,
            created_at INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS owner_calendar_reminders(
            reminder_id TEXT PRIMARY KEY, event_id TEXT NOT NULL,
            person_id TEXT NOT NULL, lead_minutes INTEGER NOT NULL,
            notify_at_ms INTEGER NOT NULL, status TEXT NOT NULL,
            delivered_at_ms INTEGER, lease_token TEXT NOT NULL DEFAULT '',
            lease_until_ms INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
            UNIQUE(event_id,lead_minutes));
        """
        with self.db.lock, self.db.connect() as connection:
            connection.executescript(schema)
            columns = {
                row[1]
                for row in connection.execute(
                    "PRAGMA table_info(owner_calendar_reminders)"
                ).fetchall()
            }
            if "lease_token" not in columns:
                connection.execute(
                    "ALTER TABLE owner_calendar_reminders ADD COLUMN lease_token TEXT NOT NULL DEFAULT ''"
                )
            if "lease_until_ms" not in columns:
                connection.execute(
                    "ALTER TABLE owner_calendar_reminders ADD COLUMN lease_until_ms INTEGER NOT NULL DEFAULT 0"
                )

    def _person(self, person_id: str | None = None) -> str:
        value = _bounded(person_id or self.db.get_setting("active_person_id"), 128)
        if not value:
            raise SarahCalendarError("Sarah has no active owner profile")
        return value

    def _encrypt(self, payload: Mapping[str, Any]) -> str:
        return self.db.crypto.encrypt(_canonical(payload).decode("utf-8"))

    def _decrypt(self, value: str) -> dict[str, Any]:
        try:
            parsed = json.loads(self.db.crypto.decrypt(value))
        except Exception as exc:
            raise SarahCalendarError("Calendar state could not be decrypted") from exc
        if not isinstance(parsed, dict):
            raise SarahCalendarError("Calendar state is not one object")
        return parsed

    def ingest_email_candidates(
        self,
        rows: list[Mapping[str, Any]],
        *,
        account_email: str,
        person_id: str | None = None,
    ) -> list[dict[str, Any]]:
        person = self._person(person_id)
        created: list[dict[str, Any]] = []
        prepared: list[tuple[dict[str, Any], str, int, str]] = []
        for row in list(rows)[:MAX_PROPOSALS_PER_SCAN]:
            proposal = build_email_event_proposal(row, account_email=account_email)
            proposal_id = _sha256(f"{person}|{proposal['source_hash']}")
            timestamp = now_ms()
            encrypted = self._encrypt(proposal)
            prepared.append((proposal, proposal_id, timestamp, encrypted))
        with self.db.lock, self.db.connect() as connection:
            for proposal, proposal_id, timestamp, encrypted in prepared:
                cursor = connection.execute(
                    "INSERT OR IGNORE INTO email_event_proposals VALUES(?,?,?,?,?,?,?)",
                    (
                        proposal_id, person, proposal["source_hash"], encrypted,
                        "pending_owner_decision", timestamp, timestamp,
                    ),
                )
                if cursor.rowcount != 1:
                    continue
                history_id = secrets.token_hex(16)
                connection.execute(
                    "INSERT INTO email_event_proposal_history VALUES(?,?,?,?,?,?,?)",
                    (
                        history_id, proposal_id, person, "pending_owner_decision",
                        "Sarah identified a source-bound candidate; no owner decision yet",
                        encrypted, timestamp,
                    ),
                )
                proposal["proposal_id"] = proposal_id
                created.append(proposal)
        return created

    def proposals(
        self, *, status: str | None = None, person_id: str | None = None
    ) -> list[dict[str, Any]]:
        person = self._person(person_id)
        query = "SELECT * FROM email_event_proposals WHERE person_id=?"
        parameters: list[Any] = [person]
        if status:
            query += " AND status=?"
            parameters.append(_bounded(status, 80))
        query += " ORDER BY created_at DESC"
        with self.db.connect() as connection:
            rows = connection.execute(query, tuple(parameters)).fetchall()
        result = []
        for row in rows:
            payload = self._decrypt(row["payload_enc"])
            payload.update({"proposal_id": row["proposal_id"], "status": row["status"]})
            result.append(payload)
        return result

    def purge_unsaved_email_proposals(self, *, person_id: str | None = None) -> int:
        """Remove unsaved Gmail-derived content when its profile disconnects."""

        person = self._person(person_id)
        with self.db.lock, self.db.connect() as connection:
            rows = connection.execute(
                "SELECT proposal_id FROM email_event_proposals WHERE person_id=? "
                "AND status IN ('pending_owner_decision','owner_rejected')",
                (person,),
            ).fetchall()
            proposal_ids = [row["proposal_id"] for row in rows]
            for proposal_id in proposal_ids:
                connection.execute(
                    "DELETE FROM email_event_proposal_history WHERE proposal_id=? AND person_id=?",
                    (proposal_id, person),
                )
                connection.execute(
                    "DELETE FROM email_event_proposals WHERE proposal_id=? AND person_id=?",
                    (proposal_id, person),
                )
        return len(proposal_ids)

    def decide(
        self,
        proposal_id: str,
        *,
        remember: bool,
        owner_action: str,
        title: str = "",
        start_local: str = "",
        end_local: str = "",
        location: str = "",
        kind: str = "",
        person_id: str | None = None,
    ) -> dict[str, Any] | None:
        person = self._person(person_id)
        with self.db.lock, self.db.connect() as connection:
            row = connection.execute(
                "SELECT * FROM email_event_proposals WHERE proposal_id=? AND person_id=?",
                (_bounded(proposal_id, 128), person),
            ).fetchone()
            if row is None:
                raise SarahCalendarError("That exact email proposal is unavailable")
            if row["status"] != "pending_owner_decision":
                raise SarahCalendarError("That exact email proposal already has an owner decision")
            proposal = self._decrypt(row["payload_enc"])
            timestamp = now_ms()
            status = "owner_accepted" if remember else "owner_rejected"
            decision_payload = dict(proposal)
            decision_payload["source"] = dict(proposal.get("source", {}))
            decision_payload["source"]["calendar_saved"] = bool(remember)
            decision_payload["status"] = status
            connection.execute(
                "UPDATE email_event_proposals SET status=?,payload_enc=?,updated_at=? WHERE proposal_id=?",
                (status, self._encrypt(decision_payload), timestamp, row["proposal_id"]),
            )
            connection.execute(
                "INSERT INTO email_event_proposal_history VALUES(?,?,?,?,?,?,?)",
                (
                    secrets.token_hex(16), row["proposal_id"], person, status,
                    _bounded(owner_action, 1_000), self._encrypt(decision_payload), timestamp,
                ),
            )
            if not remember:
                return None
            start_at_ms, start_iso = parse_owner_local_time(start_local)
            end_at_ms: int | None = None
            end_iso = ""
            if _bounded(end_local, 80):
                end_at_ms, end_iso = parse_owner_local_time(end_local)
                if end_at_ms < start_at_ms:
                    raise SarahCalendarError(
                        "The end or arrival time cannot precede the start or departure time"
                    )
            event_kind = _bounded(kind or proposal.get("kind"), 40).lower()
            if event_kind not in ALLOWED_KINDS:
                event_kind = "other"
            event_payload = {
                "schema": CALENDAR_SCHEMA,
                "title": _bounded(title or proposal.get("title"), 500),
                "location": _bounded(location, 500),
                "kind": event_kind,
                "start_local": start_iso,
                "end_local": end_iso,
                "source": "owner_confirmed_gmail_proposal",
                "source_proposal_id": row["proposal_id"],
                "source_hash": row["source_hash"],
                "attendance_claimed": False,
                "travel_completed_claimed": False,
            }
            if not event_payload["title"]:
                raise SarahCalendarError("A calendar title is required")
            event_id = _sha256(f"{person}|{row['proposal_id']}|{start_at_ms}")
            encrypted = self._encrypt(event_payload)
            connection.execute(
                "INSERT INTO owner_calendar_events VALUES(?,?,?,?,?,?,?,?,?,?)",
                (
                    event_id, person, row["proposal_id"], row["source_hash"], encrypted,
                    start_at_ms, end_at_ms, "scheduled", timestamp, timestamp,
                ),
            )
            connection.execute(
                "INSERT INTO owner_calendar_history VALUES(?,?,?,?,?,?,?)",
                (
                    secrets.token_hex(16), event_id, person, "created",
                    _bounded(owner_action, 1_000), encrypted, timestamp,
                ),
            )
        event_payload.update({
            "event_id": event_id,
            "start_at_ms": start_at_ms,
            "end_at_ms": end_at_ms,
            "status": "scheduled",
        })
        return event_payload

    def events(self, *, person_id: str | None = None) -> list[dict[str, Any]]:
        person = self._person(person_id)
        with self.db.connect() as connection:
            rows = connection.execute(
                "SELECT * FROM owner_calendar_events WHERE person_id=? ORDER BY start_at_ms",
                (person,),
            ).fetchall()
        result = []
        for row in rows:
            payload = self._decrypt(row["payload_enc"])
            payload.update(
                {
                    "event_id": row["event_id"],
                    "start_at_ms": row["start_at_ms"],
                    "end_at_ms": row["end_at_ms"],
                    "status": row["status"],
                }
            )
            result.append(payload)
        return result

    def add_reminder(
        self,
        event_id: str,
        *,
        lead_minutes: int,
        owner_action: str,
        person_id: str | None = None,
    ) -> dict[str, Any]:
        person = self._person(person_id)
        lead = int(lead_minutes)
        if lead < 0 or lead > 525_600:
            raise SarahCalendarError("Reminder lead time must be between 0 and 525600 minutes")
        with self.db.lock, self.db.connect() as connection:
            event = connection.execute(
                "SELECT * FROM owner_calendar_events WHERE event_id=? AND person_id=?",
                (_bounded(event_id, 128), person),
            ).fetchone()
            if event is None or event["status"] != "scheduled":
                raise SarahCalendarError("That scheduled calendar item is unavailable")
            notify_at = int(event["start_at_ms"]) - lead * 60_000
            timestamp = now_ms()
            reminder_id = _sha256(f"{event['event_id']}|{lead}")
            connection.execute(
                "INSERT INTO owner_calendar_reminders("
                "reminder_id,event_id,person_id,lead_minutes,notify_at_ms,status,"
                "delivered_at_ms,lease_token,lease_until_ms,created_at,updated_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,?) "
                "ON CONFLICT(event_id,lead_minutes) DO UPDATE SET "
                "notify_at_ms=excluded.notify_at_ms,status='enabled',delivered_at_ms=NULL,"
                "lease_token='',lease_until_ms=0,updated_at=excluded.updated_at",
                (
                    reminder_id, event["event_id"], person, lead, notify_at,
                    "enabled", None, "", 0, timestamp, timestamp,
                ),
            )
            payload = self._decrypt(event["payload_enc"])
            connection.execute(
                "INSERT INTO owner_calendar_history VALUES(?,?,?,?,?,?,?)",
                (
                    secrets.token_hex(16), event["event_id"], person, "reminder_enabled",
                    _bounded(owner_action, 1_000), self._encrypt({
                        "event": payload,
                        "lead_minutes": lead,
                        "notify_at_ms": notify_at,
                    }), timestamp,
                ),
            )
        return {
            "reminder_id": reminder_id,
            "event_id": event_id,
            "lead_minutes": lead,
            "notify_at_ms": notify_at,
            "status": "enabled",
        }

    def due_reminders(
        self, *, at_ms: int | None = None, person_id: str | None = None
    ) -> list[dict[str, Any]]:
        person = self._person(person_id)
        moment = int(at_ms if at_ms is not None else now_ms())
        with self.db.connect() as connection:
            rows = connection.execute(
                "SELECT reminder.*,event.payload_enc FROM owner_calendar_reminders AS reminder "
                "JOIN owner_calendar_events AS event ON event.event_id=reminder.event_id "
                "WHERE reminder.person_id=? AND reminder.status='enabled' "
                "AND reminder.notify_at_ms<=? AND event.start_at_ms>=? "
                "AND event.status='scheduled' "
                "ORDER BY reminder.notify_at_ms",
                (person, moment, moment - 24 * 60 * 60 * 1000),
            ).fetchall()
        result = []
        for row in rows:
            event = self._decrypt(row["payload_enc"])
            result.append(
                {
                    "reminder_id": row["reminder_id"],
                    "event_id": row["event_id"],
                    "lead_minutes": row["lead_minutes"],
                    "notify_at_ms": row["notify_at_ms"],
                    "title": event.get("title", "Calendar item"),
                    "start_local": event.get("start_local", ""),
                }
            )
        return result

    def claim_due_reminders(
        self,
        *,
        at_ms: int | None = None,
        person_id: str | None = None,
        lease_ms: int = 5 * 60 * 1000,
        limit: int = 20,
    ) -> list[dict[str, Any]]:
        """Atomically lease reminders so two Sarah processes cannot both notify."""

        person = self._person(person_id)
        moment = int(at_ms if at_ms is not None else now_ms())
        lease = int(lease_ms)
        count = int(limit)
        if lease < 30_000 or lease > 30 * 60 * 1000 or count < 1 or count > 100:
            raise SarahCalendarError("Reminder lease request is outside the bounded range")
        claimed: list[dict[str, Any]] = []
        with self.db.lock, self.db.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute(
                "UPDATE owner_calendar_reminders SET status='enabled',lease_token='',lease_until_ms=0 "
                "WHERE person_id=? AND status='dispatching' AND lease_until_ms<?",
                (person, moment),
            )
            connection.execute(
                "UPDATE owner_calendar_reminders SET status='expired',lease_token='',lease_until_ms=0,updated_at=? "
                "WHERE person_id=? AND status='enabled' AND event_id IN ("
                "SELECT event_id FROM owner_calendar_events WHERE person_id=? AND start_at_ms<?)",
                (moment, person, person, moment - 24 * 60 * 60 * 1000),
            )
            rows = connection.execute(
                "SELECT reminder.*,event.payload_enc FROM owner_calendar_reminders AS reminder "
                "JOIN owner_calendar_events AS event ON event.event_id=reminder.event_id "
                "WHERE reminder.person_id=? AND reminder.status='enabled' "
                "AND reminder.notify_at_ms<=? AND event.start_at_ms>=? "
                "AND event.status='scheduled' ORDER BY reminder.notify_at_ms LIMIT ?",
                (person, moment, moment - 24 * 60 * 60 * 1000, count),
            ).fetchall()
            for row in rows:
                token = secrets.token_urlsafe(24)
                cursor = connection.execute(
                    "UPDATE owner_calendar_reminders SET status='dispatching',lease_token=?,"
                    "lease_until_ms=?,updated_at=? WHERE reminder_id=? AND person_id=? AND status='enabled'",
                    (token, moment + lease, moment, row["reminder_id"], person),
                )
                if cursor.rowcount != 1:
                    continue
                event = self._decrypt(row["payload_enc"])
                claimed.append(
                    {
                        "reminder_id": row["reminder_id"],
                        "delivery_token": token,
                        "event_id": row["event_id"],
                        "lead_minutes": row["lead_minutes"],
                        "notify_at_ms": row["notify_at_ms"],
                        "title": event.get("title", "Calendar item"),
                        "start_local": event.get("start_local", ""),
                    }
                )
        return claimed

    def mark_reminder_delivered(
        self,
        reminder_id: str,
        *,
        delivery_token: str,
        person_id: str | None = None,
    ) -> None:
        person = self._person(person_id)
        timestamp = now_ms()
        with self.db.lock, self.db.connect() as connection:
            cursor = connection.execute(
                "UPDATE owner_calendar_reminders SET status='delivered',delivered_at_ms=?,"
                "lease_token='',lease_until_ms=0,updated_at=? WHERE reminder_id=? "
                "AND person_id=? AND status='dispatching' AND lease_token=?",
                (
                    timestamp, timestamp, _bounded(reminder_id, 128), person,
                    _bounded(delivery_token, 256),
                ),
            )
            if cursor.rowcount != 1:
                raise SarahCalendarError("That reminder was not pending delivery")

    def release_reminder_claim(
        self,
        reminder_id: str,
        *,
        delivery_token: str,
        person_id: str | None = None,
    ) -> None:
        person = self._person(person_id)
        with self.db.lock, self.db.connect() as connection:
            connection.execute(
                "UPDATE owner_calendar_reminders SET status='enabled',lease_token='',"
                "lease_until_ms=0,updated_at=? WHERE reminder_id=? AND person_id=? "
                "AND status='dispatching' AND lease_token=?",
                (
                    now_ms(), _bounded(reminder_id, 128), person,
                    _bounded(delivery_token, 256),
                ),
            )
