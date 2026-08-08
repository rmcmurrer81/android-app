from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import queue
import socket
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog, ttk
import webbrowser

from PIL import Image, ImageDraw, ImageTk

from sarah_live_avatar import (
    AvatarMotionModel,
    HIDDEN_AVATAR_POLL_INTERVAL_MS,
    LIVE_AVATAR_FRAME_INTERVAL_MS,
    PortraitFrameRenderer,
    decode_audio_envelope,
)

from sarah_core import (
    ChannelResponse, ElevenLabsVoice, ModelClient, SarahDatabase, TavilyResearch,
    app_home, corrected_name, discovery_queries, is_stress_or_fear,
    load_runtime_config, needs_owner_identity_confirmation, route_label, safe_text,
    save_runtime_config, runtime_setting,
)
from sarah_sync_server import SarahSyncServer

try:
    import pystray
except Exception:
    pystray = None
try:
    from playsound3 import playsound
except Exception:
    playsound = None


SARAH_PORTRAIT_RELATIVE_PATH = Path("assets") / "sarah_adult_portrait_r2_runtime_512.png"
SARAH_PORTRAIT_SHA256 = "3f5801ddcb99ba5e20a2f1a62d1bca8415210b1545c9b05ca1115b123a7b5b4f"
SARAH_PORTRAIT_BYTES = 294551
SARAH_PORTRAIT_DIMENSIONS = (512, 512)
SARAH_PORTRAIT_DISPLAY_SIZE = (180, 175)


def packaged_resource_candidates(
        relative_path: Path | str,
        *,
        source_root: Path | str | None = None,
        bundle_root: Path | str | None = None) -> tuple[Path, ...]:
    """Return packaged-first and source-tree candidates without absolute developer paths."""
    relative = Path(relative_path)
    roots: list[Path] = []
    frozen_root = bundle_root if bundle_root is not None else getattr(sys, "_MEIPASS", None)
    if frozen_root:
        roots.append(Path(frozen_root))
    roots.append(Path(source_root) if source_root is not None else Path(__file__).resolve().parent)
    candidates: list[Path] = []
    for root in roots:
        candidate = root / relative
        if candidate not in candidates:
            candidates.append(candidate)
    return tuple(candidates)


def inspect_sarah_portrait(path: Path | str) -> dict[str, object]:
    """Validate the exact bounded runtime derivative and explain any drift."""
    candidate = Path(path)
    report: dict[str, object] = {
        "path": str(candidate),
        "valid": False,
        "reason": "missing",
        "bytes": 0,
        "sha256": "",
        "dimensions": None,
        "format": "",
    }
    if not candidate.is_file():
        return report
    try:
        payload = candidate.read_bytes()
    except OSError as error:
        report["reason"] = f"unreadable:{type(error).__name__}"
        return report
    report["bytes"] = len(payload)
    report["sha256"] = hashlib.sha256(payload).hexdigest()
    try:
        with Image.open(candidate) as image:
            image.load()
            report["dimensions"] = tuple(image.size)
            report["format"] = str(image.format or "")
    except Exception as error:
        report["reason"] = f"invalid_image:{type(error).__name__}"
        return report

    drift: list[str] = []
    if report["bytes"] != SARAH_PORTRAIT_BYTES:
        drift.append("size")
    if report["sha256"] != SARAH_PORTRAIT_SHA256:
        drift.append("sha256")
    if report["dimensions"] != SARAH_PORTRAIT_DIMENSIONS:
        drift.append("dimensions")
    if report["format"] != "PNG":
        drift.append("format")
    report["valid"] = not drift
    report["reason"] = "ok" if not drift else "drift:" + ",".join(drift)
    return report


def resolve_sarah_portrait(
        *,
        source_root: Path | str | None = None,
        bundle_root: Path | str | None = None) -> Path | None:
    """Resolve only a hash-, size-, and dimension-valid runtime portrait."""
    for candidate in packaged_resource_candidates(
            SARAH_PORTRAIT_RELATIVE_PATH,
            source_root=source_root,
            bundle_root=bundle_root):
        if inspect_sarah_portrait(candidate)["valid"]:
            return candidate
    return None


def portrait_packaged_self_test(
        *,
        source_root: Path | str | None = None,
        bundle_root: Path | str | None = None) -> dict[str, object]:
    """Fail a source/package self-test if the approved runtime derivative is absent or drifted."""
    resolved = resolve_sarah_portrait(source_root=source_root, bundle_root=bundle_root)
    if resolved is None:
        raise RuntimeError(
            "The exact Sarah runtime portrait is missing, corrupt, or does not match its approved hash."
        )
    report = inspect_sarah_portrait(resolved)
    if not report["valid"]:
        raise RuntimeError(f"Sarah runtime portrait validation failed: {report['reason']}")
    return report


