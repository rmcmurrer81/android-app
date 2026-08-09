from pathlib import Path

import sarah_core


def test_internet_with_bundled_address_requires_one_owner_activation(tmp_path: Path):
    bundled = tmp_path / "event.json"
    bundled.write_text(
        '{"SARAH_MODEL_BACKEND_URL":"https://sarah.example.test"}',
        encoding="utf-8",
    )
    state = sarah_core.online_access_status(True, root=tmp_path, bundled_path=bundled)
    assert state["activated"] is False
    assert state["action"] == "enter_owner_access_code"
    assert "access code once" in state["label"]


def test_saved_owner_activation_reconnects_without_reentering_code(tmp_path: Path):
    bundled = tmp_path / "event.json"
    bundled.write_text(
        '{"SARAH_MODEL_BACKEND_URL":"https://sarah.example.test"}',
        encoding="utf-8",
    )
    sarah_core.save_runtime_config(
        {"SARAH_MODEL_BACKEND_TOKEN": "owner-activated-test-token"}, tmp_path,
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
    assert "access code once" in reply.spoken.lower()
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
    assert "access code once" in reply.spoken.lower()
    assert reply.route == "TOOL_UNAVAILABLE"
    assert "no owner access code is activated" in reply.factual_truth.lower()
