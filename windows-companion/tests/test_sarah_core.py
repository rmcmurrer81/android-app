from pathlib import Path
import gc
import json
import os
import queue
import tempfile
import threading
import time
from types import SimpleNamespace

from PIL import Image
import pytest
import requests

from sarah_core import (
    ChannelResponse, ElevenLabsVoice, ModelClient, SarahDatabase, TavilyResearch, age_group_for,
    adaptive_context_from_message, corrected_name, current_search_query, discovery_queries,
    enforce_no_false_work_promise, is_stress_or_fear,
    load_bundled_event_config, load_runtime_config, normalize_age,
    needs_owner_identity_confirmation, needs_current_sources, runtime_setting,
    save_runtime_config, sync_decrypt, sync_encrypt,
    sync_signature, transport_context, universal_calm, offline_calm_followup,
)


def test_windows_natural_interest_and_destination_feed_research_and_offline_trivia():
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "natural-adaptive-context")
        database = SarahDatabase(root)
        owner_id = database.ensure_profile("Taylor", 35, "Newark", "", True)
        assert adaptive_context_from_message("I like Power Rangers")["interest"] == "Power Rangers"
        database.learn_adaptive_context("I like Power Rangers", owner_id)
        database.learn_adaptive_context("I am planning a trip to New Zealand", owner_id)

        profile = database.active_profile()
        trips = database.list_rows("trips", person_id=owner_id, limit=20)
        assert "power rangers" in profile["interests"].lower()
        assert trips[0]["destination"] == "New Zealand"
        assert "Power Rangers filming locations in New Zealand" in discovery_queries(
            profile, trips, nearby_enabled=False,
        )[0]
        trivia = offline_calm_followup("Play trivia", profile, trips)
        assert trivia is not None
        assert "saved trip context" in trivia.spoken

        guest_id = database.ensure_profile("Guest", 30, "Boston", "", True)
        assert guest_id != owner_id
        assert database.list_rows("trips", person_id=guest_id, limit=20) == []
        assert "power rangers" not in database.active_profile()["interests"].lower()


def test_windows_current_search_query_binds_area_and_prior_destination():
    profile = {"current_area": "Newark, New Jersey"}
    trips = [{"destination": "Brazil", "status": "planned"}]
    history = [{"role": "user", "content": "I am thinking about visiting Brazil"}]
    nearby = current_search_query(
        "Is there anything to do near my current location in the near future?",
        profile,
        trips,
        history,
    )
    followup = current_search_query(
        "What is the cheapest destination and any time of the year?",
        profile,
        trips,
        history,
    )
    assert "Newark, New Jersey" in nearby
    assert "Brazil" in nearby
    assert "Brazil" in followup


def make_sarah_home(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    (path / "photos").mkdir(exist_ok=True)
    (path / "voice_cache").mkdir(exist_ok=True)
    (path / "backups").mkdir(exist_ok=True)
    return path


def wait_for_windows_handles() -> None:
    gc.collect()
    if os.name == "nt":
        time.sleep(0.25)


def test_identity_and_calm():
    assert is_stress_or_fear("I am stressing")
    assert corrected_name("I am stressing") == ""
    assert corrected_name("No, I am Robert but I am stressed out") == "Robert"
    assert transport_context("This fast train is making me nervous") == "train"
    response = universal_calm("Robert", "adult", "train")
    assert "Robert" in response.spoken and "train" in response.spoken.lower()
    assert normalize_age("unknown") is None
    assert age_group_for(None) == "unknown"
    assert age_group_for("18") == "adult"
    unknown = universal_calm("Traveler", age_group_for("unknown"), "plane")
    assert "family-safe" in unknown.spoken


def test_offline_calm_followups_adapt_only_to_active_saved_context():
    profile = {"name": "Taylor", "interests": "Power Rangers", "person_id": "taylor"}
    trips = [{"destination": "New Zealand", "status": "planned"}]
    breathing = offline_calm_followup("Guide me through breathing", profile, trips)
    assert breathing is not None and "four counts" in breathing.spoken
    assert breathing.route == "LOCAL_TOOL_RESULT"
    trivia = offline_calm_followup("Play trivia", profile, trips)
    assert trivia is not None and "New Zealand" in trivia.spoken
    assert "saved trip context" in trivia.spoken
    assert "network request" in trivia.factual_truth
    other = offline_calm_followup(
        "Play trivia", {"name": "Guest", "interests": "history"}, [],
    )
    assert other is not None and "opposite east" in other.spoken
    assert "Power Rangers" not in other.spoken
    assert offline_calm_followup("Tell me about a movie", profile, trips) is None


def test_adaptive_discovery_queries_prioritize_destination_and_nearby_without_leakage():
    profile = {
        "name": "Taylor",
        "interests": "Power Rangers",
        "current_area": "Wellington",
    }
    queries = discovery_queries(
        profile, [{"destination": "New Zealand"}], nearby_enabled=True,
    )
    assert len(queries) == 2
    assert "Power Rangers" in queries[0] and "New Zealand" in queries[0]
    assert "Wellington" in queries[1]
    assert all("Robert" not in query and "Newark" not in query for query in queries)
    assert discovery_queries(
        {"name": "Fresh person", "interests": ""}, [], nearby_enabled=False,
    ) == []


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
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "one")
        monkeypatch.setenv("SARAH_HOME", str(root))
        database = SarahDatabase(root)
        database.ensure_profile("Robert", 45, "Newark", "Power Rangers", True)
        database.add_trip("New Zealand test", "New Zealand")
        database.add_message("user", "Plan my trip")
        database.add_mind_event(ChannelResponse("Okay", "curious", "No booking occurred", "TRUTHFUL_STATEMENT", True), "test")
        source = Path(temp) / "photo.png"
        Image.new("RGB", (20, 20), "blue").save(source)
        database.import_photo(source, "test photo")
        backup = Path(temp) / "backup.sarahmind"
        database.create_backup(backup, "correct horse battery")
        assert backup.exists()

        wrong_root = make_sarah_home(Path(temp) / "wrong")
        wrong_database = SarahDatabase(wrong_root)
        with pytest.raises(Exception):
            wrong_database.restore_backup(backup, "wrong password")

        restored_root = make_sarah_home(Path(temp) / "restored")
        restored_database = SarahDatabase(restored_root)
        restored_database.restore_backup(backup, "correct horse battery")
        assert restored_database.path.exists()

        del database, wrong_database, restored_database
        wait_for_windows_handles()


def test_sync_import_merges_rows(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        first_root = make_sarah_home(Path(temp) / "first")
        first_database = SarahDatabase(first_root)
        first_database.ensure_profile("Robert", 45, "Newark", "Power Rangers", True)
        first_database.add_trip("NZ", "New Zealand")
        first_database.add_message("user", "Hello", route="USER_INPUT")
        first_database.add_message("assistant", "Hi", route="ONLINE_WORKERS_AI")
        assert first_database.add_discovery(
            "Official event", "Source-bound summary", "https://example.test/event", "events near Newark"
        )
        payload = first_database.export_sync(False)

        second_root = make_sarah_home(Path(temp) / "second")
        second_database = SarahDatabase(second_root)
        counts = second_database.import_sync(payload, confirm_owner_change=True)
        rows = second_database.list_rows("trips")
        assert counts["messages"] >= 1
        assert counts["discoveries"] == 1
        assert rows[0]["destination"] == "New Zealand"
        synced_messages = second_database.recent_messages()
        assert synced_messages[-1]["route"] == "ONLINE_WORKERS_AI"
        assert second_database.list_rows("discoveries")[0]["url"] == "https://example.test/event"

        del first_database, second_database
        wait_for_windows_handles()


def test_unknown_synced_age_never_becomes_adult_and_current_area_is_not_hometown():
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "unknown-age")
        database = SarahDatabase(root)
        database.import_sync({
            "schema": "sarah-sync-v1",
            "device_id": "android-test",
            "profile": {"name": "Robert", "age": 18, "age_known": "no", "hometown": "Newark"},
        }, confirm_owner_change=True)
        assert database.active_profile()["age"] is None
        assert age_group_for(database.active_profile()["age"]) == "unknown"
        database.set_current_area("Jersey City", captured_at=int(time.time() * 1000))
        profile = database.active_profile()
        assert profile["hometown"] == "Newark"
        assert profile["current_area"] == "Jersey City"
        database.set_current_area("Old area", captured_at=1)
        assert database.current_area() == ""

        del database
        wait_for_windows_handles()