# Windows notification area / hidden-icons support keeps Sarah active when her windows are hidden.
class SarahApp:
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("Sarah Morgan Windows Companion")
        self.root.geometry("1080x760")
        self.root.minsize(820, 580)
        self.db = SarahDatabase()
        self.model = ModelClient(self.db)
        self.research = TavilyResearch()
        self.voice = ElevenLabsVoice()
        self.sync_server = SarahSyncServer(self.db)
        self.tasks: queue.Queue[tuple[str, object]] = queue.Queue()
        self.answer_requests: queue.Queue[object] = queue.Queue()
        self.voice_requests: queue.Queue[object] = queue.Queue()
        self.turn_sequence = 0
        self.turn_in_flight = False
        self.speaking = False
        self.voice_generation = 0
        self._voice_control_lock = threading.Lock()
        self._voice_cancel_reasons: dict[int, str] = {}
        self._active_voice_process: subprocess.Popen | None = None
        self._active_voice_generation: int | None = None
        self.research_lock = threading.Lock()
        self.research_in_flight = False
        self.research_generation = 0
        self.last_interaction_at = time.monotonic()
        self.next_background_research_at = self.last_interaction_at + 120.0
        self._portrait_load_attempted = False
        self._portrait_photo = None
        self._portrait_base_image = None
        self._portrait_renderer = None
        self._portrait_asset_path: Path | None = None
        self._avatar_motion = AvatarMotionModel()
        self._avatar_lip_sync_receipt: dict[str, object] = {
            "mode": "idle",
            "physical_visual_acceptance": "pending",
        }
        self.tray = None
        self.corner: tk.Toplevel | None = None
        self._build_ui()
        threading.Thread(target=self._answer_loop, daemon=True).start()
        threading.Thread(target=self._voice_loop, daemon=True).start()
        self.root.after(500, self._maybe_onboard)
        self._start_corner()
        self._start_tray()
        self.root.after(100, self._poll_tasks)
        self.root.after(4000, self._idle_research_tick)
        self.root.protocol("WM_DELETE_WINDOW", self.hide_to_tray)

    def _maybe_onboard(self):
        profile = self.db.active_profile()
        if not needs_owner_identity_confirmation(profile):
            return
        name = simpledialog.askstring(
            "Meet Sarah",
            "What name should Sarah use on this computer? You may also cancel and import an encrypted owner backup later.",
            parent=self.root,
        )
        if not name:
            return
        age_text = simpledialog.askstring("Age", "Your age or birth year (optional):", parent=self.root) or ""
        age = None
        try:
            number = int(age_text.strip())
            age = time.localtime().tm_year - number if number >= 1900 else number
            if age < 1 or age > 120:
                age = None
        except Exception:
            pass
        hometown = simpledialog.askstring(
            "Home or approximate area",
            "City/state/country (optional; nearby proactive research stays off until separately enabled):",
            parent=self.root,
        ) or ""
        interests = simpledialog.askstring(
            "Interests",
            "Interests Sarah may use for trip ideas and trivia (optional):",
            parent=self.root,
        ) or ""
        self.db.rename_active_profile(name.strip())
        self.db.ensure_profile(
            name.strip(), age, hometown.strip(), interests.strip(), True,
            age_known=age is not None,
        )
        self.status.set("Sarah's Windows profile is ready. Device sync remains off; encrypted owner backups are available.")

    def _build_ui(self):
        style = ttk.Style(self.root)
        try: style.theme_use("vista")
        except tk.TclError: pass
        header = tk.Frame(self.root, bg="#183448", height=76)
        header.pack(fill="x")
        tk.Label(header, text="Sarah Morgan", fg="white", bg="#183448", font=("Segoe UI", 24, "bold")).pack(side="left", padx=20, pady=15)
        configured = bool(runtime_setting("SARAH_MODEL_BACKEND_URL", root=self.db.root))
        initial_route = (
            "Connected route configured • verifying with the next message"
            if configured else "Offline mind ready • no connected route configured"
        )
        self.status = tk.StringVar(value=initial_route + " • device sync setup required")
        tk.Label(header, textvariable=self.status, fg="#d9edf7", bg="#183448", font=("Segoe UI", 10)).pack(side="right", padx=20)
        ttk.Button(header, text="Connection", command=self.configure_online_services).pack(side="right", padx=6, pady=15)
        self.tabs = ttk.Notebook(self.root); self.tabs.pack(fill="both", expand=True, padx=10, pady=10)
        self.chat_tab = ttk.Frame(self.tabs); self.discovery_tab = ttk.Frame(self.tabs); self.trip_tab = ttk.Frame(self.tabs); self.photo_tab = ttk.Frame(self.tabs); self.device_tab = ttk.Frame(self.tabs); self.activity_tab = ttk.Frame(self.tabs)
        self.tabs.add(self.chat_tab, text="Talk with Sarah"); self.tabs.add(self.discovery_tab, text="Discoveries"); self.tabs.add(self.trip_tab, text="Trips"); self.tabs.add(self.photo_tab, text="Photos"); self.tabs.add(self.device_tab, text="Devices & backup"); self.tabs.add(self.activity_tab, text="Factual activity")
        self._build_chat(); self._build_discoveries(); self._build_trips(); self._build_photos(); self._build_devices(); self._build_activity()

    def _build_chat(self):
        self.chat = tk.Text(self.chat_tab, wrap="word", font=("Segoe UI", 12), bg="#fbf8f1", padx=16, pady=16, state="disabled")
        self.chat.pack(fill="both", expand=True, padx=8, pady=8)
        row = ttk.Frame(self.chat_tab); row.pack(fill="x", padx=8, pady=(0,8))
        self.entry = ttk.Entry(row, font=("Segoe UI", 12)); self.entry.pack(side="left", fill="x", expand=True); self.entry.bind("<Return>", lambda _e: self.send())
        self.send_button = ttk.Button(row, text="Send", command=self.send)
        self.send_button.pack(side="left", padx=5)
        self.calm_button = ttk.Button(row, text="Calm choices", command=lambda: self._submit_text("I am stressed and I need calm choices"))
        self.calm_button.pack(side="left")
        self.stop_voice_button = ttk.Button(row, text="Stop voice", command=self.stop_voice)
        self.stop_voice_button.pack(side="left", padx=(5, 0))
        self._append(
            "Sarah",
            "I’m here. We can plan a trip, talk about ordinary life, organize your travel photos, or do absolutely nothing travel-related.",
            "LOCAL_TOOL_RESULT",
        )

    def _append(self, who: str, text: str, route: str = ""):
        source = f"\nSource: {route_label(route)}" if route else ""
        self.chat.configure(state="normal"); self.chat.insert("end", f"{who}\n{text}{source}\n\n"); self.chat.see("end"); self.chat.configure(state="disabled")

    def configure_online_services(self):
        """Save the revocable team connection per user, never inside the EXE."""
        current = load_runtime_config(self.db.root)
        backend = simpledialog.askstring(
            "Sarah online setup",
            "Secure Sarah connection address (normally already included; blank uses the included address):",
            initialvalue=current.get("SARAH_MODEL_BACKEND_URL", ""),
            parent=self.root,
        )
        if backend is None:
            return
        token = simpledialog.askstring(
            "Sarah online setup",
            "Connection password. Leave blank to keep the saved value; type CLEAR to remove it:",
            show="*",
            parent=self.root,
        )
        updated = dict(current)
        if safe_text(backend):
            updated["SARAH_MODEL_BACKEND_URL"] = safe_text(backend)
            updated.setdefault("SARAH_MODEL_PROVIDER", "workers-ai")
            updated.setdefault("SARAH_MODEL_ID", "@cf/google/gemma-4-26b-a4b-it")
        else:
            updated.pop("SARAH_MODEL_BACKEND_URL", None)
        self._update_secret(updated, "SARAH_MODEL_BACKEND_TOKEN", token)
        try:
            save_runtime_config(updated, self.db.root)
        except ValueError as error:
            messagebox.showerror("Sarah online setup", str(error), parent=self.root)
            return
        self.voice = ElevenLabsVoice(self.db.root)
        self.research = TavilyResearch()
        self.status.set("Sarah’s secure connection settings were saved for this Windows account")

    @staticmethod
    def _update_secret(settings: dict[str, str], name: str, value: str | None) -> None:
        normalized = safe_text(value)
        if normalized.upper() == "CLEAR":
            settings.pop(name, None)
        elif normalized:
            settings[name] = normalized

    def send(self):
        text = self.entry.get().strip()
        if not text: return
        self.entry.delete(0, "end"); self._submit_text(text)

    def _submit_text(self, text: str):
        if getattr(self, "turn_in_flight", False):
            self.status.set("Sarah is finishing the current reply; the next message can be sent when it appears.")
            return
        # Owner conversation preempts any opted-in idle research. A request
        # already inside the HTTP stack is bounded to a short timeout, and its
        # generation check prevents any stale result from being committed.
        self.research_generation += 1
        voice_generation = self._begin_voice_generation("superseded_by_new_owner_turn")
        self.turn_in_flight = True
        self._set_composer_enabled(False)
        self.last_interaction_at = time.monotonic()
        submitted_at = int(time.time() * 1000)
        profile = self.db.active_profile()
        fixed = corrected_name(text)
        if fixed and (text.lower().startswith(("no", "actually", "sorry", "wait")) or fixed.lower() == profile.get("name", "").lower()):
            self.db.rename_active_profile(fixed)
        profile = self.db.active_profile()
        person_id = profile.get("person_id") or self.db.get_setting("active_person_id")
        self.turn_sequence += 1
        turn_id = f"windows-turn-{self.turn_sequence:08d}"
        self._append(profile.get("name", "You"), text)
        self.db.add_message("user", text, person_id=person_id, route="USER_INPUT")
        self.db.learn_adaptive_context(text, person_id=person_id)
        self.status.set("Sarah is thinking…")
        self.answer_requests.put((text, submitted_at, person_id, turn_id, voice_generation))

    def _answer_loop(self):
        while True:
            request = self.answer_requests.get()
            if request is None:
                return
            self._answer(*request)

    def _answer(
        self,
        text: str,
        submitted_at: int | None = None,
        person_id: str | None = None,
        turn_id: str = "",
        voice_generation: int | None = None,
    ):
        try: response = self.model.respond(text, turn_submitted_at=submitted_at, person_id=person_id)
        except Exception: response = ChannelResponse("The online reply failed, so I’m continuing from saved information for this turn. I have not started a search or background job.", "Sarah is preserving continuity after a tool error.", "The connected response failed; no external action was completed.", "RUNTIME_STATE_ERROR", True, "ONLINE_FAILED_FELL_BACK_OFFLINE")
        self.db.add_message("assistant", response.spoken, person_id=person_id, route=response.route)
        self.db.add_mind_event(response, "windows-chat:" + response.route, person_id=person_id)
        self.tasks.put(("reply", {
            "response": response,
            "person_id": person_id,
            "turn_id": turn_id,
            "voice_generation": voice_generation,
        }))

    def _poll_tasks(self):
        try:
            while True:
                kind, payload = self.tasks.get_nowait()
                if kind == "reply":
                    item = dict(payload)
                    response: ChannelResponse = item["response"]
                    person_id = item.get("person_id")
                    if person_id != self.db.get_setting("active_person_id"):
                        self.turn_in_flight = False
                        self._set_composer_enabled(True)
                        self.status.set("A reply completed in another profile and stayed in that profile's history.")
                        continue
                    self._append("Sarah", response.spoken, response.route)
                    self.turn_in_flight = False
                    self._set_composer_enabled(True)
                    self.status.set("Ready • " + route_label(response.route) + " • only SPOKEN is visible")
                    self._speak(
                        response.spoken,
                        person_id,
                        item.get("turn_id", ""),
                        item.get("voice_generation"),
                    ); self.refresh_activity()
                elif kind == "research": self.status.set(str(payload)); self.refresh_discoveries()
                elif kind == "voice_route": self.status.set(str(payload))
        except queue.Empty: pass
        self.root.after(100, self._poll_tasks)

    def _set_composer_enabled(self, enabled: bool) -> None:
        state = "normal" if enabled else "disabled"
        for control_name in ("entry", "send_button", "calm_button"):
            control = getattr(self, control_name, None)
            if control is not None:
                control.configure(state=state)
        if enabled and hasattr(self, "entry"):
            self.entry.focus_set()

    def _ensure_voice_control(self) -> None:
        if not hasattr(self, "_voice_control_lock"):
            self._voice_control_lock = threading.Lock()
        if not hasattr(self, "voice_generation"):
            self.voice_generation = 0
        if not hasattr(self, "_voice_cancel_reasons"):
            self._voice_cancel_reasons = {}
        if not hasattr(self, "_active_voice_process"):
            self._active_voice_process = None
        if not hasattr(self, "_active_voice_generation"):
            self._active_voice_generation = None

    def _current_voice_generation(self) -> int:
        self._ensure_voice_control()
        with self._voice_control_lock:
            return int(self.voice_generation)

    def _begin_voice_generation(self, reason: str) -> int:
        """Supersede only this app's queued/active speech and return a new turn generation."""
        self._ensure_voice_control()
        process = None
        with self._voice_control_lock:
            previous = int(self.voice_generation)
            self._voice_cancel_reasons[previous] = safe_text(reason) or "superseded"
            self.voice_generation = previous + 1
            process = self._active_voice_process
        self._terminate_exact_voice_process(process)
        avatar_motion = getattr(self, "_avatar_motion", None)
        if avatar_motion is not None:
            avatar_motion.stop_speaking(previous)
        return int(self.voice_generation)

    def stop_voice(self) -> None:
        self._begin_voice_generation("stopped_by_owner")
        if hasattr(self, "status"):
            self.status.set("Voice stopped. Text chat remains ready.")

    def _voice_generation_is_current(self, generation: int) -> bool:
        return int(generation) == self._current_voice_generation()

    def _voice_cancel_reason(self, generation: int) -> str:
        self._ensure_voice_control()
        with self._voice_control_lock:
            return self._voice_cancel_reasons.get(int(generation), "superseded")

    def _voice_request_is_current(self, person_id: str | None, generation: int) -> bool:
        return self._voice_profile_is_current(person_id) and self._voice_generation_is_current(generation)

    def _voice_request_failure_reason(self, person_id: str | None, generation: int) -> str:
        if not self._voice_profile_is_current(person_id):
            return "profile_changed"
        return self._voice_cancel_reason(generation)

    @staticmethod
    def _terminate_exact_voice_process(process) -> None:
        if process is None:
            return
        try:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=2)
        except (OSError, ProcessLookupError):
            pass

    def _run_cancellable_voice_process(self, command: list[str], generation: int) -> tuple[bool, str]:
        """Run only the exact child we create; a new owner turn can terminate it."""
        if not self._voice_generation_is_current(generation):
            return False, self._voice_cancel_reason(generation)
        process = subprocess.Popen(
            command,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        self._ensure_voice_control()
        with self._voice_control_lock:
            if int(generation) != int(self.voice_generation):
                stale = True
            else:
                stale = False
                self._active_voice_process = process
                self._active_voice_generation = int(generation)
        if stale:
            self._terminate_exact_voice_process(process)
            return False, self._voice_cancel_reason(generation)
        try:
            while process.poll() is None:
                if not self._voice_generation_is_current(generation):
                    self._terminate_exact_voice_process(process)
                    return False, self._voice_cancel_reason(generation)
                time.sleep(0.05)
            return process.returncode == 0, ""
        finally:
            with self._voice_control_lock:
                if self._active_voice_process is process:
                    self._active_voice_process = None
                    self._active_voice_generation = None

    def _play_audio_file(self, audio_path: Path | str, generation: int) -> tuple[bool, str]:
        self._begin_avatar_audio_file(audio_path, generation)
        try:
            if sys.platform.startswith("win"):
                escaped = str(Path(audio_path).resolve()).replace("'", "''")
                script = (
                    "$ErrorActionPreference='Stop';"
                    "Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; "
                    "public static class SarahMci { [DllImport(\"winmm.dll\", CharSet=CharSet.Unicode)] "
                    "public static extern int mciSendString(string command, System.Text.StringBuilder result, "
                    "int resultLength, IntPtr callback); }';"
                    f"$p='{escaped}';$a='sarahvoice';"
                    "$o=[SarahMci]::mciSendString(('open \"'+$p+'\" alias '+$a),$null,0,[IntPtr]::Zero);"
                    "if($o -ne 0){exit 21};"
                    "try{$r=[SarahMci]::mciSendString(('play '+$a+' wait'),$null,0,[IntPtr]::Zero);"
                    "if($r -ne 0){exit 22}}finally{[void][SarahMci]::mciSendString(('close '+$a),$null,0,[IntPtr]::Zero)}"
                )
                return self._run_cancellable_voice_process(
                    ["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                    generation,
                )
            if playsound:
                playsound(str(audio_path), block=True)
                return True, ""
            return False, ""
        finally:
            self._stop_avatar_speaking(generation)

    def _ensure_avatar_motion(self) -> AvatarMotionModel:
        motion = getattr(self, "_avatar_motion", None)
        if motion is None:
            motion = AvatarMotionModel()
            self._avatar_motion = motion
        return motion

    def _begin_avatar_audio_file(self, audio_path: Path | str, generation: int) -> None:
        started = time.monotonic()
        envelope = decode_audio_envelope(audio_path)
        self._ensure_avatar_motion().start_audio(envelope, generation)
        self._avatar_lip_sync_receipt = {
            "mode": "decoded_audio_envelope" if envelope.decoded else "speaking_activity_fallback",
            "decoder_route": envelope.route,
            "decode_reason": envelope.reason,
            "envelope_frames": len(envelope.values),
            "envelope_duration_ms": int(envelope.duration_seconds * 1000),
            "analysis_ms": int((time.monotonic() - started) * 1000),
            "physical_visual_acceptance": "pending",
        }

    def _begin_avatar_fallback_speaking(self, generation: int) -> None:
        self._ensure_avatar_motion().start_speaking_fallback(generation)
        self._avatar_lip_sync_receipt = {
            "mode": "speaking_activity_fallback",
            "decoder_route": "no_audio_file",
            "decode_reason": "windows_system_speech_exposes_no_pcm_to_sarah",
            "envelope_frames": 0,
            "envelope_duration_ms": 0,
            "analysis_ms": 0,
            "physical_visual_acceptance": "pending",
        }

    def _stop_avatar_speaking(self, generation: int) -> None:
        self._ensure_avatar_motion().stop_speaking(generation)

    def _speak(
        self,
        text: str,
        person_id: str | None = None,
        turn_id: str = "",
        voice_generation: int | None = None,
    ):
        generation = self._current_voice_generation() if voice_generation is None else voice_generation
        self.voice_requests.put((text, person_id, turn_id, generation))

    def _voice_loop(self):
        while True:
            request = self.voice_requests.get()
            if request is None:
                return
            text, person_id, turn_id, voice_generation = request
            if person_id and person_id != self.db.get_setting("active_person_id"):
                self._record_voice_receipt(
                    "NOT_ATTEMPTED_PROFILE_CHANGED", "TEXT_ONLY",
                    "Voice stayed silent because the active profile changed before playback.",
                    int(time.time() * 1000), 0, 0, 0, 0,
                    "profile_changed", len(text), person_id=person_id, turn_id=turn_id,
                )
                continue
            if not self._voice_generation_is_current(voice_generation):
                self._record_voice_receipt(
                    "NOT_ATTEMPTED_SUPERSEDED", "TEXT_ONLY",
                    "Voice stayed silent because a newer owner turn or Stop voice superseded it.",
                    int(time.time() * 1000), 0, 0, 0, 0,
                    self._voice_cancel_reason(voice_generation), len(text),
                    person_id=person_id, turn_id=turn_id,
                )
                continue
            self._speak_worker(
                text,
                person_id=person_id,
                turn_id=turn_id,
                voice_generation=voice_generation,
            )

    def _speak_worker(
        self,
        text: str,
        person_id: str | None = None,
        turn_id: str = "",
        voice_generation: int | None = None,
    ):
        generation = self._current_voice_generation() if voice_generation is None else voice_generation
        self.speaking = True
        requested_at = int(time.time() * 1000)
        synthesis_start = 0
        synthesis_end = 0
        playback_start = 0
        playback_end = 0
        attempted = "NONE"
        actual = "TEXT_ONLY"
        outcome = "Voice playback was not available on this platform."
        failure_reason = ""
        cache_hit = False
        cache_key = ""
        voice_model = ""
        voice_id = ""
        route_identity = ""
        response_content_type = ""
        route_receipt = ""
        self._avatar_lip_sync_receipt = {
            "mode": "not_started",
            "physical_visual_acceptance": "pending",
        }
        try:
            if self.voice.configured and (sys.platform.startswith("win") or playsound):
                attempted = "ELEVENLABS"
                try:
                    synthesis_start = int(time.time() * 1000)
                    audio_path = self.voice.synthesize(
                        text,
                        should_cancel=lambda: not self._voice_request_is_current(person_id, generation),
                        total_budget_seconds=15.0,
                    )
                    cache_hit = bool(getattr(self.voice, "last_cache_hit", False))
                    cache_key = str(getattr(self.voice, "last_cache_key", ""))
                    voice_model = str(getattr(self.voice, "model", ""))
                    voice_id = str(getattr(self.voice, "voice_id", ""))
                    route_identity = str(getattr(self.voice, "last_route_identity", ""))
                    response_content_type = str(getattr(self.voice, "last_content_type", ""))
                    route_receipt = str(getattr(self.voice, "last_route_receipt", ""))
                    synthesis_end = int(time.time() * 1000)
                    if not self._voice_request_is_current(person_id, generation):
                        failure_reason = self._voice_request_failure_reason(person_id, generation)
                        outcome = "Voice was synthesized but stayed silent because its person or owner turn was superseded."
                        return
                    playback_start = int(time.time() * 1000)
                    playback_ok, playback_reason = self._play_audio_file(audio_path, generation)
                    playback_end = int(time.time() * 1000)
                    if playback_reason:
                        failure_reason = playback_reason
                        outcome = "Voice stopped because a newer owner turn or Stop voice superseded it."
                        return
                    if not playback_ok:
                        raise RuntimeError("windows_audio_player_failed")
                    actual = "ELEVENLABS"
                    outcome = "Voice completed with Sarah's configured ElevenLabs route."
                except Exception as error:
                    if synthesis_start and not synthesis_end:
                        synthesis_end = int(time.time() * 1000)
                    failure_reason = type(error).__name__
                    if not self._voice_request_is_current(person_id, generation):
                        failure_reason = self._voice_request_failure_reason(person_id, generation)
                        outcome = "Voice stayed silent because its person or owner turn was superseded before fallback playback."
                        return
                    playback_start = int(time.time() * 1000)
                    fallback_ok, fallback_reason = self._speak_windows_fallback(text, generation)
                    playback_end = int(time.time() * 1000)
                    if fallback_reason:
                        failure_reason = fallback_reason
                        outcome = "Voice stopped because a newer owner turn or Stop voice superseded it."
                        return
                    actual = "WINDOWS_SYSTEM_SPEECH" if fallback_ok else "TEXT_ONLY"
                    outcome = ("ElevenLabs failed; the explicit Windows offline voice fallback completed."
                               if fallback_ok else
                               "ElevenLabs failed and Windows offline voice was unavailable; text remained available.")
            elif self.voice.configured and sys.platform.startswith("win"):
                attempted = "ELEVENLABS_NOT_ATTEMPTED_NO_MP3_PLAYER"
                failure_reason = "mp3_player_unavailable"
                if not self._voice_request_is_current(person_id, generation):
                    failure_reason = self._voice_request_failure_reason(person_id, generation)
                    outcome = "Voice stayed silent because its person or owner turn was superseded before fallback playback."
                    return
                playback_start = int(time.time() * 1000)
                fallback_ok, fallback_reason = self._speak_windows_fallback(text, generation)
                playback_end = int(time.time() * 1000)
                if fallback_reason:
                    failure_reason = fallback_reason
                    outcome = "Voice stopped because a newer owner turn or Stop voice superseded it."
                    return
                actual = "WINDOWS_SYSTEM_SPEECH" if fallback_ok else "TEXT_ONLY"
                outcome = ("ElevenLabs playback support was unavailable; the explicit Windows offline voice fallback completed."
                           if fallback_ok else
                           "ElevenLabs playback support and Windows offline voice were unavailable; text remained available.")
            elif sys.platform.startswith("win"):
                attempted = "WINDOWS_SYSTEM_SPEECH"
                if not self._voice_request_is_current(person_id, generation):
                    failure_reason = self._voice_request_failure_reason(person_id, generation)
                    outcome = "Voice stayed silent because its person or owner turn was superseded before playback."
                    return
                playback_start = int(time.time() * 1000)
                fallback_ok, fallback_reason = self._speak_windows_fallback(text, generation)
                playback_end = int(time.time() * 1000)
                if fallback_reason:
                    failure_reason = fallback_reason
                    outcome = "Voice stopped because a newer owner turn or Stop voice superseded it."
                    return
                actual = "WINDOWS_SYSTEM_SPEECH" if fallback_ok else "TEXT_ONLY"
                outcome = ("Voice completed with the Windows offline route; ElevenLabs is not configured."
                           if fallback_ok else
                           "Windows offline voice was unavailable; text remained available.")
        finally:
            self.speaking = False
            self._record_voice_receipt(
                attempted, actual, outcome, requested_at, synthesis_start,
                synthesis_end, playback_start, playback_end, failure_reason,
                len(text),
                cache_hit, cache_key, voice_model, voice_id, route_identity,
                response_content_type,
                person_id, turn_id, route_receipt,
            )

    def _record_voice_receipt(
        self,
        attempted: str,
        actual: str,
        outcome: str,
        requested_at: int,
        synthesis_start: int,
        synthesis_end: int,
        playback_start: int,
        playback_end: int,
        failure_reason: str,
        character_count: int,
        cache_hit: bool = False,
        cache_key: str = "",
        voice_model: str = "",
        voice_id: str = "",
        route_identity: str = "",
        response_content_type: str = "",
        person_id: str | None = None,
        turn_id: str = "",
        route_receipt: str = "",
    ) -> None:
        receipt = {
            "attempted_route": attempted,
            "actual_route": actual,
            "outcome": outcome,
            "failure_reason": failure_reason,
            "requested_at": requested_at,
            "synthesis_start": synthesis_start,
            "synthesis_end": synthesis_end,
            "playback_start": playback_start,
            "playback_end": playback_end,
            "playback_start_semantics": "PROCESS_ATTEMPT_NOT_AUDIBLE_PROOF",
            "audible_start_confirmed": False,
            "generation_latency_ms": max(0, synthesis_end - synthesis_start) if synthesis_start else 0,
            "total_voice_latency_ms": max(0, playback_end - requested_at) if playback_end else 0,
            "character_count": character_count,
            "cache_hit": cache_hit,
            "cache_key": cache_key,
            "voice_model": voice_model,
            "voice_id": voice_id,
            "route_identity": route_identity,
            "response_content_type": response_content_type,
            "route_receipt": route_receipt,
            "avatar_animation": dict(getattr(self, "_avatar_lip_sync_receipt", {})),
            "person_id": person_id or "",
            "turn_id": turn_id,
            "recorded_at": int(time.time() * 1000),
        }
        if hasattr(self, "db"):
            receipt_person_id = person_id or self.db.get_setting("active_person_id")
            self.db.set_setting(f"voice_route_receipt:{receipt_person_id}", json.dumps(receipt, sort_keys=True))
        superseded = failure_reason in {
            "superseded",
            "superseded_by_new_owner_turn",
            "stopped_by_owner",
            "profile_changed",
            "profile_archive_restore",
            "application_exit",
        }
        if hasattr(self, "tasks") and self._voice_profile_is_current(person_id) and not superseded:
            self.tasks.put(("voice_route", outcome))

    def _voice_profile_is_current(self, person_id: str | None) -> bool:
        return not person_id or person_id == self.db.get_setting("active_person_id")

    def _speak_windows_fallback(self, text: str, generation: int) -> tuple[bool, str]:
        if not sys.platform.startswith("win"):
            return False, ""
        escaped = text.replace("'", "''")
        self._begin_avatar_fallback_speaking(generation)
        try:
            return self._run_cancellable_voice_process(
                [
                    "powershell", "-NoProfile", "-NonInteractive", "-Command",
                    f"Add-Type -AssemblyName System.Speech; $s=New-Object System.Speech.Synthesis.SpeechSynthesizer; $s.Rate=-1; $s.Speak('{escaped}')",
                ],
                generation,
            )
        finally:
            self._stop_avatar_speaking(generation)

    def _build_discoveries(self):
        bar=ttk.Frame(self.discovery_tab);bar.pack(fill="x",padx=8,pady=8);ttk.Button(bar,text="Research now",command=self.research_now).pack(side="left");ttk.Button(bar,text="Nearby permission",command=self.set_nearby_permission).pack(side="left",padx=5);self.background_research_button=ttk.Button(bar,command=self.toggle_background_research);self.background_research_button.pack(side="left",padx=5);ttk.Button(bar,text="Connection status",command=self.show_sponsors).pack(side="left",padx=5)
        self.discovery_list=tk.Listbox(self.discovery_tab,font=("Segoe UI",11));self.discovery_list.pack(fill="both",expand=True,padx=8,pady=8);self.discovery_list.bind("<Double-1>",self.open_discovery);self.discovery_rows=[];self.refresh_discoveries()
        self._refresh_background_research_label()

    def _refresh_background_research_label(self):
        if hasattr(self, "background_research_button"):
            state = "on" if self.db.background_research_enabled() else "off"
            self.background_research_button.configure(text=f"Background research: {state}")

    def toggle_background_research(self):
        if self.db.background_research_enabled():
            self.db.set_background_research_enabled(False)
            self.research_generation += 1
            self.status.set("Background research is off for this profile.")
        else:
            profile = self.db.active_profile()
            if not bool(profile.get("memory_consent", 0)):
                messagebox.showinfo(
                    "Background research unavailable",
                    "This profile has not allowed Sarah to save personal memories, so background research remains off.",
                )
                return
            approved = messagebox.askyesno(
                "Allow background research?",
                "Allow Sarah to run a small source-backed research job for this profile about every six hours while the Windows companion is running? You can turn this off here at any time.",
            )
            if approved:
                self.db.set_background_research_enabled(True)
                self.status.set("Background research is on for this profile; each run is bounded and source recorded.")
        self._refresh_background_research_label()

    def set_nearby_permission(self):
        enabled = messagebox.askyesno(
            "Nearby discoveries",
            "Allow Sarah to use an approximate area you type for source-backed nearby events and places? She will not use precise GPS or run nearby research when this is off.",
        )
        if not enabled:
            self.db.set_nearby_enabled(False)
            self.status.set("Nearby proactive discovery is off.")
            return
        area = simpledialog.askstring(
            "Approximate current area",
            "Current city, state or area Sarah may use for this profile. This will not replace the saved hometown:",
            initialvalue=self.db.current_area() or "",
        )
        if area:
            self.db.set_current_area(area.strip())
            self.db.set_nearby_enabled(True)
            self.status.set("Nearby proactive discovery is on for the temporary approximate current area; hometown was unchanged.")

    def research_now(self):
        if not self.research.configured: messagebox.showinfo("Current-source connection unavailable","Sarah’s protected current-source connection is not available. No search or research was claimed.");return
        if not bool(self.db.active_profile().get("memory_consent", 0)):
            messagebox.showinfo("Research unavailable","This profile has not allowed Sarah to save personal research results. No search was started.");return
        if not self._queue_research("owner_requested"):
            self.status.set("One bounded research job is already running.")

    def _queue_research(self, trigger: str) -> bool:
        with self.research_lock:
            if self.research_in_flight:
                return False
            self.research_in_flight = True
        profile = dict(self.db.active_profile())
        person_id = profile.get("person_id") or self.db.get_setting("active_person_id")
        generation = self.research_generation
        threading.Thread(
            target=self._research_worker,
            args=(trigger, person_id, profile, generation),
            daemon=True,
        ).start()
        return True

    def _research_worker(self, trigger: str, person_id: str | None = None, profile: dict | None = None, generation: int | None = None):
        profile=dict(profile or self.db.active_profile());person_id=person_id or profile.get("person_id") or self.db.get_setting("active_person_id");trips=self.db.list_rows("trips",person_id=person_id,limit=20);nearby=self.db.nearby_enabled(person_id);added=0;seen=0;started_at=int(time.time()*1000)
        generation = getattr(self, "research_generation", 0) if generation is None else generation
        profile["current_area"]=self.db.current_area(person_id=person_id)
        queries=discovery_queries(profile,trips,nearby)[:2]
        receipt={"trigger":trigger,"provider":"Tavily","status":"RUNNING","profile_id":person_id,"query_count":len(queries),"source_result_count":0,"saved_count":0,"started_at":started_at,"completed_at":0,"failure_class":""}
        self.db.set_setting(f"research_receipt:{person_id}",json.dumps(receipt,sort_keys=True))
        try:
            for query in queries:
                if not self._research_still_authorized(trigger, person_id, generation):
                    raise InterruptedError("research authorization changed")
                for result in self.research.search(query,4):
                    if not self._research_still_authorized(trigger, person_id, generation):
                        raise InterruptedError("research authorization changed")
                    seen += 1
                    added += self.db.add_discovery(result["title"],result["summary"],result["url"],query,person_id=person_id)
            receipt={"trigger":trigger,"provider":"Tavily","status":"SUCCEEDED","profile_id":person_id,"query_count":len(queries),"source_result_count":seen,"saved_count":added,"started_at":started_at,"completed_at":int(time.time()*1000),"failure_class":""}
            self.db.set_setting(f"research_receipt:{person_id}",json.dumps(receipt,sort_keys=True))
            self.tasks.put(("research",f"Research finished • {len(queries)} bounded query/queries • {seen} source receipt(s) • {added} new saved match(es)"))
        except Exception as failure:
            cancelled = isinstance(failure, InterruptedError)
            receipt={"trigger":trigger,"provider":"Tavily","status":"CANCELLED" if cancelled else "FAILED","profile_id":person_id,"query_count":len(queries),"source_result_count":seen,"saved_count":added,"started_at":started_at,"completed_at":int(time.time()*1000),"failure_class":type(failure).__name__}
            self.db.set_setting(f"research_receipt:{person_id}",json.dumps(receipt,sort_keys=True))
            self.tasks.put(("research","Research stopped because its profile or permission changed." if cancelled else "Research did not complete; no unsupported result was claimed."))
        finally:
            with self.research_lock:
                self.research_in_flight=False

    def _research_still_authorized(self, trigger: str, person_id: str, generation: int) -> bool:
        if generation != getattr(self, "research_generation", 0):
            return False
        if person_id != self.db.get_setting("active_person_id"):
            return False
        profile = self.db.profile(person_id)
        if not profile or not bool(profile.get("memory_consent", 0)):
            return False
        if trigger == "profile_opted_in_idle_background" and not self.db.background_research_enabled(person_id):
            return False
        return True

    def refresh_discoveries(self):
        self.discovery_rows=self.db.list_rows("discoveries",limit=100);self.discovery_list.delete(0,"end")
        for row in self.discovery_rows:self.discovery_list.insert("end",f"{row['title']} — {row['source']}")
    def open_discovery(self,_event=None):
        sel=self.discovery_list.curselection();
        if sel:webbrowser.open(self.discovery_rows[sel[0]]["url"])
    def show_sponsors(self):
        config=load_runtime_config(self.db.root)
        voice=("available; this status view does not perform a live request"
               if self.voice.configured else
               "not configured; Windows offline speech remains the explicit fallback")
        research=("available; results are stored with source and time receipts"
                  if self.research.configured else
                  "not configured; no public research is claimed")
        stay22=("backend configured; no booking is claimed until a verified tool receipt exists"
                if safe_text(config.get("SARAH_STAY22_BACKEND_URL")) else
                "not configured in this Windows build")
        messagebox.showinfo(
            "Sarah connection status",
            f"Natural online voice: {voice}.\nCurrent-source search: {research}.\nHotel handoff: {stay22}.\nGmail: not connected by this program.\n\nNo search, handoff, email, reservation, or booking is called completed without a verified receipt.",
        )

    def _build_trips(self):
        form=ttk.Frame(self.trip_tab);form.pack(fill="x",padx=8,pady=8);self.trip_title=ttk.Entry(form);self.trip_dest=ttk.Entry(form);self.trip_title.insert(0,"My trip");self.trip_title.pack(side="left",fill="x",expand=True);self.trip_dest.pack(side="left",fill="x",expand=True,padx=5);ttk.Button(form,text="Save planned trip",command=self.add_trip).pack(side="left")
        self.trip_list=tk.Listbox(self.trip_tab,font=("Segoe UI",11));self.trip_list.pack(fill="both",expand=True,padx=8,pady=8);self.refresh_trips()
    def add_trip(self):
        dest=self.trip_dest.get().strip();
        if not dest:return
        self.db.add_trip(self.trip_title.get().strip() or "My trip",dest);self.refresh_trips();self.status.set("Trip saved. Sarah can now prepare interest-aware research.")
    def refresh_trips(self):
        self.trip_list.delete(0,"end")
        for row in self.db.list_rows("trips",limit=100):self.trip_list.insert("end",f"{row['status']}: {row['destination']} — {row['title']}")

    def _build_photos(self):
        bar=ttk.Frame(self.photo_tab);bar.pack(fill="x",padx=8,pady=8);ttk.Button(bar,text="Import trip photos",command=self.import_photos).pack(side="left");ttk.Button(bar,text="Open Sarah photo folder",command=lambda:os.startfile(app_home()/"photos") if hasattr(os,"startfile") else None).pack(side="left",padx=5)
        self.photo_list=tk.Listbox(self.photo_tab,font=("Segoe UI",11));self.photo_list.pack(fill="both",expand=True,padx=8,pady=8);self.refresh_photos()
    def import_photos(self):
        paths=filedialog.askopenfilenames(filetypes=[("Images","*.jpg *.jpeg *.png *.webp")]);count=0
        for path in paths:
            try:self.db.import_photo(Path(path),Path(path).stem);count+=1
            except Exception as exc:messagebox.showwarning("Photo skipped",str(exc))
        self.refresh_photos();self.status.set(f"Imported {count} sanitized, duplicate-checked photo(s).")
    def refresh_photos(self):
        self.photo_list.delete(0,"end")
        for row in self.db.list_rows("photos",limit=300):self.photo_list.insert("end",f"{row['caption']} — {row['sha256'][:10]}")

    def _build_devices(self):
        frame = ttk.Frame(self.device_tab, padding=14)
        frame.pack(fill="both", expand=True)
        ttk.Label(frame, text="Trusted device sync", font=("Segoe UI", 14, "bold")).pack(anchor="w")
        ttk.Label(
            frame,
            text=("Setup required. R2 does not expose pairing tokens over plain local-network HTTP. "
                  "Phone/Windows sync remains off until an authenticated TLS or key-agreement path is accepted."),
            wraplength=760,
            justify="left",
        ).pack(anchor="w", pady=8)
        ttk.Button(frame, text="Export encrypted .sarahmind backup", command=self.backup).pack(anchor="w", pady=5)
        ttk.Button(frame, text="Restore encrypted backup from this computer", command=self.restore).pack(anchor="w")
        ttk.Button(frame, text="Upload encrypted backup to Google Drive appDataFolder", command=self.drive_backup).pack(anchor="w", pady=5)
        ttk.Button(frame, text="Download and restore newest encrypted Google Drive backup", command=self.drive_restore).pack(anchor="w")
        ttk.Button(frame, text="Revoke a paired device", command=self.revoke_device).pack(anchor="w", pady=5)
        ttk.Button(frame, text="Voice cache status / cleanup", command=self.manage_voice_cache).pack(anchor="w")

    def manage_voice_cache(self):
        status = self.voice.cache_status()
        size_mib = status["size_bytes"] / (1024 * 1024)
        maximum_mib = status["documented_max_bytes"] / (1024 * 1024)
        approved = messagebox.askyesno(
            "Sarah voice cache",
            f"Current regenerable voice cache: {size_mib:.1f} MiB.\n"
            f"Documented owner-managed maximum: {maximum_mib:.0f} MiB.\n\n"
            "Clear only cached MP3 speech now? Approved voice settings, models, and profile data are not removed.",
            parent=self.root,
        )
        if not approved:
            return
        result = self.voice.clear_cache_by_owner_request()
        self.status.set(
            f"Voice cache cleanup removed {result['removed_files']} regenerable file(s) "
            f"and {result['removed_bytes'] / (1024 * 1024):.1f} MiB."
        )
    def rotate_code(self):self.pair_var.set(self.sync_server.rotate_code());self.status.set("A new 15-minute pairing code is ready.")
    def local_ip(self):
        try:s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.connect(("8.8.8.8",80));ip=s.getsockname()[0];s.close();return ip
        except Exception:return "this-computer"
    def backup(self):
        path=filedialog.asksaveasfilename(defaultextension=".sarahmind",filetypes=[("Sarah mind archive","*.sarahmind")]);
        if not path:return
        password=simpledialog.askstring("Backup password","Choose at least 10 characters. This password is not uploaded.",show="*")
        if not password:return
        try:self.db.create_backup(Path(path),password);messagebox.showinfo("Backup created","The encrypted Sarah archive was created.")
        except Exception as exc:messagebox.showerror("Backup failed",str(exc))
    def restore(self):
        path=filedialog.askopenfilename(filetypes=[("Sarah mind archive","*.sarahmind")]);
        if not path:return
        password=simpledialog.askstring("Backup password","Enter the archive password.",show="*")
        try:
            self._begin_voice_generation("profile_archive_restore")
            self.db.restore_backup(Path(path),password or "")
            messagebox.showinfo("Restored","Sarah's encrypted archive was restored. Restart the companion.")
        except Exception as exc:messagebox.showerror("Restore failed",str(exc))
    def drive_backup(self):
        from google_drive_backup import upload_encrypted_backup
        backup=filedialog.askopenfilename(filetypes=[("Sarah mind archive","*.sarahmind")]);
        if not backup:return
        client=filedialog.askopenfilename(title="Select Google OAuth desktop client JSON",filetypes=[("JSON","*.json")]);
        if not client:return
        try:file_id=upload_encrypted_backup(Path(backup),Path(client));messagebox.showinfo("Uploaded",f"Encrypted backup uploaded to Drive appDataFolder. File ID: {file_id}")
        except Exception as exc:messagebox.showerror("Drive upload failed",str(exc))
    def drive_restore(self):
        from google_drive_backup import download_latest_encrypted_backup
        client=filedialog.askopenfilename(title="Select Google OAuth desktop client JSON",filetypes=[("JSON","*.json")])
        if not client:return
        password=simpledialog.askstring("Backup password","Enter the archive password after it downloads.",show="*")
        if not password:return
        try:
            destination=app_home()/"backups"/"latest-google-drive.sarahmind"
            download_latest_encrypted_backup(destination,Path(client))
            self._begin_voice_generation("profile_archive_restore")
            self.db.restore_backup(destination,password)
            messagebox.showinfo("Drive restore completed","Sarah's newest encrypted Drive archive was restored. Restart the companion so the restored private encryption key is loaded.")
        except Exception as exc:messagebox.showerror("Drive restore failed",str(exc))

    def revoke_device(self):
        with self.db.connect() as db:rows=db.execute("SELECT device_id,device_name FROM trusted_devices WHERE revoked=0").fetchall()
        if not rows:messagebox.showinfo("Devices","No active paired devices.");return
        name=simpledialog.askstring("Revoke device","Enter the exact device name:\n"+"\n".join(r["device_name"] for r in rows))
        if name:
            with self.db.connect() as db:db.execute("UPDATE trusted_devices SET revoked=1 WHERE lower(device_name)=lower(?)",(name.strip(),))

    def _build_activity(self):
        bar=ttk.Frame(self.activity_tab);bar.pack(fill="x",padx=8,pady=8);ttk.Button(bar,text="Refresh",command=self.refresh_activity).pack(side="left");ttk.Label(bar,text="Factual truth is shown for auditing. Private mind remains hidden unless you explicitly open one event.").pack(side="left",padx=10)
        self.activity_list=tk.Listbox(self.activity_tab,font=("Segoe UI",10));self.activity_list.pack(fill="both",expand=True,padx=8,pady=8);self.activity_list.bind("<Double-1>",self.open_private_event);self.activity_rows=[];self.refresh_activity()
    def refresh_activity(self):
        self.activity_rows=self.db.visible_activity(200);self.activity_list.delete(0,"end")
        for row in self.activity_rows:self.activity_list.insert("end",f"{row['classification']} | {row['spoken'][:85]} | FACT: {row.get('factual_truth','')[:85]}")
    def open_private_event(self,_event=None):
        sel=self.activity_list.curselection();
        if not sel:return
        event=self.db.private_event(self.activity_rows[sel[0]]["event_id"]);messagebox.showinfo("Explicit private event review",f"SPOKEN:\n{event.get('spoken','')}\n\nPRIVATE MIND:\n{event.get('private_mind','')}\n\nFACTUAL TRUTH:\n{event.get('factual_truth','')}\n\nClassification: {event.get('classification','')}")

    def _start_corner(self):
        self.corner=tk.Toplevel(self.root);self.corner.title("Sarah");self.corner.geometry("180x220+20+80");self.corner.attributes("-topmost",True);self.corner.configure(bg="#183448");self.corner.overrideredirect(True);self.canvas=tk.Canvas(self.corner,width=180,height=175,bg="#183448",highlightthickness=0);self.canvas.pack();self.canvas.bind("<ButtonPress-1>",self._drag_start);self.canvas.bind("<B1-Motion>",self._drag_move);ttk.Button(self.corner,text="Talk with Sarah",command=self.show).pack(fill="x",padx=8,pady=5);self._load_portrait_once();self._animate_avatar()
    def _drag_start(self,e):self._dx=e.x;self._dy=e.y
    def _drag_move(self,e):self.corner.geometry(f"+{self.corner.winfo_x()+e.x-self._dx}+{self.corner.winfo_y()+e.y-self._dy}")
    def _load_portrait_once(self):
        """Load the exact immutable source portrait into the CPU-only live renderer."""
        if self._portrait_load_attempted:
            return self._portrait_renderer is not None
        self._portrait_load_attempted = True
        resolved = resolve_sarah_portrait()
        if resolved is None:
            return False
        try:
            with Image.open(resolved) as image:
                image.load()
                self._portrait_base_image = image.convert("RGB").copy()
            self._portrait_renderer = PortraitFrameRenderer(
                self._portrait_base_image,
                SARAH_PORTRAIT_DISPLAY_SIZE,
            )
            self._portrait_asset_path = resolved
            return True
        except Exception:
            self._portrait_photo = None
            self._portrait_base_image = None
            self._portrait_renderer = None
            self._portrait_asset_path = None
            return False

    def _draw_vector_avatar(self):
        """Preserved static rollback art when the exact portrait is unavailable."""
        self.canvas.create_oval(47,16,133,112,fill="#e2ad86",outline="#f2d1b8",width=2)
        self.canvas.create_arc(38,5,143,108,start=15,extent=150,fill="#5a382a",outline="#5a382a")
        self.canvas.create_arc(37,6,144,111,start=195,extent=150,fill="#5a382a",outline="#5a382a")
        self.canvas.create_oval(67,54,78,60,fill="#263746",outline="")
        self.canvas.create_oval(102,54,113,60,fill="#263746",outline="")
        self.canvas.create_arc(78,77,103,91,start=200,extent=140,style="arc",width=2,outline="#8b3f4b")
        self.canvas.create_polygon(42,107,138,107,165,171,15,171,fill="#2f7897",outline="#6eb1ca")

    def _animate_avatar(self):
        """Continuously render bounded idle motion, blinks, and voice-bound mouth motion."""
        if not self.corner:return
        try:
            viewable = bool(self.corner.winfo_viewable())
        except (AttributeError, tk.TclError):
            viewable = True
        if not viewable:
            # Keep the live loop responsive to Show without spending CPU on
            # frames that cannot be seen in the notification area.
            self.root.after(HIDDEN_AVATAR_POLL_INTERVAL_MS,self._animate_avatar)
            return
        self.canvas.delete("all")
        renderer = getattr(self, "_portrait_renderer", None)
        if renderer is not None:
            try:
                pose = self._ensure_avatar_motion().pose_at()
                frame = renderer.render(pose)
                self._portrait_photo = ImageTk.PhotoImage(frame, master=self.canvas)
                self.canvas.create_image(0,0,anchor="nw",image=self._portrait_photo)
            except Exception:
                self._draw_vector_avatar()
        else:
            self._draw_vector_avatar()
        pulse=int(time.monotonic()*4)%2
        if self.speaking:
            outline="#ffe19a" if pulse else "#69e7ee"
            width=3
        else:
            outline="#5bc4cf" if pulse else "#397c8a"
            width=2
        self.canvas.create_rectangle(2,2,177,172,outline=outline,width=width)
        self.root.after(LIVE_AVATAR_FRAME_INTERVAL_MS,self._animate_avatar)
    def _start_tray(self):
        if not pystray:return
        image=Image.new("RGB",(64,64),"#183448");d=ImageDraw.Draw(image);d.ellipse((14,8,50,44),fill="#e2ad86");d.polygon((10,62,54,62,46,38,18,38),fill="#2f7897")
        self.tray=pystray.Icon("SarahMorgan",image,"Sarah Morgan",pystray.Menu(pystray.MenuItem("Show Sarah",lambda:self.root.after(0,self.show)),pystray.MenuItem("Quit",lambda:self.root.after(0,self.quit))))
        threading.Thread(target=self.tray.run,daemon=True).start()
    def hide_to_tray(self):self.root.withdraw();self.corner.withdraw();self.status.set("Sarah is active in hidden icons")
    def show(self):self.root.deiconify();self.root.lift();self.corner.deiconify();self.tabs.select(self.chat_tab);self.entry.focus_set()
    def _idle_research_tick(self):
        profile=self.db.active_profile()
        person_id=profile.get("person_id") or self.db.get_setting("active_person_id")
        has_context=bool(self.db.list_rows("trips",person_id=person_id,limit=1) or (self.db.nearby_enabled(person_id) and self.db.current_area(person_id=person_id)))
        now=time.monotonic()
        idle=now-self.last_interaction_at>=120.0 and not self.speaking
        if now>=self.next_background_research_at and idle and self.research.configured and self.db.background_research_enabled(person_id) and has_context:
            if self._queue_research("profile_opted_in_idle_background"):
                self.next_background_research_at=now+6*60*60
        self.root.after(60*1000,self._idle_research_tick)
    def quit(self):
        self.research_generation += 1
        self._begin_voice_generation("application_exit")
        if hasattr(self, "answer_requests"):
            self.answer_requests.put(None)
        if hasattr(self, "voice_requests"):
            self.voice_requests.put(None)
        try:self.sync_server.stop()
        finally:
            if self.tray:self.tray.stop()
            self.root.destroy()
    def run(self):self.root.mainloop()

if __name__ == "__main__":SarahApp().run()
