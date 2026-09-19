"""Executes the isolated D1 schema against SQLite without touching a database."""

from __future__ import annotations

import sqlite3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    database = sqlite3.connect(":memory:")
    database.executescript((ROOT / "migrations" / "0001_device_auth_v1.sql").read_text(encoding="utf-8"))
    tables = {
        row[0]
        for row in database.execute("SELECT name FROM sqlite_master WHERE type = 'table'")
    }
    expected = {"owners", "enrollments", "devices", "auth_challenges", "key_rotations", "audit_events"}
    assert expected <= tables

    database.execute(
        "INSERT INTO owners(owner_id, access_subject_hash, status, created_at) VALUES (?, ?, ?, ?)",
        ("owner_one", "owner_hash", "active", "2026-08-09T12:00:00.000Z"),
    )
    row = (
        "enroll_one", "device_hash", "user_hash", "challenge_hash", "{}", "thumb_one",
        "android", "Galaxy A17", "com.kiraworld.sarahtravel", "2.5", "pending_owner", None,
        "2026-08-09T12:00:00.000Z", "2026-08-09T12:10:00.000Z", None, None,
    )
    database.execute(
        "INSERT INTO enrollments VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", row,
    )
    try:
        database.execute(
            "INSERT INTO enrollments VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ("enroll_two", "device_hash", "other_user", "other_challenge", "{}", "thumb_two",
             "windows", "Laptop", "SarahMorgan.Windows", "2.5", "pending_owner", None,
             "2026-08-09T12:00:00.000Z", "2026-08-09T12:10:00.000Z", None, None),
        )
    except sqlite3.IntegrityError:
        pass
    else:
        raise AssertionError("duplicate device-code hash was accepted")

    database.close()
    print("PASS: isolated D1 migration executes and uniqueness fails closed")


if __name__ == "__main__":
    main()
