from pathlib import Path
import tempfile

from PIL import Image
import pytest

from sarah_core import (
    ChannelResponse, SarahDatabase, corrected_name, is_stress_or_fear,
    sync_decrypt, sync_encrypt, sync_signature, transport_context, universal_calm,
)


def make_sarah_home(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    (path / "photos").mkdir(exist_ok=True)
    (path / "voice_cache").mkdir(exist_ok=True)
    (path / "backups").mkdir(exist_ok=True)
    return path


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
    with tempfile.TemporaryDirectory() as temp:
        root = make_sarah_home(Path(temp) / "one")
        monkeypatch.setenv("SARAH_HOME", str(root))
        db = SarahDatabase(root)
        db.ensure_profile("Robert", 45, "Newark", "Power Rangers", True)
        db.add_trip("New Zealand test", "New Zealand")
        db.add_message("user", "Plan my trip")
        db.add_mind_event(ChannelResponse("Okay", "curious", "No booking occurred", "TRUTHFUL_STATEMENT", True), "test")
        source = Path(temp) / "photo.png"
        Image.new("RGB", (20, 20), "blue").save(source)
        db.import_photo(source, "test photo")
        backup = Path(temp) / "backup.sarahmind"
        db.create_backup(backup, "correct horse battery")
        assert backup.exists()

        wrong_root = make_sarah_home(Path(temp) / "wrong")
        with pytest.raises(Exception):
            SarahDatabase(wrong_root).restore_backup(backup, "wrong password")

        restored_root = make_sarah_home(Path(temp) / "restored")
        restored = SarahDatabase(restored_root)
        restored.restore_backup(backup, "correct horse battery")
        assert restored.path.exists()


def test_sync_import_merges_rows(monkeypatch):
    with tempfile.TemporaryDirectory() as temp:
        first_root = make_sarah_home(Path(temp) / "first")
        first = SarahDatabase(first_root)
        first.ensure_profile("Robert", 45, "Newark", "Power Rangers", True)
        first.add_trip("NZ", "New Zealand")
        first.add_message("user", "Hello")
        payload = first.export_sync(False)

        second_root = make_sarah_home(Path(temp) / "second")
        second = SarahDatabase(second_root)
        counts = second.import_sync(payload)
        assert counts["messages"] >= 1
        assert second.list_rows("trips")[0]["destination"] == "New Zealand"
