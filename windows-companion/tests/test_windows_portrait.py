from pathlib import Path
import shutil
import sys

from PIL import Image

from sarah_windows import (
    SARAH_PORTRAIT_BYTES,
    SARAH_PORTRAIT_DIMENSIONS,
    SARAH_PORTRAIT_RELATIVE_PATH,
    SARAH_PORTRAIT_SHA256,
    inspect_sarah_portrait,
    packaged_resource_candidates,
    portrait_packaged_self_test,
    resolve_sarah_portrait,
)


WINDOWS_ROOT = Path(__file__).resolve().parents[1]
RUNTIME_PORTRAIT = WINDOWS_ROOT / SARAH_PORTRAIT_RELATIVE_PATH


def test_exact_runtime_portrait_hash_dimensions_and_size():
    report = inspect_sarah_portrait(RUNTIME_PORTRAIT)
    assert report["valid"] is True
    assert report["reason"] == "ok"
    assert report["bytes"] == SARAH_PORTRAIT_BYTES
    assert report["sha256"] == SARAH_PORTRAIT_SHA256
    assert report["dimensions"] == SARAH_PORTRAIT_DIMENSIONS
    assert report["format"] == "PNG"


def test_source_and_meipass_resource_resolution(tmp_path, monkeypatch):
    source_candidates = packaged_resource_candidates(
        SARAH_PORTRAIT_RELATIVE_PATH,
        source_root=WINDOWS_ROOT,
        bundle_root=tmp_path / "not-packaged",
    )
    assert source_candidates[-1] == RUNTIME_PORTRAIT
    assert resolve_sarah_portrait(
        source_root=WINDOWS_ROOT,
        bundle_root=tmp_path / "not-packaged",
    ) == RUNTIME_PORTRAIT

    bundle = tmp_path / "bundle"
    packaged = bundle / SARAH_PORTRAIT_RELATIVE_PATH
    packaged.parent.mkdir(parents=True)
    shutil.copy2(RUNTIME_PORTRAIT, packaged)
    monkeypatch.setattr(sys, "_MEIPASS", str(bundle), raising=False)
    assert resolve_sarah_portrait(source_root=tmp_path / "missing-source") == packaged
    assert portrait_packaged_self_test(source_root=tmp_path / "missing-source")["valid"] is True


def test_missing_or_corrupt_portrait_fails_closed_to_vector(tmp_path, monkeypatch):
    monkeypatch.delattr(sys, "_MEIPASS", raising=False)
    assert resolve_sarah_portrait(source_root=tmp_path) is None
    corrupt = tmp_path / SARAH_PORTRAIT_RELATIVE_PATH
    corrupt.parent.mkdir(parents=True)
    corrupt.write_bytes(b"not a PNG")
    report = inspect_sarah_portrait(corrupt)
    assert report["valid"] is False
    assert report["reason"].startswith("invalid_image:")
    assert resolve_sarah_portrait(source_root=tmp_path) is None


def test_hash_size_and_dimension_drift_are_rejected(tmp_path, monkeypatch):
    monkeypatch.delattr(sys, "_MEIPASS", raising=False)
    drift = tmp_path / SARAH_PORTRAIT_RELATIVE_PATH
    drift.parent.mkdir(parents=True)
    Image.new("RGB", SARAH_PORTRAIT_DIMENSIONS, "#123456").save(drift, format="PNG")
    report = inspect_sarah_portrait(drift)
    assert report["valid"] is False
    assert report["dimensions"] == SARAH_PORTRAIT_DIMENSIONS
    assert report["sha256"] != SARAH_PORTRAIT_SHA256
    assert report["bytes"] != SARAH_PORTRAIT_BYTES
    assert "sha256" in report["reason"] and "size" in report["reason"]

    wrong_dimensions = tmp_path / "wrong_dimensions.png"
    Image.new("RGB", (511, 512), "#123456").save(wrong_dimensions, format="PNG")
    dimensions_report = inspect_sarah_portrait(wrong_dimensions)
    assert dimensions_report["valid"] is False
    assert dimensions_report["dimensions"] == (511, 512)
    assert "dimensions" in dimensions_report["reason"]
