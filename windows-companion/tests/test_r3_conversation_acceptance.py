from __future__ import annotations

import json
from pathlib import Path

import pytest

from sarah_r3_acceptance import LIVE_CONFIRMATION, fixture_battery, run_acceptance


def test_fixture_battery_uses_production_database_and_model_routes(tmp_path: Path) -> None:
    attempt = run_acceptance(
        working_root=tmp_path / "runtime",
        evidence_root=tmp_path / "evidence",
    )
    payload = json.loads((attempt / "turns.json").read_text(encoding="utf-8"))
    assert payload["mode"] == "DETERMINISTIC_FIXTURE_NO_NETWORK"
    assert payload["production_paths_exercised"] == ["SarahDatabase", "ModelClient"]
    assert payload["turn_count"] == len(fixture_battery())
    assert payload["objective_pass"] is True
    assert all(turn["objective_pass"] for turn in payload["turns"])
    assert "llama3.1" not in json.dumps(payload).lower()


def test_online_offline_failure_online_route_contract(tmp_path: Path) -> None:
    attempt = run_acceptance(
        working_root=tmp_path / "runtime",
        evidence_root=tmp_path / "evidence",
    )
    payload = json.loads((attempt / "turns.json").read_text(encoding="utf-8"))
    by_id = {turn["turn_id"]: turn for turn in payload["turns"]}
    assert by_id["natural_conversation"]["route"] == "ONLINE_WORKERS_AI"
    assert by_id["flight_calm"]["route"] == "LOCAL_TOOL_RESULT"
    assert by_id["offline_conversation"]["route"] == "OFFLINE_LOCAL"
    assert by_id["connected_failure_fallback"]["route"] == "ONLINE_FAILED_FELL_BACK_OFFLINE"
    assert by_id["automatic_online_return"]["route"] == "ONLINE_WORKERS_AI"


def test_current_source_gate_and_ticket_receipt_are_separate(tmp_path: Path) -> None:
    attempt = run_acceptance(
        working_root=tmp_path / "runtime",
        evidence_root=tmp_path / "evidence",
    )
    payload = json.loads((attempt / "turns.json").read_text(encoding="utf-8"))
    by_id = {turn["turn_id"]: turn for turn in payload["turns"]}
    no_source = by_id["nearby_event_without_source"]
    assert no_source["route"] == "TOOL_UNAVAILABLE"
    assert not (no_source["receipt"].get("source_urls") or [])
    assert "will not invent" in no_source["final_spoken"].lower()
    ticket = by_id["official_event_ticket_link"]
    assert ticket["receipt"]["source_urls"] == ["https://events.example.test/official/tickets"]
    assert "not bought" in ticket["final_spoken"].lower()


def test_interest_and_trip_reach_later_connected_prompt(tmp_path: Path) -> None:
    attempt = run_acceptance(
        working_root=tmp_path / "runtime",
        evidence_root=tmp_path / "evidence",
    )
    payload = json.loads((attempt / "turns.json").read_text(encoding="utf-8"))
    continuity = next(turn for turn in payload["turns"] if turn["turn_id"] == "interest_trip_continuity")
    assert "Power Rangers" in continuity["captured_prompt"]
    assert "New Zealand" in continuity["captured_prompt"]
    assert "no dates or booking claimed" in continuity["captured_prompt"].lower()


def test_attempts_are_append_only_and_manifest_hashes_are_distinct(tmp_path: Path) -> None:
    first = run_acceptance(
        working_root=tmp_path / "runtime-one",
        evidence_root=tmp_path / "evidence",
        max_turns=1,
    )
    first_bytes = (first / "turns.json").read_bytes()
    second = run_acceptance(
        working_root=tmp_path / "runtime-two",
        evidence_root=tmp_path / "evidence",
        max_turns=1,
    )
    assert first.name == "attempt_01"
    assert second.name == "attempt_02"
    assert (first / "turns.json").read_bytes() == first_bytes
    manifest = json.loads((second / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["append_only"] is True
    assert len(manifest["files"]["turns.json"]["sha256"]) == 64


def test_live_mode_refuses_implicit_network_or_unconfigured_root(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="explicit owner authorization"):
        run_acceptance(
            working_root=tmp_path / "runtime",
            evidence_root=tmp_path / "evidence",
            live=True,
        )
    with pytest.raises(ValueError, match="explicitly selected"):
        run_acceptance(
            working_root=tmp_path / "runtime-two",
            evidence_root=tmp_path / "evidence-two",
            live=True,
            live_authorized=True,
        )
    assert LIVE_CONFIRMATION == "I_AUTHORIZE_BOUNDED_SARAH_LIVE_ACCEPTANCE"
