from pathlib import Path
import tempfile

from sarah_conversation import ConversationEngine
from sarah_core import SarahDatabase
from sarah_event_ready import render_avatar_frame
from sarah_voice import WindowsVoiceEngine


def test_offline_conversation_is_not_the_repeated_configuration_error():
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as folder:
        db = SarahDatabase(Path(folder))
        db.ensure_profile("Robert", 45, "Newark", "Power Rangers", True)
        engine = ConversationEngine(db)
        response = engine._offline_response("How are you?", db.active_profile())
        assert response.spoken
        assert "model is not configured" not in response.spoken.lower()
        assert "actually talking" in response.spoken.lower()


def test_offline_destination_response_is_useful_and_truthful():
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as folder:
        db = SarahDatabase(Path(folder))
        db.ensure_profile("Robert", 45, "Newark", "Power Rangers", True)
        engine = ConversationEngine(db)
        response = engine._offline_response("I am thinking about going to Mexico", db.active_profile())
        assert "Mexico" in response.spoken
        assert "not saved or booked" in response.spoken
        assert "No trip" in response.factual_truth


def test_avatar_frames_render_with_real_transparency():
    for expression in ("neutral", "smile", "talk", "blink"):
        frame = render_avatar_frame(expression, 250, 410)
        assert frame.mode == "RGBA"
        assert frame.size == (250, 410)
        assert frame.getbbox() is not None
        assert frame.getextrema()[3][0] == 0
        assert frame.getextrema()[3][1] == 255


def test_voice_engine_always_describes_its_available_mode():
    engine = WindowsVoiceEngine()
    assert engine.preferred_mode in {
        "ElevenLabs online voice",
        "Windows offline voice",
        "Text only",
    }
