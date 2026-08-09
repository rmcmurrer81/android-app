from datetime import datetime, timezone
import json
from pathlib import Path

import pytest

import sarah_core


@pytest.fixture(autouse=True)
def _clear_backend_process_override(monkeypatch):
    monkeypatch.delenv("SARAH_MODEL_BACKEND_URL", raising=False)
    monkeypatch.delenv("SARAH_MODEL_BACKEND_TOKEN", raising=False)


def _write_event_capability(path: Path, *, expiry: str | None) -> None:
    value = {
        "SARAH_MODEL_BACKEND_URL": "https://sarah.example.test",
        "SARAH_MODEL_BACKEND_TOKEN": "test-only-short-lived-token",
    }
    if expiry is not None:
        value["SARAH_EVENT_AUTH_EXPIRES_UTC"] = expiry
    path.write_text(json.dumps(value), encoding="utf-8")


def test_internet_with_address_but_no_event_capability_requests_current_build(tmp_path: Path):
    bundled = tmp_path / "event.json"
    bundled.write_text(
        '{"SARAH_MODEL_BACKEND_URL":"https://sarah.example.test"}',
        encoding="utf-8",
    )
    state = sarah_core.online_access_status(True, root=tmp_path, bundled_path=bundled)
    assert state["activated"] is False
    assert state["action"] == "install_current_event_build_or_enroll_device"
    assert "no active Sarah event access" in state["label"]


def test_packaged_event_capability_is_automatic_and_expires_truthfully(tmp_path: Path):
    bundled = tmp_path / "event.json"
    bundled.write_text(
        '{"SARAH_MODEL_BACKEND_URL":"https://sarah.example.test",'
        '"SARAH_MODEL_BACKEND_TOKEN":"short-lived-test-token",'
        '"SARAH_EVENT_AUTH_EXPIRES_UTC":"2999-08-12T11:49:22.000Z"}',
        encoding="utf-8",
    )
    active = sarah_core.online_access_status(True, root=tmp_path, bundled_path=bundled)
    assert active["activated"] is True
    assert active["using_packaged_event_capability"] is True
    assert active["event_capability_expired"] is False
    assert active["action"] == "verify_and_retry"

    bundled.write_text(
        '{"SARAH_MODEL_BACKEND_URL":"https://sarah.example.test",'
        '"SARAH_MODEL_BACKEND_TOKEN":"short-lived-test-token",'
        '"SARAH_EVENT_AUTH_EXPIRES_UTC":"2000-01-01T00:00:00.000Z"}',
        encoding="utf-8",
    )
    expired = sarah_core.online_access_status(True, root=tmp_path, bundled_path=bundled)
    assert expired["activated"] is False
    assert expired["event_capability_expired"] is True
    assert expired["action"] == "install_current_event_build"


@pytest.mark.parametrize(
    "expiry",
    [
        None,
        "not-a-timestamp",
        "2030-01-01T00:00:01.000",
    ],
    ids=["missing", "invalid", "naive-without-z"],
)
def test_packaged_event_capability_fails_closed_without_exact_utc_expiry(
    monkeypatch, tmp_path: Path, expiry: str | None,
):
    monkeypatch.delenv("SARAH_MODEL_BACKEND_TOKEN", raising=False)
    bundled = tmp_path / "event.json"
    _write_event_capability(bundled, expiry=expiry)
    now = datetime(2030, 1, 1, tzinfo=timezone.utc)

    event = sarah_core.bundled_event_capability_status(bundled, now=now)
    assert event["present"] is True
    assert event["expiry_known"] is False
    assert event["active"] is False
    assert event["reason"] == "MISSING_OR_INVALID_EXPIRY"
    assert sarah_core.active_backend_token(tmp_path, bundled, now=now) == ""


def test_packaged_event_capability_expires_at_exact_boundary(monkeypatch, tmp_path: Path):
    monkeypatch.delenv("SARAH_MODEL_BACKEND_TOKEN", raising=False)
    bundled = tmp_path / "event.json"
    _write_event_capability(bundled, expiry="2030-01-01T00:00:00.000Z")
    boundary = datetime(2030, 1, 1, tzinfo=timezone.utc)

    event = sarah_core.bundled_event_capability_status(bundled, now=boundary)
    assert event["expiry_known"] is True
    assert event["expired"] is True
    assert event["active"] is False
    assert event["reason"] == "EXPIRED"
    assert sarah_core.active_backend_token(tmp_path, bundled, now=boundary) == ""


def test_packaged_event_capability_accepts_only_a_valid_future_utc_expiry(
    monkeypatch, tmp_path: Path,
):
    monkeypatch.delenv("SARAH_MODEL_BACKEND_TOKEN", raising=False)
    bundled = tmp_path / "event.json"
    _write_event_capability(bundled, expiry="2030-01-01T00:00:01.000Z")
    now = datetime(2030, 1, 1, tzinfo=timezone.utc)

    event = sarah_core.bundled_event_capability_status(bundled, now=now)
    assert event["expiry_known"] is True
    assert event["expired"] is False
    assert event["active"] is True
    assert event["reason"] == "ACTIVE"
    assert sarah_core.active_backend_token(tmp_path, bundled, now=now) == "test-only-short-lived-token"