def test_sync_different_confirmed_owner_requires_explicit_confirmation():
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "sync-owner-confirmation")
        database = SarahDatabase(root)
        database.ensure_profile("Robert", 45, "Newark", "travel", True)
        payload = {
            "schema": "sarah-sync-v1",
            "device_id": "other-phone",
            "profile": {"name": "Taylor", "age": 32, "age_known": "yes"},
        }
        with pytest.raises(ValueError, match="identity confirmation required"):
            database.import_sync(payload)
        assert database.active_profile()["name"] == "Robert"
        staged = json.loads(database.get_setting("pending_sync_owner_candidate"))
        assert staged["current_name"] == "Robert"
        assert staged["incoming_name"] == "Taylor"
        database.import_sync(payload, confirm_owner_change=True)
        assert database.active_profile()["name"] == "Taylor"

        del database
        wait_for_windows_handles()


def test_existing_windows_database_migrates_message_route_column():
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "legacy")
        import sqlite3
        path = root / "sarah_windows.db"
        with sqlite3.connect(path) as legacy:
            legacy.executescript(
                "CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);"
                "CREATE TABLE messages(event_id TEXT PRIMARY KEY,person_id TEXT NOT NULL,role TEXT NOT NULL,content TEXT NOT NULL,device_id TEXT NOT NULL,created_at INTEGER NOT NULL);"
            )
        database = SarahDatabase(root)
        database.add_message("assistant", "Migrated", route="OFFLINE_LOCAL")
        assert database.recent_messages()[-1]["route"] == "OFFLINE_LOCAL"

        del database
        wait_for_windows_handles()


def test_false_background_work_promises_are_removed_without_erasing_other_reply():
    original = ChannelResponse(
        "You're welcome. I'll get to work on finding deals. I'll be back with a summary soon.",
        "Sarah wants to help.",
        "No job exists.",
        "TRUTHFUL_STATEMENT",
        True,
    )
    repaired = enforce_no_false_work_promise(original)
    assert repaired.spoken == "You're welcome."
    assert "background" in repaired.factual_truth.lower()
    assert repaired.classification == "HALLUCINATION_OR_GROUNDING_ERROR"


def test_runtime_config_is_local_atomic_and_environment_wins(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "runtime")
        path = save_runtime_config({
            "SARAH_MODEL_BACKEND_URL": "https://sarah.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "saved-token",
            "SARAH_MODEL_PROVIDER": "workers-ai",
            "SARAH_MODEL_ID": "@cf/google/gemma-4-26b-a4b-it",
            "NOT_ALLOWED": "must-not-be-saved",
        }, root)
        assert path.parent == root
        raw = path.read_text(encoding="utf-8")
        assert "NOT_ALLOWED" not in raw
        assert "saved-token" not in raw
        assert "dpapi-v1:" in raw or "local-aesgcm-v1:" in raw
        assert load_runtime_config(root)["SARAH_MODEL_BACKEND_TOKEN"] == "saved-token"
        assert runtime_setting("SARAH_MODEL_BACKEND_TOKEN", root=root) == "saved-token"
        monkeypatch.setenv("SARAH_MODEL_BACKEND_TOKEN", "environment-token")
        assert runtime_setting("SARAH_MODEL_BACKEND_TOKEN", root=root) == "environment-token"
        with pytest.raises(ValueError):
            save_runtime_config({"SARAH_MODEL_BACKEND_URL": "http://not-protected.test"}, root)


def test_legacy_plaintext_runtime_access_is_migrated_on_first_read():
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "legacy-runtime")
        path = root / "runtime-config.json"
        path.write_text(json.dumps({
            "SARAH_MODEL_BACKEND_URL": "https://sarah.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "legacy-plaintext-token",
        }), encoding="utf-8")

        assert load_runtime_config(root)["SARAH_MODEL_BACKEND_TOKEN"] == "legacy-plaintext-token"
        migrated = path.read_text(encoding="utf-8")
        assert "legacy-plaintext-token" not in migrated
        assert "dpapi-v1:" in migrated or "local-aesgcm-v1:" in migrated


def test_runtime_setting_precedence_excludes_bundled_credentials(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "runtime")
        bundle = Path(temp) / "sarah-event-config.json"
        bundle.write_text(json.dumps({
            "SARAH_MODEL_BACKEND_URL": "https://event.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "event-token",
            "NOT_ALLOWED": "ignored",
        }), encoding="utf-8")

        monkeypatch.delenv("SARAH_MODEL_BACKEND_TOKEN", raising=False)
        assert load_bundled_event_config(bundle) == {
            "SARAH_MODEL_BACKEND_URL": "https://event.example.test",
        }
        assert runtime_setting(
            "SARAH_MODEL_BACKEND_TOKEN", root=root, bundled_path=bundle,
        ) == ""

        save_runtime_config({"SARAH_MODEL_BACKEND_TOKEN": "user-token"}, root)
        assert runtime_setting(
            "SARAH_MODEL_BACKEND_TOKEN", root=root, bundled_path=bundle,
        ) == "user-token"

        monkeypatch.setenv("SARAH_MODEL_BACKEND_TOKEN", "environment-token")
        assert runtime_setting(
            "SARAH_MODEL_BACKEND_TOKEN", root=root, bundled_path=bundle,
        ) == "environment-token"


def test_windows_model_client_uses_shared_worker_contract(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "model-client")
        database = SarahDatabase(root)
        database.ensure_profile("Robert", 45, "Newark", "travel", True)
        database.add_message("user", "Hello Sarah")
        save_runtime_config({
            "SARAH_MODEL_BACKEND_URL": "https://sarah.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "local-test-token",
            "SARAH_MODEL_PROVIDER": "workers-ai",
            "SARAH_MODEL_ID": "@cf/google/gemma-4-26b-a4b-it",
        }, root)
        for name in (
            "SARAH_MODEL_BACKEND_URL",
            "SARAH_MODEL_BACKEND_TOKEN",
            "SARAH_MODEL_PROVIDER",
            "SARAH_MODEL_ID",
        ):
            monkeypatch.delenv(name, raising=False)

        captured = {}

        class FakeResponse:
            @staticmethod
            def raise_for_status():
                return None

            @staticmethod
            def json():
                return {
                    "reply": "<SPOKEN>Hi Robert.</SPOKEN>"
                    "<PRIVATE_MIND>Sarah is attentive.</PRIVATE_MIND>"
                    "<FACTUAL_TRUTH>No external action occurred.</FACTUAL_TRUTH>"
                    "<CLASSIFICATION>TRUTHFUL_STATEMENT</CLASSIFICATION>",
                    "provider": "workers-ai",
                    "model": "@cf/google/gemma-4-26b-a4b-it",
                    "online": True,
                    "web_search_requested": False,
                    "web_search_applied": False,
                    "source_urls": [],
                }

        def fake_post(url, **kwargs):
            captured["url"] = url
            captured.update(kwargs)
            return FakeResponse()

        monkeypatch.setattr("sarah_core.requests.post", fake_post)
        response = ModelClient(database).respond("Hello Sarah")
        assert response.spoken == "Hi Robert."
        assert response.route == "ONLINE_WORKERS_AI"
        assert "actual_provider=workers-ai" in response.factual_truth
        assert "actual_model=@cf/google/gemma-4-26b-a4b-it" in response.factual_truth
        assert "text_latency_ms=" in response.factual_truth
        assert captured["url"] == "https://sarah.example.test"
        assert captured["headers"]["Authorization"] == "Bearer local-test-token"
        payload = captured["json"]
        assert payload["provider"] == "workers-ai"
        assert payload["model"] == "@cf/google/gemma-4-26b-a4b-it"
        assert payload["message"] == "Hello Sarah"
        assert payload["system_prompt"]
        assert all(row["content"] != "Hello Sarah" for row in payload["history"])
        assert "system" not in payload
        assert "store" not in payload

        del database
        wait_for_windows_handles()


