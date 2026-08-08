from pathlib import Path
import gc
import json
import os
import tempfile
import time

from PIL import Image
import pytest
import requests

from sarah_core import (
    ChannelResponse, ElevenLabsVoice, ModelClient, SarahDatabase, corrected_name,
    is_stress_or_fear, load_bundled_event_config, load_runtime_config,
    runtime_setting, save_runtime_config, sync_decrypt, sync_encrypt,
    sync_signature, transport_context, universal_calm,
)


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
        first_database.add_message("user", "Hello")
        payload = first_database.export_sync(False)

        second_root = make_sarah_home(Path(temp) / "second")
        second_database = SarahDatabase(second_root)
        counts = second_database.import_sync(payload)
        rows = second_database.list_rows("trips")
        assert counts["messages"] >= 1
        assert rows[0]["destination"] == "New Zealand"

        del first_database, second_database
        wait_for_windows_handles()


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
        assert "NOT_ALLOWED" not in path.read_text(encoding="utf-8")
        assert load_runtime_config(root)["SARAH_MODEL_BACKEND_TOKEN"] == "saved-token"
        assert runtime_setting("SARAH_MODEL_BACKEND_TOKEN", root=root) == "saved-token"
        monkeypatch.setenv("SARAH_MODEL_BACKEND_TOKEN", "environment-token")
        assert runtime_setting("SARAH_MODEL_BACKEND_TOKEN", root=root) == "environment-token"
        with pytest.raises(ValueError):
            save_runtime_config({"SARAH_MODEL_BACKEND_URL": "http://not-protected.test"}, root)


def test_runtime_setting_precedence_includes_ci_bundled_event_config(monkeypatch):
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
            "SARAH_MODEL_BACKEND_TOKEN": "event-token",
        }
        assert runtime_setting(
            "SARAH_MODEL_BACKEND_TOKEN", root=root, bundled_path=bundle,
        ) == "event-token"

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
                    "<CLASSIFICATION>TRUTHFUL_STATEMENT</CLASSIFICATION>"
                }

        def fake_post(url, **kwargs):
            captured["url"] = url
            captured.update(kwargs)
            return FakeResponse()

        monkeypatch.setattr("sarah_core.requests.post", fake_post)
        response = ModelClient(database).respond("Hello Sarah")
        assert response.spoken == "Hi Robert."
        assert captured["url"] == "https://sarah.example.test"
        assert captured["headers"]["Authorization"] == "Bearer local-test-token"
        payload = captured["json"]
        assert payload["provider"] == "workers-ai"
        assert payload["model"] == "@cf/google/gemma-4-26b-a4b-it"
        assert payload["message"] == "Hello Sarah"
        assert payload["system_prompt"]
        assert payload["history"][-1]["content"] == "Hello Sarah"
        assert "system" not in payload
        assert "store" not in payload

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


def test_windows_online_failure_falls_back_for_turn_and_retries_next_turn(monkeypatch):
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
        assert calls["count"] == 2
        assert "temporarily unavailable" in first.spoken
        assert "temporarily unavailable" in second.spoken
        assert first.classification == "RUNTIME_STATE_ERROR"

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
