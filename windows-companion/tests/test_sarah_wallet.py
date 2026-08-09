from __future__ import annotations

import io
import json
from pathlib import Path

from PIL import Image, PngImagePlugin
import pytest

import sarah_wallet
from sarah_core import SarahDatabase
from sarah_wallet import SarahWallet, WalletError, WalletLimitError, WalletValidationError


def database(root: Path) -> tuple[SarahDatabase, str, str]:
    root.mkdir(parents=True, exist_ok=True)
    db = SarahDatabase(root)
    robert = db.ensure_profile("Robert", 30, memory_consent=True, age_known=True)
    other = db.ensure_profile("Other Owner", 30, memory_consent=True, age_known=True)
    db.set_setting("active_person_id", robert)
    return db, robert, other


def qr_like_png(path: Path, *, metadata: bool = True) -> bytes:
    image = Image.new("RGB", (128, 128), "white")
    pixels = image.load()
    for y in range(0, 128, 8):
        for x in range(0, 128, 8):
            if (x // 8 + y // 8) % 2:
                for py in range(y, y + 8):
                    for px in range(x, x + 8):
                        pixels[px, py] = (0, 0, 0)
    info = PngImagePlugin.PngInfo()
    if metadata:
        info.add_text("private_note", "must be stripped")
    image.save(path, format="PNG", pnginfo=info)
    return path.read_bytes()


def test_loyalty_records_are_encrypted_and_profile_isolated(tmp_path: Path) -> None:
    db, robert, other = database(tmp_path / "runtime")
    wallet = SarahWallet(db)
    saved = wallet.add_loyalty(
        program_name="Example Air Rewards",
        member_identifier="MEMBER-48291",
        member_name="Robert",
        tier="Silver",
        official_url="https://rewards.example.com/member/home?view=card",
        person_id=robert,
    )
    assert saved["record_type"] == "loyalty"
    assert saved["fields"]["member_identifier"] == "MEMBER-48291"
    assert len(wallet.list_records(person_id=robert)) == 1
    assert wallet.list_records(person_id=other) == []
    raw = wallet.vault_path.read_text(encoding="utf-8")
    assert "Example Air Rewards" not in raw
    assert "MEMBER-48291" not in raw
    assert robert not in raw
    assert "ciphertext" in raw


def test_ticket_image_is_sanitized_bounded_and_url_is_exact(tmp_path: Path) -> None:
    db, robert, _other = database(tmp_path / "runtime")
    source = tmp_path / "owner-selected-pass.png"
    original = qr_like_png(source)
    wallet = SarahWallet(db)
    exact_url = "https://tickets.example.org/event/42?source=official#entry"
    saved = wallet.add_ticket_pass(
        title="Example Event Pass",
        official_url=exact_url,
        image_path=source,
        metadata={"issuer": "Example Events", "venue": "Hall A", "seat": "B-12"},
        person_id=robert,
    )
    assert saved["official_url"] == exact_url
    assert saved["owner_truth"] == "OWNER_PROVIDED_REFERENCE_NOT_PURCHASE_OR_ADMISSION_PROOF"
    data, mime = wallet.get_image_bytes(saved["record_id"], person_id=robert)
    assert mime == "image/png"
    assert data != original
    assert len(data) <= sarah_wallet.MAX_SANITIZED_IMAGE_BYTES
    with Image.open(io.BytesIO(data)) as opened:
        assert opened.size == (128, 128)
        assert "private_note" not in opened.info
    assert source.resolve().as_posix() not in wallet.vault_path.read_text(encoding="utf-8")


def test_url_credentials_passwords_and_payment_numbers_fail_closed(tmp_path: Path) -> None:
    db, robert, _other = database(tmp_path / "runtime")
    wallet = SarahWallet(db)
    with pytest.raises(WalletValidationError, match="HTTPS"):
        wallet.add_loyalty(program_name="Rail", member_identifier="R-1", official_url="http://example.com", person_id=robert)
    with pytest.raises(WalletValidationError, match="credentials"):
        wallet.add_loyalty(program_name="Rail", member_identifier="R-1", official_url="https://user:secret@example.com", person_id=robert)
    with pytest.raises(WalletValidationError, match="credential query"):
        wallet.add_loyalty(program_name="Rail", member_identifier="R-1", official_url="https://example.com/card?access_token=secret", person_id=robert)
    with pytest.raises(WalletValidationError, match="password"):
        wallet.add_loyalty(program_name="Rail", member_identifier="R-1", notes="password: secret", person_id=robert)
    with pytest.raises(WalletValidationError, match="payment-card"):
        wallet.add_loyalty(program_name="Rail", member_identifier="4111 1111 1111 1111", person_id=robert)
    # Benign names are not rejected merely because a word resembles a secret
    # field; there is no freeform credential value in this field.
    saved = wallet.add_loyalty(program_name="Pin Collectors Club", member_identifier="R-2", person_id=robert)
    assert saved["fields"]["program_name"] == "Pin Collectors Club"


def test_invalid_and_oversized_image_is_rejected_before_vault_change(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    db, robert, _other = database(tmp_path / "runtime")
    wallet = SarahWallet(db)
    invalid = tmp_path / "not-an-image.png"
    invalid.write_bytes(b"not an image")
    with pytest.raises(WalletValidationError, match="readable"):
        wallet.add_ticket_pass(title="Bad", official_url="https://example.org", image_path=invalid, person_id=robert)
    source = tmp_path / "valid.png"
    qr_like_png(source, metadata=False)
    monkeypatch.setattr(sarah_wallet, "MAX_SOURCE_IMAGE_BYTES", 10)
    with pytest.raises(WalletLimitError, match="12 MiB"):
        wallet.add_ticket_pass(title="Large", official_url="https://example.org", image_path=source, person_id=robert)
    assert wallet.list_records(person_id=robert) == []


def test_remove_is_explicit_and_cannot_cross_profiles(tmp_path: Path) -> None:
    db, robert, other = database(tmp_path / "runtime")
    wallet = SarahWallet(db)
    saved = wallet.add_loyalty(program_name="Rail", member_identifier="R-99", person_id=robert)
    with pytest.raises(WalletValidationError, match="not found"):
        wallet.remove_record(saved["record_id"], person_id=other)
    assert len(wallet.list_records(person_id=robert)) == 1
    receipt = wallet.remove_record(saved["record_id"], person_id=robert)
    assert receipt == {
        "record_id": saved["record_id"],
        "removed": True,
        "image_removed": False,
        "logical_record_recovery_supported": False,
        "forensic_storage_recovery": "NOT_ASSESSED",
    }
    assert wallet.list_records(person_id=robert) == []


def test_sync_projection_never_contains_image_or_member_identifier(tmp_path: Path) -> None:
    db, robert, _other = database(tmp_path / "runtime")
    source = tmp_path / "pass.png"
    qr_like_png(source)
    wallet = SarahWallet(db)
    wallet.add_loyalty(program_name="Rail Rewards", member_identifier="PRIVATE-MEMBER-77", person_id=robert)
    wallet.add_ticket_pass(title="Event Pass", official_url="https://tickets.example.org/e/1", image_path=source, person_id=robert)
    projection = wallet.sync_projection(person_id=robert)
    encoded = json.dumps(projection)
    assert "data_b64" not in encoded
    assert "PRIVATE-MEMBER-77" not in encoded
    assert "owner-selected-pass" not in encoded
    assert all(set(row) == {
        "record_id", "record_type", "title", "official_url", "has_image",
        "image_sha256", "owner_truth",
    } for row in projection)


def test_record_limit_and_corruption_do_not_overwrite_existing_vault(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    db, robert, _other = database(tmp_path / "runtime")
    wallet = SarahWallet(db)
    monkeypatch.setattr(sarah_wallet, "MAX_RECORDS_PER_PROFILE", 1)
    wallet.add_loyalty(program_name="One", member_identifier="A-1", person_id=robert)
    with pytest.raises(WalletLimitError, match="100-record"):
        wallet.add_loyalty(program_name="Two", member_identifier="A-2", person_id=robert)
    wallet.vault_path.write_text("{corrupt", encoding="utf-8")
    original = wallet.vault_path.read_bytes()
    with pytest.raises(WalletError, match="not overwritten"):
        wallet.add_loyalty(program_name="Three", member_identifier="A-3", person_id=robert)
    assert wallet.vault_path.read_bytes() == original


def test_key_is_wrapped_for_current_user_and_plaintext_key_is_absent(tmp_path: Path) -> None:
    db, _robert, _other = database(tmp_path / "runtime")
    wallet = SarahWallet(db)
    raw = wallet.key_path.read_text(encoding="utf-8")
    assert base64_key(wallet._data_key) not in raw
    if sarah_wallet.os.name == "nt":
        assert "dpapi-v1:" in raw
    assert wallet.storage_status()["current_user_key_protection"] in {"WINDOWS_DPAPI", "LOCAL_TEST_FALLBACK"}


def base64_key(value: bytes) -> str:
    import base64
    return base64.b64encode(value).decode("ascii")
