from __future__ import annotations

from pathlib import Path

import pytest

from sarah_calendar import (
    SarahCalendarError,
    SarahCalendarStore,
    build_email_event_proposal,
    parse_owner_local_time,
)
from sarah_core import SarahDatabase


def _db(tmp_path: Path) -> SarahDatabase:
    root = tmp_path / "state"
    root.mkdir(parents=True, exist_ok=True)
    database = SarahDatabase(root)
    database.ensure_profile("Robert", 40, age_known=True)
    return database


def _row(message_id: str = "m1") -> dict[str, str]:
    return {
        "message_id": message_id,
        "thread_id": "t1",
        "internal_date_ms": "1786200000000",
        "from": "tickets@example.test",
        "subject": "PopCon ticket for 2027-03-26 10:30",
        "date": "Sat, 8 Aug 2026 10:00:00 -0400",
        "snippet": "Indianapolis convention confirmation",
        "source": "gmail.readonly",
    }


def test_email_candidate_is_only_a_source_bound_pending_proposal() -> None:
    proposal = build_email_event_proposal(
        _row(), account_email="owner@example.com", fetched_at_ms=100
    )
    assert proposal["status"] == "pending_owner_decision"
    assert proposal["suggested_start_local"] == "2027-03-26 10:30"
    assert proposal["source"]["calendar_saved"] is False
    assert proposal["source"]["attendance_claimed"] is False
    assert proposal["source"]["message_modified"] is False
    assert "owner@example.com" not in str(proposal)
    assert len(proposal["source_hash"]) == 64


def test_transport_proposal_and_saved_event_preserve_separate_arrival(tmp_path: Path) -> None:
    row = _row()
    row["subject"] = (
        "Train departure 2027-03-26 10:30; arrival 2027-03-26 13:45"
    )
    store = SarahCalendarStore(_db(tmp_path))
    proposal = store.ingest_email_candidates(
        [row], account_email="owner@example.com"
    )[0]
    assert proposal["kind"] == "train"
    assert proposal["suggested_start_local"] == "2027-03-26 10:30"
    assert proposal["suggested_end_local"] == "2027-03-26 13:45"
    event = store.decide(
        proposal["proposal_id"],
        remember=True,
        owner_action="Remember this exact train email",
        start_local=proposal["suggested_start_local"],
        end_local=proposal["suggested_end_local"],
    )
    assert event is not None
    assert event["end_at_ms"] > event["start_at_ms"]
    saved = store.events()[0]
    assert saved["end_at_ms"] == event["end_at_ms"]
    assert saved["end_local"] == event["end_local"]

    offset_row = _row("offset")
    offset_row["subject"] = (
        "Flight departure 2027-03-26T10:30:00-04:00; "
        "arrival 2027-03-26T13:45:00-07:00"
    )
    offset = build_email_event_proposal(
        offset_row, account_email="owner@example.com"
    )
    assert offset["suggested_start_local"].endswith("-04:00")
    assert offset["suggested_end_local"].endswith("-07:00")


def test_arrival_before_departure_fails_without_accepting_proposal(tmp_path: Path) -> None:
    store = SarahCalendarStore(_db(tmp_path))
    proposal = store.ingest_email_candidates(
        [_row()], account_email="owner@example.com"
    )[0]
    with pytest.raises(SarahCalendarError, match="cannot precede"):
        store.decide(
            proposal["proposal_id"],
            remember=True,
            owner_action="Remember",
            start_local="2027-03-26 10:30",
            end_local="2027-03-26 09:30",
        )
    assert store.events() == []
    assert store.proposals(status="pending_owner_decision")


def test_email_date_header_is_not_mistaken_for_event_time() -> None:
    row = _row()
    row["subject"] = "Your ticket confirmation"
    row["snippet"] = "Thanks for your order"
    proposal = build_email_event_proposal(row, account_email="owner@example.com")
    assert proposal["suggested_start_local"] == ""


def test_owner_must_confirm_before_calendar_event_exists(tmp_path: Path) -> None:
    store = SarahCalendarStore(_db(tmp_path))
    created = store.ingest_email_candidates([_row()], account_email="owner@example.com")
    assert len(created) == 1
    assert store.events() == []
    pending = store.proposals(status="pending_owner_decision")
    event = store.decide(
        pending[0]["proposal_id"],
        remember=True,
        owner_action="Yes, remember this exact email item",
        title="PopCon Indy",
        start_local="2027-03-26 10:30",
        location="Indianapolis",
    )
    assert event is not None
    assert event["attendance_claimed"] is False
    assert event["travel_completed_claimed"] is False
    assert store.proposals(status="owner_accepted")[0]["proposal_id"] == pending[0]["proposal_id"]
    assert store.events()[0]["title"] == "PopCon Indy"


def test_duplicate_scan_does_not_duplicate_or_reset_owner_decision(tmp_path: Path) -> None:
    store = SarahCalendarStore(_db(tmp_path))
    first = store.ingest_email_candidates([_row()], account_email="owner@example.com")
    store.decide(
        first[0]["proposal_id"],
        remember=False,
        owner_action="No, do not remember this one",
    )
    assert store.ingest_email_candidates([_row()], account_email="owner@example.com") == []
    assert store.proposals(status="owner_rejected")[0]["proposal_id"] == first[0]["proposal_id"]


