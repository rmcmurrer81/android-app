from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
from typing import Callable

from sarah_core import app_home, safe_text


REFERENCE_FILENAME = "sarah_original_reference.wav"
REFERENCE_SHA256 = "6f92cdf4ee7d2409c08550d6b75553c944ca9180c3f056a462c437177e04a672"
REFERENCE_BYTES = 526124
VOICE_ID = "sarah-original-calm-female-pilot-v1"
MODEL_ID = "chatterbox-tts-local"
ROUTE_ID = "LOCAL_GENERATED_VOICE"


def packaged_resource(name: str) -> Path:
    root = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
    return root / name


class SarahLocalVoice:
    """Free local Sarah voice using an original synthetic reference + Chatterbox.

    The reference was created by the project's Qwen3-TTS VoiceDesign workflow.
    Runtime speech stays local after the optional one-time model setup.
    """

    MAX_TEXT_CHARACTERS = 4000
    MAX_SYNTHESIS_SECONDS = 240.0

    def __init__(self, root: Path | None = None):
        self.root = root or app_home()
        self.runtime_root = self.root / "local_voice"
        self.runtime_root.mkdir(parents=True, exist_ok=True)
        configured_python = safe_text(os.environ.get("SARAH_LOCAL_VOICE_PYTHON"))
        self.python = (
            Path(configured_python).expanduser()
            if configured_python
            else self.runtime_root / ".venv" / "Scripts" / "python.exe"
        )
        self.reference = self.runtime_root / REFERENCE_FILENAME
        self.worker = packaged_resource("sarah_local_voice_worker.py")
        self.setup_script = packaged_resource("SETUP_FREE_LOCAL_VOICE.ps1")
        self.device = safe_text(os.environ.get("SARAH_LOCAL_VOICE_DEVICE")) or "cpu"
        self.model = MODEL_ID
        self.voice_id = VOICE_ID
        self.last_cache_hit = False
        self.last_cache_key = ""
        self.last_route_identity = "local:chatterbox:original-sarah-v1"
        self.last_content_type = "audio/wav"
        self.last_route_receipt = ""

    @property
    def configured(self) -> bool:
        return (
            self.python.is_file()
            and self.worker.is_file()
            and self._reference_valid()
        )

    def _reference_valid(self) -> bool:
        try:
            if not self.reference.is_file() or self.reference.stat().st_size != REFERENCE_BYTES:
                return False
            return _sha256_file(self.reference) == REFERENCE_SHA256
        except OSError:
            return False

    def setup_available(self) -> bool:
        return self.setup_script.is_file()

    def synthesize(
        self,
        text: str,
        *,
        should_cancel: Callable[[], bool] | None = None,
        total_budget_seconds: float = MAX_SYNTHESIS_SECONDS,
    ) -> Path:
        cancel = should_cancel or (lambda: False)
        normalized = re.sub(r"\s+", " ", safe_text(text))[: self.MAX_TEXT_CHARACTERS]
        if not normalized:
            raise ValueError("Voice text is empty")
        if not self.configured:
            raise RuntimeError("Sarah local voice is not set up")

        identity = json.dumps(
            {
                "voice_id": self.voice_id,
                "model": self.model,
                "reference_sha256": REFERENCE_SHA256,
                "device": self.device,
                "text": normalized,
            },
            sort_keys=True,
            separators=(",", ":"),
        )
        digest = hashlib.sha256(identity.encode("utf-8")).hexdigest()
        target = self.root / "voice_cache" / f"{digest}.wav"
        self.last_cache_key = digest
        self.last_cache_hit = False
        self.last_route_receipt = ""
        if target.is_file() and target.stat().st_size >= 512:
            self.last_cache_hit = True
            self.last_route_receipt = "local-cache"
            return target

        request_dir = self.runtime_root / "requests"
        request_dir.mkdir(parents=True, exist_ok=True)
        text_path = request_dir / f"{digest}.txt"
        partial = request_dir / f"{digest}.part.wav"
        text_path.write_text(normalized, encoding="utf-8")
        partial.unlink(missing_ok=True)
        deadline = time.monotonic() + max(
            10.0, min(float(total_budget_seconds), self.MAX_SYNTHESIS_SECONDS)
        )
        env = os.environ.copy()
        # Setup pre-caches the model. Normal speech must not silently download.
        env["HF_HUB_OFFLINE"] = "1"
        env["TRANSFORMERS_OFFLINE"] = "1"
        command = [
            str(self.python),
            str(self.worker),
            "--reference",
            str(self.reference),
            "--text-file",
            str(text_path),
            "--output",
            str(partial),
            "--device",
            self.device,
        ]
        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=env,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        try:
            while process.poll() is None:
                if cancel():
                    process.terminate()
                    try:
                        process.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        process.kill()
                    raise RuntimeError("voice_synthesis_cancelled")
                if time.monotonic() >= deadline:
                    process.kill()
                    raise TimeoutError("Sarah local voice synthesis exceeded its time budget")
                time.sleep(0.08)
            stdout, stderr = process.communicate()
            if process.returncode != 0:
                detail = safe_text(stderr) or safe_text(stdout) or f"exit {process.returncode}"
                raise RuntimeError("Sarah local voice failed: " + detail[:400])
            if not partial.is_file() or partial.stat().st_size < 512:
                raise RuntimeError("Sarah local voice produced no usable WAV")
            partial.replace(target)
            self.last_route_receipt = "local-generated"
            return target
        finally:
            try:
                text_path.unlink(missing_ok=True)
            except OSError:
                pass
            try:
                partial.unlink(missing_ok=True)
            except OSError:
                pass


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