def test_per_user_encrypted_token_remains_separate_from_invalid_event_bundle(
    monkeypatch, tmp_path: Path,
):
    monkeypatch.delenv("SARAH_MODEL_BACKEND_TOKEN", raising=False)
    bundled = tmp_path / "event.json"
    _write_event_capability(bundled, expiry="invalid")
    sarah_core.save_runtime_config(
        {
            "SARAH_MODEL_BACKEND_URL": "https://durable.sarah.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "per-user-encrypted-test-token",
        },
        tmp_path,
    )

    assert (
        sarah_core.active_backend_token(
            tmp_path,
            bundled,
            now=datetime(2030, 1, 1, tzinfo=timezone.utc),
        )
        == "per-user-encrypted-test-token"
    )
    state = sarah_core.online_access_status(True, root=tmp_path, bundled_path=bundled)
    assert state["activated"] is True
    assert state["using_packaged_event_capability"] is False
    assert state["event_capability_invalid"] is False


def test_valid_event_pair_overrides_stale_per_user_pair(monkeypatch, tmp_path: Path):
    monkeypatch.delenv("SARAH_MODEL_BACKEND_URL", raising=False)
    monkeypatch.delenv("SARAH_MODEL_BACKEND_TOKEN", raising=False)
    bundled = tmp_path / "event.json"
    _write_event_capability(bundled, expiry="2999-01-01T00:00:00.000Z")
    sarah_core.save_runtime_config(
        {
            "SARAH_MODEL_BACKEND_URL": "https://stale-user.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "stale-user-test-token",
        },
        tmp_path,
    )

    access = sarah_core.resolve_backend_access(tmp_path, bundled)
    assert access["source"] == "bundled_event"
    assert access["endpoint"] == "https://sarah.example.test"
    assert access["expires_utc"] == "2999-01-01T00:00:00.000Z"
    assert access["active"] is True
    state = sarah_core.online_access_status(True, root=tmp_path, bundled_path=bundled)
    assert state["using_packaged_event_capability"] is True
    assert state["activated"] is True


def test_model_search_and_voice_use_the_same_selected_event_pair(
    monkeypatch, tmp_path: Path,
):
    bundled = tmp_path / sarah_core.BUNDLED_EVENT_CONFIG_NAME
    _write_event_capability(bundled, expiry="2999-01-01T00:00:00.000Z")
    monkeypatch.setattr(sarah_core, "bundled_event_config_path", lambda: bundled)
    sarah_core.save_runtime_config(
        {
            "SARAH_MODEL_BACKEND_URL": "https://stale-user.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "stale-user-test-token",
        },
        tmp_path,
    )
    captured = {}

    class FakeResponse:
        @staticmethod
        def raise_for_status():
            return None

        @staticmethod
        def json():
            return {
                "reply": "<SPOKEN>Hello.</SPOKEN>"
                "<PRIVATE_MIND>Sarah is attentive.</PRIVATE_MIND>"
                "<FACTUAL_TRUTH>No external action occurred.</FACTUAL_TRUTH>"
                "<CLASSIFICATION>TRUTHFUL_STATEMENT</CLASSIFICATION>",
                "provider": "workers-ai",
                "model": "event-test-model",
                "online": True,
                "web_search_requested": False,
                "web_search_applied": False,
                "source_urls": [],
            }

    def fake_post(url, **kwargs):
        captured["url"] = url
        captured.update(kwargs)
        return FakeResponse()

    monkeypatch.setattr(sarah_core.requests, "post", fake_post)
    reply = sarah_core.ModelClient(sarah_core.SarahDatabase(tmp_path)).respond("Hello")
    assert reply.route == "ONLINE_WORKERS_AI"
    assert captured["url"].startswith("https://sarah.example.test?")
    assert captured["headers"]["Authorization"] == "Bearer test-only-short-lived-token"

    research = sarah_core.TavilyResearch(root=tmp_path)
    voice = sarah_core.ElevenLabsVoice(tmp_path)
    assert research.backend_url == "https://sarah.example.test/search"
    assert voice.backend_url == "https://sarah.example.test/voice"
    assert research.backend_token == voice.backend_token


@pytest.mark.parametrize("environment_half", ["url", "token"])
def test_incomplete_environment_pair_does_not_mix_with_another_source(
    monkeypatch, tmp_path: Path, environment_half: str,
):
    monkeypatch.delenv("SARAH_MODEL_BACKEND_URL", raising=False)
    monkeypatch.delenv("SARAH_MODEL_BACKEND_TOKEN", raising=False)
    if environment_half == "url":
        monkeypatch.setenv("SARAH_MODEL_BACKEND_URL", "https://operator.example.test")
    else:
        monkeypatch.setenv("SARAH_MODEL_BACKEND_TOKEN", "operator-half-test-token")
    bundled = tmp_path / "event.json"
    _write_event_capability(bundled, expiry="2999-01-01T00:00:00.000Z")

    access = sarah_core.resolve_backend_access(tmp_path, bundled)
    assert access["source"] == "bundled_event"
    assert access["endpoint"] == "https://sarah.example.test"