def test_reminder_is_opt_in_and_delivered_once(tmp_path: Path) -> None:
    store = SarahCalendarStore(_db(tmp_path))
    proposal = store.ingest_email_candidates([_row()], account_email="owner@example.com")[0]
    event = store.decide(
        proposal["proposal_id"],
        remember=True,
        owner_action="Remember it",
        title="Train",
        start_local="2027-03-26 10:30",
        kind="train",
    )
    assert store.due_reminders(at_ms=9_999_999_999_999) == []
    reminder = store.add_reminder(
        event["event_id"], lead_minutes=60, owner_action="Remind me one hour before"
    )
    due = store.claim_due_reminders(at_ms=reminder["notify_at_ms"])
    assert [item["reminder_id"] for item in due] == [reminder["reminder_id"]]
    assert store.claim_due_reminders(at_ms=reminder["notify_at_ms"]) == []
    store.mark_reminder_delivered(
        reminder["reminder_id"], delivery_token=due[0]["delivery_token"]
    )
    assert store.due_reminders(at_ms=reminder["notify_at_ms"] + 1) == []


def test_invalid_owner_time_and_cross_profile_access_fail_closed(tmp_path: Path) -> None:
    database = _db(tmp_path)
    store = SarahCalendarStore(database)
    proposal = store.ingest_email_candidates([_row()], account_email="owner@example.com")[0]
    with pytest.raises(SarahCalendarError, match="YYYY-MM-DD"):
        store.decide(
            proposal["proposal_id"],
            remember=True,
            owner_action="Remember",
            start_local="next Friday",
        )
    # The transaction rolled back, so the original proposal remains pending.
    assert store.proposals(status="pending_owner_decision")
    other = database.ensure_profile("Other Adult", 35, age_known=True)
    assert store.events(person_id=other) == []
    with pytest.raises(SarahCalendarError, match="unavailable"):
        store.decide(
            proposal["proposal_id"],
            remember=False,
            owner_action="No",
            person_id=other,
        )


def test_parse_owner_local_time_accepts_bounded_unambiguous_forms() -> None:
    first, iso = parse_owner_local_time("2027-03-26 10:30")
    second, _ = parse_owner_local_time("2027-03-26 10:30 AM")
    assert first == second
    assert iso.startswith("2027-03-26T10:30:00")


def test_batch_ingest_is_atomic_and_same_message_metadata_does_not_bypass_decision(
    tmp_path: Path,
) -> None:
    store = SarahCalendarStore(_db(tmp_path))
    with pytest.raises(SarahCalendarError, match="message ID"):
        store.ingest_email_candidates(
            [_row("stable"), {"subject": "invalid"}],
            account_email="owner@example.com",
        )
    assert store.proposals() == []

    original = store.ingest_email_candidates(
        [_row("stable")], account_email="owner@example.com"
    )[0]
    store.decide(
        original["proposal_id"], remember=False, owner_action="No to this exact message"
    )
    changed = _row("stable")
    changed["subject"] = "Changed sender wording"
    changed["from"] = "different@example.test"
    assert store.ingest_email_candidates(
        [changed], account_email="owner@example.com"
    ) == []


def test_disconnect_cleanup_removes_unsaved_but_preserves_saved_calendar_item(
    tmp_path: Path,
) -> None:
    store = SarahCalendarStore(_db(tmp_path))
    accepted, pending, rejected = store.ingest_email_candidates(
        [_row("accepted"), _row("pending"), _row("rejected")],
        account_email="owner@example.com",
    )
    store.decide(
        accepted["proposal_id"],
        remember=True,
        owner_action="Remember",
        start_local="2027-03-26 10:30",
    )
    store.decide(
        rejected["proposal_id"], remember=False, owner_action="Do not remember"
    )
    assert store.purge_unsaved_email_proposals() == 2
    assert len(store.events()) == 1
    assert len(store.proposals(status="owner_accepted")) == 1
    assert store.proposals(status="pending_owner_decision") == []
    assert store.proposals(status="owner_rejected") == []


def test_profile_rename_migrates_calendar_and_reminders(tmp_path: Path) -> None:
    database = _db(tmp_path)
    old_id = database.get_setting("active_person_id")
    store = SarahCalendarStore(database)
    proposal = store.ingest_email_candidates(
        [_row()], account_email="owner@example.com", person_id=old_id
    )[0]
    event = store.decide(
        proposal["proposal_id"],
        remember=True,
        owner_action="Remember",
        start_local="2027-03-26 10:30",
        person_id=old_id,
    )
    store.add_reminder(
        event["event_id"], lead_minutes=60, owner_action="Remind", person_id=old_id
    )
    new_id = database.rename_active_profile("Robert McMurrer")
    assert new_id != old_id
    assert len(store.events(person_id=new_id)) == 1
    assert store.events(person_id=old_id) == []


def test_explicit_offset_is_preserved_for_dst_sensitive_times() -> None:
    winter_ms, winter = parse_owner_local_time("2027-01-15T10:00:00-05:00")
    summer_ms, summer = parse_owner_local_time("2027-07-15T10:00:00-04:00")
    assert winter.endswith("-05:00")
    assert summer.endswith("-04:00")
    assert winter_ms != summer_ms
