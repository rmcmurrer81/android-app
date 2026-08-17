from pathlib import Path
import gc
import os
import tempfile
import time

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