def test_complete_environment_pair_is_the_only_process_override(
    monkeypatch, tmp_path: Path,
):
    monkeypatch.setenv("SARAH_MODEL_BACKEND_URL", "https://operator.example.test")
    monkeypatch.setenv("SARAH_MODEL_BACKEND_TOKEN", "operator-pair-test-token")
    bundled = tmp_path / "event.json"
    _write_event_capability(bundled, expiry="2999-01-01T00:00:00.000Z")

    access = sarah_core.resolve_backend_access(tmp_path, bundled)
    assert access["source"] == "environment"
    assert access["endpoint"] == "https://operator.example.test"
    assert access["expires_utc"] == ""


def test_expired_packaged_event_bearer_is_not_sent(monkeypatch, tmp_path: Path):
    bundled = tmp_path / sarah_core.BUNDLED_EVENT_CONFIG_NAME
    bundled.write_text(
        '{"SARAH_MODEL_BACKEND_URL":"https://sarah.example.test",'
        '"SARAH_MODEL_BACKEND_TOKEN":"expired-short-lived-test-token",'
        '"SARAH_EVENT_AUTH_EXPIRES_UTC":"2000-01-01T00:00:00.000Z"}',
        encoding="utf-8",
    )
    monkeypatch.setattr(sarah_core, "bundled_event_config_path", lambda: bundled)
    monkeypatch.setattr(
        sarah_core.requests,
        "post",
        lambda *args, **kwargs: (_ for _ in ()).throw(
            AssertionError("expired event bearer escaped to the network")
        ),
    )
    reply = sarah_core.ModelClient(sarah_core.SarahDatabase(tmp_path)).respond("Hello Sarah")
    assert reply.route == "OFFLINE_LOCAL"
    assert "no active sarah event capability" in reply.spoken.lower()


def test_saved_owner_activation_reconnects_without_reentering_code(tmp_path: Path):
    bundled = tmp_path / "event.json"
    bundled.write_text(
        '{"SARAH_MODEL_BACKEND_URL":"https://sarah.example.test"}',
        encoding="utf-8",
    )
    sarah_core.save_runtime_config(
        {
            "SARAH_MODEL_BACKEND_URL": "https://sarah.example.test",
            "SARAH_MODEL_BACKEND_TOKEN": "owner-activated-test-token",
        },
        tmp_path,
    )
    state = sarah_core.online_access_status(True, root=tmp_path, bundled_path=bundled)
    assert state["activated"] is True
    assert state["action"] == "verify_and_retry"
    assert "checking" in state["label"].lower()


def test_no_token_never_sends_an_unauthenticated_inference_request(
    monkeypatch, tmp_path: Path,
):
    bundled = tmp_path / sarah_core.BUNDLED_EVENT_CONFIG_NAME
    bundled.write_text(
        '{"SARAH_MODEL_BACKEND_URL":"https://sarah.example.test"}',
        encoding="utf-8",
    )
    monkeypatch.setattr(sarah_core, "bundled_event_config_path", lambda: bundled)
    monkeypatch.setattr(
        sarah_core.requests,
        "post",
        lambda *args, **kwargs: (_ for _ in ()).throw(
            AssertionError("an unauthenticated inference request escaped")
        ),
    )
    db = sarah_core.SarahDatabase(tmp_path)
    reply = sarah_core.ModelClient(db).respond("Hello Sarah")
    assert "no active sarah event capability" in reply.spoken.lower()
    assert "invent or paste a private code" in reply.spoken.lower()
    assert reply.route == "OFFLINE_LOCAL"


def test_current_request_without_activation_is_actionable_and_still_fail_closed(
    monkeypatch, tmp_path: Path,
):
    bundled = tmp_path / sarah_core.BUNDLED_EVENT_CONFIG_NAME
    bundled.write_text(
        '{"SARAH_MODEL_BACKEND_URL":"https://sarah.example.test"}',
        encoding="utf-8",
    )
    monkeypatch.setattr(sarah_core, "bundled_event_config_path", lambda: bundled)
    monkeypatch.setattr(
        sarah_core.requests,
        "post",
        lambda *args, **kwargs: (_ for _ in ()).throw(
            AssertionError("an unauthenticated search escaped")
        ),
    )
    db = sarah_core.SarahDatabase(tmp_path)
    reply = sarah_core.ModelClient(db).respond("What is happening nearby this week?")
    assert "no active sarah event capability" in reply.spoken.lower()
    assert reply.route == "TOOL_UNAVAILABLE"
    assert "no active event capability" in reply.factual_truth.lower()
