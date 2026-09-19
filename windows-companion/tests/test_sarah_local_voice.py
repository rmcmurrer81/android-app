from pathlib import Path
import tempfile

from sarah_local_voice import SarahLocalVoice


def test_local_voice_cache_status_and_owner_clear_are_wav_only():
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as folder:
        root = Path(folder)
        voice = SarahLocalVoice(root)
        cache = root / "voice_cache"
        cache.mkdir(parents=True, exist_ok=True)
        (cache / "one.wav").write_bytes(b"W" * 700)
        (cache / "two.wav").write_bytes(b"A" * 900)
        (cache / "keep.txt").write_text("not a voice derivative", encoding="utf-8")

        status = voice.cache_status()
        assert status["size_bytes"] == 1600
        assert status["over_documented_max"] is False

        result = voice.clear_cache_by_owner_request()
        assert result == {"removed_files": 2, "removed_bytes": 1600}
        assert not (cache / "one.wav").exists()
        assert not (cache / "two.wav").exists()
        assert (cache / "keep.txt").is_file()


def test_local_voice_is_not_configured_without_exact_reference_and_runtime():
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as folder:
        voice = SarahLocalVoice(Path(folder))
        assert voice.configured is False