def test_placeholder_with_history_still_requires_owner_identity_confirmation(monkeypatch):
    import sarah_windows

    assert needs_owner_identity_confirmation({"name": "Phone owner"})
    assert needs_owner_identity_confirmation({"name": "Traveler"})
    assert not needs_owner_identity_confirmation({"name": "Robert"})

    calls = []

    class PlaceholderDatabase:
        @staticmethod
        def active_profile():
            return {"name": "Phone owner", "age": None, "age_known": "no"}

        @staticmethod
        def recent_messages(_limit):
            raise AssertionError("chat history must not suppress owner correction")

    app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)
    app.db = PlaceholderDatabase()
    app.root = object()
    monkeypatch.setattr(
        sarah_windows.simpledialog,
        "askstring",
        lambda *args, **kwargs: calls.append((args, kwargs)) or None,
    )
    app._maybe_onboard()
    assert calls, "placeholder owner prompt must appear even when old chat history exists"


def test_windows_model_turn_stays_bound_to_submitting_profile(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "profile-bound-model-turn")
        database = SarahDatabase(root)
        robert_id = database.ensure_profile("Robert", 45, "Newark", "Power Rangers", True)
        database.add_message("user", "Robert-only history", person_id=robert_id)
        guest_id = database.ensure_profile("Guest", 30, "Boston", "history", True)
        database.add_message("user", "Guest-only history", person_id=guest_id)
        save_runtime_config({
            "SARAH_MODEL_BACKEND_URL": "https://sarah.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "local-test-token",
            "SARAH_MODEL_PROVIDER": "workers-ai",
            "SARAH_MODEL_ID": "@cf/google/gemma-4-26b-a4b-it",
        }, root)
        captured = {}

        class FakeResponse:
            @staticmethod
            def raise_for_status():
                return None

            @staticmethod
            def json():
                return {
                    "reply": "<SPOKEN>Profile-bound reply.</SPOKEN>",
                    "provider": "workers-ai",
                    "model": "@cf/google/gemma-4-26b-a4b-it",
                    "online": True,
                    "web_search_applied": False,
                    "source_urls": [],
                }

        def fake_post(_url, **kwargs):
            captured.update(kwargs)
            return FakeResponse()

        monkeypatch.setattr("sarah_core.requests.post", fake_post)
        response = ModelClient(database).respond("Bound turn", person_id=robert_id)
        payload = captured["json"]
        assert response.spoken == "Profile-bound reply."
        assert '"name": "Robert"' in payload["system_prompt"]
        assert '"name": "Guest"' not in payload["system_prompt"]
        assert [row["content"] for row in payload["history"]] == ["Robert-only history"]
        assert database.get_setting("active_person_id") == guest_id

        del database
        wait_for_windows_handles()


def test_windows_answer_records_reply_to_origin_profile_after_active_profile_changes():
    import sarah_windows

    calls = []

    class BoundModel:
        @staticmethod
        def respond(text, turn_submitted_at=None, person_id=None):
            calls.append(("model", text, turn_submitted_at, person_id))
            return ChannelResponse("Bound reply", route="OFFLINE_LOCAL")

    class BoundDatabase:
        @staticmethod
        def add_message(role, content, person_id=None, route="UNKNOWN_LEGACY"):
            calls.append(("message", role, content, person_id, route))

        @staticmethod
        def add_mind_event(response, source, person_id=None):
            calls.append(("mind", response.spoken, source, person_id))

    app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)
    app.model = BoundModel()
    app.db = BoundDatabase()
    app.tasks = queue.Queue()
    app._answer("Question", 1234, "origin-person", "turn-7")
    kind, payload = app.tasks.get_nowait()
    assert kind == "reply"
    assert payload["person_id"] == "origin-person"
    assert payload["turn_id"] == "turn-7"
    assert ("model", "Question", 1234, "origin-person") in calls
    assert any(row[:2] == ("message", "assistant") and row[3] == "origin-person" for row in calls)
    assert any(row[0] == "mind" and row[3] == "origin-person" for row in calls)


def test_legacy_profiles_gain_durable_age_known_and_clear_placeholder_18():
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "legacy-age")
        import sqlite3
        path = root / "sarah_windows.db"
        traveler_id = "legacy-traveler"
        robert_id = "legacy-robert"
        with sqlite3.connect(path) as legacy:
            legacy.executescript(
                "CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);"
                "CREATE TABLE profiles(person_id TEXT PRIMARY KEY,name TEXT NOT NULL,age INTEGER,hometown TEXT NOT NULL DEFAULT '',interests TEXT NOT NULL DEFAULT '',memory_consent INTEGER NOT NULL DEFAULT 1,updated_at INTEGER NOT NULL);"
                "CREATE TABLE memories(memory_id TEXT PRIMARY KEY,person_id TEXT NOT NULL,category TEXT NOT NULL,summary TEXT NOT NULL,source TEXT NOT NULL,created_at INTEGER NOT NULL,UNIQUE(person_id,category,summary));"
            )
            legacy.execute("INSERT INTO settings VALUES('active_person_id',?)", (traveler_id,))
            legacy.execute("INSERT INTO profiles VALUES(?,?,?,?,?,?,?)", (traveler_id, "Phone owner", 18, "", "", 1, 1))
            legacy.execute("INSERT INTO profiles VALUES(?,?,?,?,?,?,?)", (robert_id, "Robert", 45, "Newark", "Power Rangers", 1, 1))
        database = SarahDatabase(root)
        assert database.active_profile()["age"] is None
        assert database.active_profile()["age_known"] == "no"
        with database.connect() as connection:
            robert = connection.execute("SELECT age,age_known FROM profiles WHERE person_id=?", (robert_id,)).fetchone()
        assert tuple(robert) == (45, 1)
        del database
        wait_for_windows_handles()


def test_windows_placeholder_merge_archives_collisions_without_losing_target_rows():
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "profile-collision-archive")
        database = SarahDatabase(root)
        placeholder_id = database.get_setting("active_person_id")
        database.add_trip("Shared trip", "Brazil", notes="placeholder notes", person_id=placeholder_id)
        database.add_memory("interest", "Power Rangers", person_id=placeholder_id)
        database.set_setting(f"current_area:{placeholder_id}", json.dumps({"area": "Old area", "captured_at": 1}))

        robert_id = database.ensure_profile("Robert", 45, "Newark", "Power Rangers", True)
        database.add_trip("Shared trip", "Brazil", notes="Robert notes", person_id=robert_id)
        database.add_memory("interest", "Power Rangers", person_id=robert_id)
        database.set_setting(f"current_area:{robert_id}", json.dumps({"area": "Newark", "captured_at": int(time.time() * 1000)}))
        database.set_setting("active_person_id", placeholder_id)

        assert database.rename_active_profile("Robert") == robert_id
        trips = database.list_rows("trips", person_id=robert_id)
        assert len(trips) == 1 and trips[0]["notes"] == "Robert notes"
        memories = database.list_rows("memories", person_id=robert_id)
        assert len([row for row in memories if row["summary"] == "Power Rangers"]) == 1
        archived = database.profile_migration_archive(placeholder_id)
        assert {row["record_type"] for row in archived} >= {"profile", "trips", "memories", "setting"}
        assert any(row["payload"].get("notes") == "placeholder notes" for row in archived)
        assert any(row["payload"].get("value", "").find("Old area") >= 0 for row in archived)

        del database
        wait_for_windows_handles()


