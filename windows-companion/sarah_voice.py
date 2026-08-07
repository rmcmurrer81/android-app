from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from sarah_core import ElevenLabsVoice, safe_text

try:
    from playsound3 import playsound
except Exception:
    playsound = None


CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


@dataclass(frozen=True)
class VoiceResult:
    ok: bool
    mode: str
    detail: str = ""


class WindowsVoiceEngine:
    """Reliable Sarah speech with visible diagnostics and an offline Windows fallback."""

    def __init__(self) -> None:
        self.eleven = ElevenLabsVoice()

    @staticmethod
    def _powershell() -> str:
        discovered = shutil.which("powershell.exe") or shutil.which("powershell")
        if discovered:
            return discovered
        root = Path(os.environ.get("SystemRoot", r"C:\Windows"))
        return str(root / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe")

    @property
    def preferred_mode(self) -> str:
        if self.eleven.configured and playsound is not None:
            return "ElevenLabs online voice"
        if sys.platform.startswith("win"):
            return "Windows offline voice"
        return "Text only"

    def speak(self, text: str) -> VoiceResult:
        spoken = safe_text(text)
        if not spoken:
            return VoiceResult(True, "silent", "Nothing to speak")

        if self.eleven.configured and playsound is not None:
            try:
                audio = self.eleven.synthesize(spoken)
                playsound(str(audio), block=True)
                return VoiceResult(True, "ElevenLabs", "Connected Sarah voice played")
            except Exception as error:
                online_error = str(error)
            else:
                online_error = ""
        else:
            online_error = "ElevenLabs is not configured for this installation"

        if not sys.platform.startswith("win"):
            return VoiceResult(False, "text-only", online_error)

        result = self._speak_with_windows(spoken)
        if result.ok:
            detail = result.detail
            if online_error:
                detail = f"{detail}; online voice unavailable: {online_error}"
            return VoiceResult(True, result.mode, detail)
        return VoiceResult(False, result.mode, f"{online_error}; {result.detail}".strip("; "))

    def _speak_with_windows(self, text: str) -> VoiceResult:
        # Passing the sentence through an environment variable avoids quoting failures
        # for apostrophes, URLs, Unicode names, and long travel answers.
        script = r"""
$ErrorActionPreference = 'Stop'
$text = $env:SARAH_SPEAK_TEXT
try {
    Add-Type -AssemblyName System.Speech
    $voice = New-Object System.Speech.Synthesis.SpeechSynthesizer
    $voice.Volume = 100
    $voice.Rate = -1
    $voice.Speak($text)
    $voice.Dispose()
    Write-Output 'SYSTEM_SPEECH_OK'
    exit 0
}
catch {
    try {
        $voice = New-Object -ComObject SAPI.SpVoice
        $null = $voice.Speak($text)
        Write-Output 'SAPI_OK'
        exit 0
    }
    catch {
        Write-Error ($_.Exception.Message)
        exit 1
    }
}
"""
        environment = os.environ.copy()
        environment["SARAH_SPEAK_TEXT"] = text[:9000]
        try:
            with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8-sig", suffix=".ps1", delete=False
            ) as handle:
                handle.write(script)
                script_path = handle.name
            completed = subprocess.run(
                [
                    self._powershell(),
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    script_path,
                ],
                capture_output=True,
                text=True,
                timeout=120,
                env=environment,
                creationflags=CREATE_NO_WINDOW,
            )
        except Exception as error:
            return VoiceResult(False, "Windows voice", str(error))
        finally:
            try:
                Path(locals().get("script_path", "")).unlink(missing_ok=True)
            except Exception:
                pass

        output = (completed.stdout or "").strip()
        error_text = (completed.stderr or "").strip()
        if completed.returncode == 0:
            mode = "Windows System.Speech" if "SYSTEM_SPEECH_OK" in output else "Windows SAPI"
            return VoiceResult(True, mode, "Offline speech played")
        return VoiceResult(False, "Windows voice", error_text or output or f"exit {completed.returncode}")

    def self_test(self) -> VoiceResult:
        if not sys.platform.startswith("win"):
            return VoiceResult(True, "non-Windows CI", "Windows audio test skipped")
        return self._speak_with_windows("Sarah voice test. If you hear this, the Windows fallback is working.")