def test_windows_profile_rename_merges_all_person_bound_data_and_location():
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "rename")
        database = SarahDatabase(root)
        robert_id = database.ensure_profile("Robert", 45, "Newark", "Power Rangers", True)
        old_id = database.ensure_profile("Phone owner", None, "", "travel", True, age_known=False)
        database.add_message("user", "Keep this conversation", route="USER_INPUT")
        database.add_memory("preference", "Likes aisle seats")
        database.add_trip("NZ", "New Zealand")
        database.add_mind_event(ChannelResponse("Okay", "private", "fact", "TRUTHFUL_STATEMENT", True), "test")
        assert database.add_discovery("Event", "Summary", "https://example.test/event", "query")
        source = Path(temp) / "photo.png"
        Image.new("RGB", (10, 10), "green").save(source)
        database.import_photo(source, "trip photo")
        database.set_current_area("Jersey City", captured_at=int(time.time() * 1000))
        database.set_nearby_enabled(True)
        with database.connect() as connection:
            connection.execute(
                "INSERT INTO wishes(wish_id,person_id,destination,notes,created_at) VALUES(?,?,?,?,?)",
                ("wish-old", old_id, "Brazil", "keep", int(time.time() * 1000)),
            )

        assert database.rename_active_profile("Robert") == robert_id
        profile = database.active_profile()
        assert profile["name"] == "Robert"
        assert profile["age"] == 45 and profile["age_known"] == "yes"
        assert profile["current_area"] == "Jersey City"
        assert database.nearby_enabled()
        for table in ("messages", "memories", "trips", "wishes", "mind_events", "discoveries", "photos"):
            with database.connect() as connection:
                assert connection.execute(f"SELECT count(*) FROM {table} WHERE person_id=?", (old_id,)).fetchone()[0] == 0
                assert connection.execute(f"SELECT count(*) FROM {table} WHERE person_id=?", (robert_id,)).fetchone()[0] >= 1, table
        with database.connect() as connection:
            assert connection.execute("SELECT count(*) FROM profiles WHERE person_id=?", (old_id,)).fetchone()[0] == 0

        del database
        wait_for_windows_handles()


def test_background_research_is_per_profile_opt_in_and_requires_memory_consent():
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "background-consent")
        database = SarahDatabase(root)
        database.ensure_profile("Robert", 45, "Newark", "travel", True)
        assert not database.background_research_enabled()
        database.set_background_research_enabled(True)
        assert database.background_research_enabled()

        database.ensure_profile("Guest", None, "", "", False, age_known=False)
        database.set_background_research_enabled(True)
        assert not database.background_research_enabled()

        database.ensure_profile("Robert", 45, "Newark", "travel", True)
        assert database.background_research_enabled()

        del database
        wait_for_windows_handles()


def test_windows_research_job_is_bounded_and_records_source_receipt():
    import sarah_windows

    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "research-receipt")
        database = SarahDatabase(root)
        database.ensure_profile("Robert", 45, "Newark", "Power Rangers", True)
        database.add_trip("New Zealand", "New Zealand")
        calls = []

        class FakeResearch:
            configured = True

            @staticmethod
            def search(query, limit):
                calls.append((query, limit))
                return [
                    {
                        "title": f"Result {len(calls)}-{index}",
                        "summary": "Source-backed fixture",
                        "url": f"https://example.test/{len(calls)}/{index}",
                    }
                    for index in range(limit)
                ]

        app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)
        app.db = database
        app.research = FakeResearch()
        app.tasks = queue.Queue()
        app.research_lock = threading.Lock()
        app.research_in_flight = True
        profile = database.active_profile()
        app._research_worker("test_fixture", profile["person_id"], profile)

        assert 1 <= len(calls) <= 2
        assert all(limit == 4 for _query, limit in calls)
        receipt = json.loads(database.get_setting(f"research_receipt:{database.get_setting('active_person_id')}"))
        assert receipt["trigger"] == "test_fixture"
        assert receipt["provider"] == "Tavily"
        assert receipt["status"] == "SUCCEEDED"
        assert receipt["profile_id"] == database.get_setting("active_person_id")
        assert receipt["query_count"] == len(calls)
        assert receipt["source_result_count"] == 4 * len(calls)
        assert receipt["started_at"] > 0
        assert receipt["completed_at"] >= receipt["started_at"]
        assert app.research_in_flight is False
        assert app.tasks.get_nowait()[0] == "research"

        del database
        wait_for_windows_handles()


def test_windows_failed_research_job_persists_truthful_profile_receipt():
    import sarah_windows

    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "research-failure-receipt")
        database = SarahDatabase(root)
        person_id = database.ensure_profile(
            "Taylor", 31, "Boston", "museums", True,
        )
        database.add_trip("Paris", "Paris", person_id=person_id)

        class FailedResearch:
            configured = True

            @staticmethod
            def search(_query, _limit):
                raise requests.ConnectionError("fixture unavailable")

        app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)
        app.db = database
        app.research = FailedResearch()
        app.tasks = queue.Queue()
        app.research_lock = threading.Lock()
        app.research_in_flight = True
        profile = database.active_profile()
        app._research_worker("failure_fixture", person_id, profile)

        receipt = json.loads(database.get_setting(f"research_receipt:{person_id}"))
        assert receipt["status"] == "FAILED"
        assert receipt["provider"] == "Tavily"
        assert receipt["profile_id"] == person_id
        assert receipt["failure_class"] == "ConnectionError"
        assert receipt["source_result_count"] == 0
        assert receipt["saved_count"] == 0
        assert database.list_rows("discoveries", person_id=person_id) == []
        assert "did not complete" in app.tasks.get_nowait()[1]

        del database
        wait_for_windows_handles()


def test_windows_research_cancels_before_network_save_after_profile_switch():
    import sarah_windows

    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "research-profile-race")
        database = SarahDatabase(root)
        robert_id = database.ensure_profile("Robert", 45, "Newark", "Power Rangers", True)
        database.add_trip("New Zealand", "New Zealand", person_id=robert_id)
        robert_profile = database.active_profile()
        guest_id = database.ensure_profile("Guest", 30, "Boston", "museums", True)
        assert database.get_setting("active_person_id") == guest_id

        class FakeResearch:
            configured = True

            @staticmethod
            def search(_query, _limit):
                return [{
                    "title": "Robert's exact result",
                    "summary": "Profile-bound fixture",
                    "url": "https://example.test/robert-only",
                }]

        app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)
        app.db = database
        app.research = FakeResearch()
        app.tasks = queue.Queue()
        app.research_lock = threading.Lock()
        app.research_in_flight = True
        app._research_worker("profile_switch_fixture", robert_id, robert_profile)

        assert database.list_rows("discoveries", person_id=robert_id) == []
        assert database.list_rows("discoveries", person_id=guest_id) == []
        receipt = json.loads(database.get_setting(f"research_receipt:{robert_id}"))
        assert receipt["trigger"] == "profile_switch_fixture"
        assert receipt["status"] == "CANCELLED"
        assert receipt["saved_count"] == 0
        assert database.get_setting(f"research_receipt:{guest_id}") == ""

        del database
        wait_for_windows_handles()


def test_windows_research_generation_cancellation_preempts_stale_save():
    import sarah_windows

    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "research-owner-turn-preemption")
        database = SarahDatabase(root)
        person_id = database.ensure_profile("Robert", 45, "Newark", "travel", True)
        database.add_trip("New Zealand", "New Zealand", person_id=person_id)
        calls = []

        class FakeResearch:
            configured = True

            @staticmethod
            def search(_query, _limit):
                calls.append("network")
                return [{"title": "stale", "summary": "stale", "url": "https://example.test/stale"}]

        app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)
        app.db = database
        app.research = FakeResearch()
        app.tasks = queue.Queue()
        app.research_lock = threading.Lock()
        app.research_in_flight = True
        app.research_generation = 2
        app._research_worker("profile_opted_in_idle_background", person_id, database.active_profile(), 1)

        assert calls == []
        assert database.list_rows("discoveries", person_id=person_id) == []
        receipt = json.loads(database.get_setting(f"research_receipt:{person_id}"))
        assert receipt["status"] == "CANCELLED"
        assert receipt["saved_count"] == 0

        del database
        wait_for_windows_handles()


def test_windows_backend_failure_then_ollama_success_records_attempted_and_actual(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "fallback-route")
        database = SarahDatabase(root)
        database.ensure_profile("Robert", 45, "Newark", "travel", True)
        save_runtime_config({
            "SARAH_MODEL_BACKEND_URL": "https://sarah.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "test-token",
        }, root)
        monkeypatch.setenv("SARAH_OLLAMA_URL", "http://127.0.0.1:11434")
        calls = []

        class OllamaReply:
            @staticmethod
            def raise_for_status():
                return None

            @staticmethod
            def json():
                return {"message": {"content": "<SPOKEN>Offline answer.</SPOKEN><FACTUAL_TRUTH>Saved knowledge only.</FACTUAL_TRUTH>"}}

        def fake_post(url, **kwargs):
            calls.append(url)
            if url.startswith("https://"):
                raise requests.ConnectionError("protected backend unavailable")
            return OllamaReply()

        monkeypatch.setattr("sarah_core.requests.post", fake_post)
        response = ModelClient(database).respond("Continue")
        assert calls.count("https://sarah.example.test") == 2
        assert calls[-1].endswith("/api/chat")
        assert response.route == "ONLINE_FAILED_FELL_BACK_OFFLINE"
        assert "Attempted route: ONLINE_WORKERS_AI" in response.factual_truth
        assert "Actual route: local Ollama" in response.factual_truth

        del database
        wait_for_windows_handles()


def test_windows_voice_can_use_local_unbundled_configuration(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "voice-config")
        monkeypatch.delenv("SARAH_ELEVENLABS_API_KEY", raising=False)
        monkeypatch.delenv("SARAH_ELEVENLABS_VOICE_ID", raising=False)
        save_runtime_config({
            "SARAH_ELEVENLABS_API_KEY": "test-key-never-bundled",
            "SARAH_ELEVENLABS_VOICE_ID": "approved-sarah-voice",
        }, root)
        voice = ElevenLabsVoice(root)
        assert voice.configured
        assert voice.voice_id == "approved-sarah-voice"


def test_windows_protected_voice_requires_revocable_backend_token(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "voice-missing-protected-token")
        for name in (
            "SARAH_MODEL_BACKEND_URL",
            "SARAH_MODEL_BACKEND_TOKEN",
            "SARAH_ELEVENLABS_BACKEND_URL",
            "SARAH_ELEVENLABS_BACKEND_TOKEN",
            "SARAH_ELEVENLABS_API_KEY",
        ):
            monkeypatch.delenv(name, raising=False)
        save_runtime_config({
            "SARAH_MODEL_BACKEND_URL": "https://sarah.example.test",
        }, root)
        voice = ElevenLabsVoice(root)
        assert voice.backend_url == "https://sarah.example.test/voice"
        assert voice.backend_token == ""
        assert not voice.configured


def test_windows_direct_voice_does_not_select_uncredentialed_backend(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "voice-direct-over-uncredentialed-backend")
        for name in (
            "SARAH_MODEL_BACKEND_URL",
            "SARAH_MODEL_BACKEND_TOKEN",
            "SARAH_ELEVENLABS_BACKEND_URL",
            "SARAH_ELEVENLABS_BACKEND_TOKEN",
            "SARAH_ELEVENLABS_API_KEY",
        ):
            monkeypatch.delenv(name, raising=False)
        save_runtime_config({
            "SARAH_MODEL_BACKEND_URL": "https://uncredentialed.example.test",
            "SARAH_ELEVENLABS_API_KEY": "direct-test-key",
            "SARAH_ELEVENLABS_VOICE_ID": "approved-sarah-voice",
        }, root)
        captured = {}

        class FakeAudioResponse:
            content = b"ID3" + b"direct-audio" * 20
            headers = {"Content-Type": "audio/mpeg"}

            @staticmethod
            def raise_for_status():
                return None

        def fake_post(url, **kwargs):
            captured["url"] = url
            captured.update(kwargs)
            return FakeAudioResponse()

        monkeypatch.setattr("sarah_core.requests.post", fake_post)
        voice = ElevenLabsVoice(root)
        assert voice.configured
        voice.synthesize("Use the credentialed direct route")
        assert captured["url"].startswith("https://api.elevenlabs.io/")
        assert captured["headers"]["xi-api-key"] == "direct-test-key"
        assert "Authorization" not in captured["headers"]


def test_windows_direct_voice_keeps_voice_id_in_provider_url_only(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "direct-voice-contract")
        for name in (
            "SARAH_MODEL_BACKEND_URL",
            "SARAH_ELEVENLABS_BACKEND_URL",
            "SARAH_ELEVENLABS_API_KEY",
            "SARAH_ELEVENLABS_VOICE_ID",
        ):
            monkeypatch.delenv(name, raising=False)
        save_runtime_config({
            "SARAH_ELEVENLABS_API_KEY": "test-direct-key",
            "SARAH_ELEVENLABS_VOICE_ID": "approved-sarah-voice",
        }, root)
        captured = {}

        class FakeAudioResponse:
            content = b"ID3" + b"test-audio" * 20
            headers = {
                "Content-Type": "audio/mpeg",
                "X-Sarah-Voice-Route": "elevenlabs-protected",
            }

            @staticmethod
            def raise_for_status():
                return None

        def fake_post(url, **kwargs):
            captured["url"] = url
            captured.update(kwargs)
            return FakeAudioResponse()

        monkeypatch.setattr("sarah_core.requests.post", fake_post)
        output = ElevenLabsVoice(root).synthesize("Direct voice contract")
        assert output.is_file()
        assert "/approved-sarah-voice/stream" in captured["url"]
        assert captured["headers"]["xi-api-key"] == "test-direct-key"
        assert "voice_id" not in captured["json"]
        assert captured["timeout"] == (3, 5)
        assert captured["stream"] is True


def test_windows_streamed_voice_cancellation_closes_response_without_cache(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "voice-stream-cancel")
        save_runtime_config({
            "SARAH_MODEL_BACKEND_URL": "https://sarah.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "shared-event-token",
        }, root)
        state = {"checks": 0, "closed": False}

        class StreamingAudioResponse:
            headers = {
                "Content-Type": "audio/mpeg",
                "X-Sarah-Voice-Route": "elevenlabs-protected",
            }

            @staticmethod
            def raise_for_status():
                return None

            @staticmethod
            def iter_content(chunk_size):
                assert chunk_size == 32 * 1024
                yield b"ID3" + b"first-audio" * 20
                yield b"second-audio" * 20

            @staticmethod
            def close():
                state["closed"] = True

        def should_cancel():
            state["checks"] += 1
            return state["checks"] >= 3

        monkeypatch.setattr(
            "sarah_core.requests.post",
            lambda *_args, **_kwargs: StreamingAudioResponse(),
        )
        with pytest.raises(RuntimeError, match="voice_synthesis_cancelled"):
            ElevenLabsVoice(root).synthesize(
                "Cancel this streamed voice",
                should_cancel=should_cancel,
                total_budget_seconds=15.0,
            )
        assert state["closed"] is True
        assert not list((root / "voice_cache").glob("*.mp3"))


def test_windows_online_failure_retries_once_per_turn_then_falls_back(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "fallback")
        database = SarahDatabase(root)
        database.ensure_profile("Robert", 45, "Newark", "travel", True)
        save_runtime_config({
            "SARAH_MODEL_BACKEND_URL": "https://sarah.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "local-test-token",
        }, root)
        for name in ("SARAH_MODEL_BACKEND_URL", "SARAH_MODEL_BACKEND_TOKEN", "SARAH_OLLAMA_URL"):
            monkeypatch.delenv(name, raising=False)

        calls = {"count": 0}

        def unavailable(*_args, **_kwargs):
            calls["count"] += 1
            raise requests.ConnectionError("test-only unavailable")

        monkeypatch.setattr("sarah_core.requests.post", unavailable)
        client = ModelClient(database)
        first = client.respond("Tell me about New York")
        second = client.respond("Are you still there?")
        assert calls["count"] == 4
        assert "online mind did not answer" in first.spoken.lower()
        assert "online mind did not answer" in second.spoken.lower()
        assert first.classification == "RUNTIME_STATE_ERROR"
        assert first.route == "ONLINE_FAILED_FELL_BACK_OFFLINE"

        del database
        wait_for_windows_handles()


def test_windows_connected_retry_succeeds_on_second_short_attempt(monkeypatch):
    calls = []

    class FakeResponse:
        @staticmethod
        def raise_for_status():
            return None

        @staticmethod
        def json():
            return {
                "reply": "<SPOKEN>Connected.</SPOKEN><FACTUAL_TRUTH>Fixture.</FACTUAL_TRUTH>",
                "provider": "workers-ai",
                "model": "fixture-model",
                "online": True,
            }

    def fake_post(_url, **kwargs):
        calls.append(kwargs["timeout"])
        if len(calls) == 1:
            raise requests.ConnectionError("first short attempt failed")
        return FakeResponse()

    monkeypatch.setattr("sarah_core.requests.post", fake_post)
    data, started_at = ModelClient._post_connected_with_retry(
        "https://sarah.example.test",
        "test-token",
        {"message": "Hello"},
    )

    assert data["provider"] == "workers-ai"
    assert started_at > 0
    assert len(calls) == 2
    assert all(connect <= 2.0 and read <= 5.5 for connect, read in calls)


def test_windows_online_forced_offline_restored_online_transcript_and_actual_receipts(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "route-sequence")
        database = SarahDatabase(root)
        database.ensure_profile("Robert", 45, "Newark", "travel", True)
        save_runtime_config({
            "SARAH_MODEL_BACKEND_URL": "https://sarah.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "test-token",
            "SARAH_MODEL_PROVIDER": "workers-ai",
            "SARAH_MODEL_ID": "requested-model",
        }, root)
        for name in (
            "SARAH_MODEL_BACKEND_URL", "SARAH_MODEL_BACKEND_TOKEN",
            "SARAH_MODEL_PROVIDER", "SARAH_MODEL_ID", "SARAH_OLLAMA_URL",
        ):
            monkeypatch.delenv(name, raising=False)

        sequence = [
            {
                "reply": "<SPOKEN>Online first.</SPOKEN><FACTUAL_TRUTH>First connected turn.</FACTUAL_TRUTH>",
                "provider": "workers-ai", "model": "workers-model", "online": True,
                "web_search_requested": False, "web_search_applied": False, "source_urls": [],
            },
            requests.ConnectionError("forced offline attempt one"),
            requests.ConnectionError("forced offline attempt two"),
            {
                "reply": "<SPOKEN>Online again.</SPOKEN><FACTUAL_TRUTH>Restored connected turn.</FACTUAL_TRUTH>",
                "provider": "openai", "model": "server-selected-model", "online": True,
                "web_search_requested": False, "web_search_applied": False, "source_urls": [],
            },
        ]

        class FakeResponse:
            def __init__(self, payload):
                self.payload = payload

            @staticmethod
            def raise_for_status():
                return None

            def json(self):
                return self.payload

        def fake_post(_url, **_kwargs):
            item = sequence.pop(0)
            if isinstance(item, Exception):
                raise item
            return FakeResponse(item)

        monkeypatch.setattr("sarah_core.requests.post", fake_post)
        client = ModelClient(database)
        first = client.respond("Hello Sarah")
        forced_offline = client.respond("Keep talking while the online route is unavailable")
        restored = client.respond("The online route is back")

        assert first.route == "ONLINE_WORKERS_AI"
        assert "actual_provider=workers-ai" in first.factual_truth
        assert forced_offline.route == "ONLINE_FAILED_FELL_BACK_OFFLINE"
        assert "actual_provider=on-device" in forced_offline.factual_truth
        assert restored.route == "ONLINE_OPENAI"
        assert "actual_provider=openai" in restored.factual_truth
        assert "actual_model=server-selected-model" in restored.factual_truth
        assert sequence == []

        del database
        wait_for_windows_handles()


def test_windows_current_info_raw_prose_is_withheld_without_web_receipt(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "current-source-gate")
        database = SarahDatabase(root)
        database.ensure_profile("Robert", 45, "Newark", "travel", True)
        save_runtime_config({
            "SARAH_MODEL_BACKEND_URL": "https://sarah.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "test-owner-access-code",
            "SARAH_MODEL_PROVIDER": "workers-ai",
            "SARAH_MODEL_ID": "workers-model",
        }, root)
        for name in ("SARAH_MODEL_BACKEND_URL", "SARAH_MODEL_BACKEND_TOKEN", "SARAH_MODEL_PROVIDER", "SARAH_MODEL_ID"):
            monkeypatch.delenv(name, raising=False)

        captured = {}

        class UnsourcedResponse:
            @staticmethod
            def raise_for_status():
                return None

            @staticmethod
            def json():
                return {
                    "reply": "<SPOKEN>The cheapest live fare is definitely $1.</SPOKEN>",
                    "provider": "workers-ai", "model": "workers-model", "online": True,
                    "web_search_requested": True, "web_search_applied": False, "source_urls": [],
                }

        def fake_post(_url, **kwargs):
            captured.update(kwargs)
            return UnsourcedResponse()

        monkeypatch.setattr("sarah_core.requests.post", fake_post)
        response = ModelClient(database).respond("What is the cheapest destination this week?")
        assert needs_current_sources("What is the cheapest destination this week?")
        assert captured["json"]["web_search"] is True
        assert response.route == "TOOL_UNAVAILABLE"
        assert "$1" not in response.spoken
        assert "web_search_applied=false" in response.factual_truth

        del database
        wait_for_windows_handles()


def test_windows_voice_reuses_protected_model_backend_without_provider_key(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "protected-voice")
        for name in (
            "SARAH_MODEL_BACKEND_URL",
            "SARAH_MODEL_BACKEND_TOKEN",
            "SARAH_ELEVENLABS_API_KEY",
            "SARAH_ELEVENLABS_BACKEND_URL",
            "SARAH_ELEVENLABS_BACKEND_TOKEN",
        ):
            monkeypatch.delenv(name, raising=False)
        save_runtime_config({
            "SARAH_MODEL_BACKEND_URL": "https://sarah.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "shared-event-token",
        }, root)
        voice = ElevenLabsVoice(root)
        assert voice.configured
        assert voice.api_key == ""
        assert voice.backend_url == "https://sarah.example.test/voice"

        captured = {}

        class FakeAudioResponse:
            content = b"ID3" + b"test-audio" * 20
            headers = {
                "Content-Type": "audio/mpeg",
                "X-Sarah-Voice-Route": "elevenlabs-protected",
            }

            @staticmethod
            def raise_for_status():
                return None

        def fake_post(url, **kwargs):
            captured["url"] = url
            captured.update(kwargs)
            return FakeAudioResponse()

        monkeypatch.setattr("sarah_core.requests.post", fake_post)
        output = voice.synthesize("Sarah voice test")
        assert output.is_file()
        assert captured["url"] == "https://sarah.example.test/voice"
        assert captured["headers"]["Authorization"] == "Bearer shared-event-token"
        assert "xi-api-key" not in captured["headers"]
        assert captured["json"]["voice_id"] == "WcGvc9xxaOYbKswm3NBx"


def test_windows_voice_cache_identity_includes_model_settings_and_route(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "voice-cache-identity")
        save_runtime_config({
            "SARAH_MODEL_BACKEND_URL": "https://sarah.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "shared-event-token",
        }, root)
        calls = []

        class FakeAudioResponse:
            content = b"ID3" + b"cache-audio" * 20
            headers = {
                "Content-Type": "audio/mpeg; charset=binary",
                "X-Sarah-Voice-Route": "elevenlabs-protected",
            }

            @staticmethod
            def raise_for_status():
                return None

        monkeypatch.setattr(
            "sarah_core.requests.post",
            lambda url, **kwargs: calls.append((url, kwargs)) or FakeAudioResponse(),
        )
        first = ElevenLabsVoice(root)
        first_path = first.synthesize("Cache identity test")
        assert first.last_cache_hit is False
        assert first.last_content_type == "audio/mpeg"
        repeated = ElevenLabsVoice(root)
        assert repeated.synthesize("Cache identity test") == first_path
        assert repeated.last_cache_hit is True
        changed = ElevenLabsVoice(root)
        changed.model = "eleven_multilingual_v2"
        changed_path = changed.synthesize("Cache identity test")
        assert changed_path != first_path
        assert len(calls) == 2
        assert first.cache_status()["size_bytes"] >= 256


def test_windows_voice_rejects_non_audio_or_tiny_response(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "voice-validation")
        save_runtime_config({
            "SARAH_MODEL_BACKEND_URL": "https://sarah.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "shared-event-token",
        }, root)

        class FakeBadResponse:
            content = b"not audio"
            headers = {
                "Content-Type": "application/json",
                "X-Sarah-Voice-Route": "elevenlabs-protected",
            }

            @staticmethod
            def raise_for_status():
                return None

        monkeypatch.setattr("sarah_core.requests.post", lambda *_args, **_kwargs: FakeBadResponse())
        with pytest.raises(RuntimeError, match="non-audio"):
            ElevenLabsVoice(root).synthesize("Reject invalid audio")
        assert not list((root / "voice_cache").glob("*.mp3"))


def test_windows_protected_voice_requires_exact_route_receipt(monkeypatch):
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "voice-route-receipt")
        save_runtime_config({
            "SARAH_MODEL_BACKEND_URL": "https://sarah.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "shared-event-token",
        }, root)

        class FakeUnattestedAudio:
            content = b"ID3" + b"unattested-audio" * 20
            headers = {"Content-Type": "audio/mpeg"}

            @staticmethod
            def raise_for_status():
                return None

        monkeypatch.setattr("sarah_core.requests.post", lambda *_args, **_kwargs: FakeUnattestedAudio())
        with pytest.raises(RuntimeError, match="approved route receipt"):
            ElevenLabsVoice(root).synthesize("Require route receipt")
        assert not list((root / "voice_cache").glob("*.mp3"))


def test_windows_voice_cache_cleanup_is_explicit_and_exact():
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temp:
        root = make_sarah_home(Path(temp) / "voice-owner-cleanup")
        cache = root / "voice_cache"
        (cache / "one.mp3").write_bytes(b"1" * 150)
        (cache / "two.mp3").write_bytes(b"2" * 200)
        protected = cache / "approved-voice-reference.wav"
        protected.write_bytes(b"keep")
        voice = ElevenLabsVoice(root)
        assert voice.cache_status()["size_bytes"] == 350
        result = voice.clear_cache_by_owner_request()
        assert result == {"removed_files": 2, "removed_bytes": 350}
        assert protected.read_bytes() == b"keep"


def test_windows_voice_failure_uses_system_speech_fallback(monkeypatch):
    import sarah_windows

    calls = []
    settings = {}

    class ReceiptDatabase:
        root = Path(".")

        @staticmethod
        def get_setting(_key):
            return "test-person"

        @staticmethod
        def set_setting(key, value):
            settings[key] = value

    class FailingVoice:
        configured = True

        @staticmethod
        def synthesize(_text, **_kwargs):
            raise requests.ConnectionError("test-only voice failure")

    app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)
    app.db = ReceiptDatabase()
    app.tasks = queue.Queue()
    app.voice = FailingVoice()
    app.speaking = False
    monkeypatch.setattr(sarah_windows, "playsound", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(sarah_windows.sys, "platform", "win32")
    app._run_cancellable_voice_process = (
        lambda command, generation: calls.append((command, generation)) or (True, "")
    )
    app._speak_worker("Sarah fallback voice test", person_id="test-person", turn_id="turn-voice-1")
    assert calls
    assert "System.Speech" in calls[0][0][-1]
    assert app.speaking is False
    receipt = json.loads(settings["voice_route_receipt:test-person"])
    assert receipt["attempted_route"] == "ELEVENLABS"
    assert receipt["actual_route"] == "WINDOWS_SYSTEM_SPEECH"
    assert receipt["synthesis_start"] > 0
    assert receipt["synthesis_end"] >= receipt["synthesis_start"]
    assert receipt["playback_end"] >= receipt["playback_start"] > 0
    assert receipt["total_voice_latency_ms"] >= 0
    assert receipt["failure_reason"] == "ConnectionError"
    assert receipt["person_id"] == "test-person"
    assert receipt["turn_id"] == "turn-voice-1"
    assert "explicit Windows offline voice fallback completed" in receipt["outcome"]
    assert app.tasks.get_nowait()[0] == "voice_route"


def test_windows_new_owner_turn_terminates_only_exact_active_voice_process():
    import sarah_windows

    class ActiveProcess:
        def __init__(self):
            self.terminated = 0
            self.killed = 0
            self.running = True

        def poll(self):
            return None if self.running else 0

        def terminate(self):
            self.terminated += 1
            self.running = False

        def wait(self, timeout=None):
            return 0

        def kill(self):
            self.killed += 1
            self.running = False

    app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)
    app.voice_generation = 7
    app._voice_control_lock = threading.Lock()
    app._voice_cancel_reasons = {}
    process = ActiveProcess()
    app._active_voice_process = process
    app._active_voice_generation = 7

    assert app._begin_voice_generation("superseded_by_new_owner_turn") == 8
    assert process.terminated == 1
    assert process.killed == 0
    assert app._voice_cancel_reason(7) == "superseded_by_new_owner_turn"


def test_windows_stop_voice_supersedes_queued_generation_without_touching_text():
    import sarah_windows

    statuses = []
    app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)
    app.voice_generation = 2
    app._voice_control_lock = threading.Lock()
    app._voice_cancel_reasons = {}
    app._active_voice_process = None
    app._active_voice_generation = None
    app.status = SimpleNamespace(set=statuses.append)

    app.stop_voice()

    assert app.voice_generation == 3
    assert app._voice_generation_is_current(3)
    assert not app._voice_generation_is_current(2)
    assert app._voice_cancel_reason(2) == "stopped_by_owner"
    assert statuses == ["Voice stopped. Text chat remains ready."]


def test_windows_mp3_player_uses_exact_cancellable_child_command(monkeypatch):
    import sarah_windows

    captured = []
    app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)
    app.voice_generation = 4
    app._voice_control_lock = threading.Lock()
    app._voice_cancel_reasons = {}
    app._active_voice_process = None
    app._active_voice_generation = None
    app._run_cancellable_voice_process = (
        lambda command, generation: captured.append((command, generation)) or (True, "")
    )
    monkeypatch.setattr(sarah_windows.sys, "platform", "win32")

    ok, reason = app._play_audio_file(Path("Sarah reply.mp3"), 4)

    assert ok and reason == ""
    command, generation = captured[0]
    assert generation == 4
    assert command[:3] == ["powershell", "-NoProfile", "-NonInteractive"]
    assert "mciSendString" in command[-1]
    assert "play '+$a+' wait" in command[-1]


def test_windows_cancelled_elevenlabs_playback_never_starts_fallback(monkeypatch):
    import sarah_windows

    settings = {}

    class ReceiptDatabase:
        root = Path(".")

        @staticmethod
        def get_setting(_key):
            return "test-person"

        @staticmethod
        def set_setting(key, value):
            settings[key] = value

    class CompletedVoice:
        configured = True
        last_cache_hit = False
        last_cache_key = "cancelled-cache"
        model = "eleven_flash_v2_5"
        voice_id = "approved-voice"
        last_route_identity = "elevenlabs-protected"
        last_content_type = "audio/mpeg"
        last_route_receipt = "elevenlabs-protected"

        @staticmethod
        def synthesize(_text, **_kwargs):
            return Path("cancelled.mp3")

    app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)
    app.db = ReceiptDatabase()
    app.tasks = queue.Queue()
    app.voice = CompletedVoice()
    app.speaking = False
    app.voice_generation = 9
    app._voice_control_lock = threading.Lock()
    app._voice_cancel_reasons = {9: "stopped_by_owner"}
    app._active_voice_process = None
    app._active_voice_generation = None
    app._play_audio_file = lambda _path, _generation: (False, "stopped_by_owner")
    app._speak_windows_fallback = lambda *_args: pytest.fail("cancelled voice must not fall back")
    monkeypatch.setattr(sarah_windows.sys, "platform", "win32")

    app._speak_worker(
        "Do not speak this obsolete reply",
        person_id="test-person",
        turn_id="turn-cancelled",
        voice_generation=9,
    )

    receipt = json.loads(settings["voice_route_receipt:test-person"])
    assert receipt["actual_route"] == "TEXT_ONLY"
    assert receipt["failure_reason"] == "stopped_by_owner"
    assert app.tasks.empty()


def test_windows_stop_during_synthesis_suppresses_fallback_and_playback(monkeypatch):
    import sarah_windows

    settings = {}
    calls = []

    class ReceiptDatabase:
        root = Path(".")

        @staticmethod
        def get_setting(_key):
            return "test-person"

        @staticmethod
        def set_setting(key, value):
            settings[key] = value

    app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)

    class CancellingVoice:
        configured = True

        @staticmethod
        def synthesize(_text, *, should_cancel, total_budget_seconds):
            assert total_budget_seconds == 15.0
            app._begin_voice_generation("stopped_by_owner")
            assert should_cancel()
            raise RuntimeError("voice_synthesis_cancelled")

    app.db = ReceiptDatabase()
    app.tasks = queue.Queue()
    app.voice = CancellingVoice()
    app.speaking = False
    app.voice_generation = 12
    app._voice_control_lock = threading.Lock()
    app._voice_cancel_reasons = {}
    app._active_voice_process = None
    app._active_voice_generation = None
    app._play_audio_file = lambda *_args: calls.append("playback") or (True, "")
    app._speak_windows_fallback = lambda *_args: calls.append("fallback") or (True, "")
    monkeypatch.setattr(sarah_windows.sys, "platform", "win32")

    app._speak_worker(
        "Stop while generating",
        person_id="test-person",
        turn_id="turn-synthesis-cancel",
        voice_generation=12,
    )

    assert calls == []
    receipt = json.loads(settings["voice_route_receipt:test-person"])
    assert receipt["actual_route"] == "TEXT_ONLY"
    assert receipt["failure_reason"] == "stopped_by_owner"
    assert app.tasks.empty()


def test_windows_profile_switch_suppresses_old_profile_voice_and_fallback(monkeypatch):
    import sarah_windows

    calls = []
    settings = {}

    class ReceiptDatabase:
        root = Path(".")

        @staticmethod
        def get_setting(_key):
            return "new-profile"

        @staticmethod
        def set_setting(key, value):
            settings[key] = value

    class CompletedVoice:
        configured = True
        last_cache_hit = False
        last_cache_key = "profile-switch-cache"
        model = "eleven_flash_v2_5"
        voice_id = "approved-voice"
        last_route_identity = "elevenlabs-protected"
        last_content_type = "audio/mpeg"
        last_route_receipt = "protected"

        @staticmethod
        def synthesize(_text, **_kwargs):
            return Path("old-profile.mp3")

    app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)
    app.db = ReceiptDatabase()
    app.tasks = queue.Queue()
    app.voice = CompletedVoice()
    app.speaking = False
    monkeypatch.setattr(sarah_windows, "playsound", lambda *_args, **_kwargs: calls.append("played"))
    monkeypatch.setattr(sarah_windows.sys, "platform", "win32")
    monkeypatch.setattr(
        sarah_windows.subprocess,
        "run",
        lambda *_args, **_kwargs: calls.append("fallback") or SimpleNamespace(returncode=0),
    )

    app._speak_worker("Old profile reply", person_id="old-profile", turn_id="turn-old")

    assert calls == []
    receipt = json.loads(settings["voice_route_receipt:old-profile"])
    assert receipt["actual_route"] == "TEXT_ONLY"
    assert receipt["failure_reason"] == "profile_changed"
    assert "stayed silent" in receipt["outcome"]
    assert app.tasks.empty()


def test_windows_connection_panel_reports_configuration_truth(monkeypatch):
    import sarah_windows

    shown = {}
    app = sarah_windows.SarahApp.__new__(sarah_windows.SarahApp)
    app.db = SimpleNamespace(root=Path("."))
    app.voice = SimpleNamespace(configured=False)
    app.research = SimpleNamespace(configured=False)
    monkeypatch.setattr(sarah_windows, "load_runtime_config", lambda _root: {})
    monkeypatch.setattr(
        sarah_windows.messagebox,
        "showinfo",
        lambda title, message: shown.update({"title": title, "message": message}),
    )
    app.show_sponsors()
    assert "Natural online voice: not configured" in shown["message"]
    assert "Current-source search: not configured" in shown["message"]
    assert "Hotel handoff: not configured" in shown["message"]
    assert "Gmail: not connected" in shown["message"]


def test_windows_research_prefers_protected_search_proxy(monkeypatch):
    calls = []

    class Response:
        @staticmethod
        def raise_for_status():
            return None

        @staticmethod
        def json():
            return {"results": [{
                "title": "Official visitor source",
                "url": "https://example.test/new-zealand",
                "summary": "Source-bound fixture",
            }]}

    def fake_post(url, **kwargs):
        calls.append((url, kwargs))
        return Response()

    monkeypatch.setattr("sarah_core.requests.post", fake_post)
    research = TavilyResearch(
        api_key="developer-key-must-not-be-used",
        backend_url="https://sarah.example",
        backend_token="revocable-app-token",
    )
    results = research.search("New Zealand visitor information", 3)
    assert results[0]["url"].startswith("https://")
    assert calls[0][0] == "https://sarah.example/search"
    assert calls[0][1]["headers"] == {"Authorization": "Bearer revocable-app-token"}
    assert "api_key" not in calls[0][1]["json"]
    assert calls[0][1]["timeout"] == (2.0, 8.0)
