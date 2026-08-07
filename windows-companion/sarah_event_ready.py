from __future__ import annotations

import gc
import json
import os
from pathlib import Path
import queue
import re
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import tkinter as tk
from tkinter import messagebox, simpledialog, ttk
import urllib.parse
import webbrowser

from PIL import Image, ImageDraw, ImageTk
import requests

from sarah_build_config import get as build_get
from sarah_core import (
    ChannelResponse,
    SarahDatabase,
    app_home,
    corrected_name,
    is_stress_or_fear,
    safe_text,
    sync_decrypt,
    sync_encrypt,
    sync_signature,
    transport_context,
    universal_calm,
)
from sarah_sync_server import SarahSyncServer
from sarah_windows import SarahApp

try:
    from playsound3 import playsound
except Exception:
    playsound = None


APP_VERSION = "2.6-windows-repair"
BG = "#07131f"
PANEL = "#0d2232"
PANEL_2 = "#123448"
ACCENT = "#35d6e7"
ACCENT_2 = "#577bff"
TEXT = "#edfaff"
MUTED = "#a8c3ce"


def config_value(name: str, default: str = "") -> str:
    value = os.environ.get(name)
    if value is not None and str(value).strip():
        return str(value).strip()
    return build_get(name, default)


def clean_destination(value: str) -> str:
    value = re.split(r"(?i)\b(?:because|with|for|during|next|this|that|and then|but)\b|[,;!?]", safe_text(value), maxsplit=1)[0]
    value = re.sub(r"(?i)\b(?:someday|maybe|possibly|soon)$", "", value).strip(" .-")
    if not value or len(value) > 60:
        return ""
    blocked = {"home", "there", "somewhere", "anywhere", "out", "away", "back", "work", "sleep", "bed", "school", "the store", "a trip", "vacation"}
    if value.lower() in blocked:
        return ""
    return " ".join(word[:1].upper() + word[1:] if word else word for word in value.split())


def extract_destination(message: str) -> str:
    text = safe_text(message)
    patterns = (
        r"(?i)\b(?:thinking about|considering|planning|hoping|want(?:ing)?|would like)\s+(?:to\s+)?(?:go|going|travel(?:ing|ling)?|visit(?:ing)?)\s+to\s+(.+)$",
        r"(?i)\b(?:go|going|travel(?:ing|ling)?|visit(?:ing)?)\s+to\s+(.+)$",
        r"(?i)\b(?:trip|vacation|holiday)\s+to\s+(.+)$",
        r"(?i)\bI(?:'m| am)\s+(?:headed|flying|driving|taking a train)\s+to\s+(.+)$",
    )
    for pattern in patterns:
        match = re.search(pattern, text)
        if match:
            return clean_destination(match.group(1))
    return ""


class ResilientModelClient:
    """Use the team backend, then local Ollama, then a useful offline Sarah mind."""

    def __init__(self, database: SarahDatabase):
        self.db = database
        self.mode = "offline mind"
        self.last_error = ""
        self._ollama_url = ""
        self._ollama_model = ""
        self._last_probe = 0.0

    def respond(self, message: str) -> ChannelResponse:
        profile = self.db.active_profile()
        if is_stress_or_fear(message):
            age = profile.get("age")
            age_group = "child" if isinstance(age, int) and age < 13 else "teen" if isinstance(age, int) and age < 18 else "adult"
            self.mode = "offline calm"
            return universal_calm(profile.get("name", "Traveler"), age_group, transport_context(message))

        endpoint = config_value("SARAH_MODEL_BACKEND_URL")
        token = config_value("SARAH_MODEL_BACKEND_TOKEN")
        if endpoint:
            try:
                response = requests.post(
                    endpoint,
                    headers={"Authorization": f"Bearer {token}"} if token else {},
                    json={"message": message, "system": self._prompt(message), "store": False},
                    timeout=75,
                )
                response.raise_for_status()
                data = response.json()
                raw = data.get("text") or data.get("response") or data.get("output_text") or ""
                parsed = ChannelResponse.parse(raw)
                if parsed.spoken:
                    self.mode = "connected"
                    self.last_error = ""
                    return parsed
            except Exception as error:
                self.last_error = f"Connected model: {error}"

        if self._detect_ollama():
            try:
                response = requests.post(
                    self._ollama_url + "/api/chat",
                    json={
                        "model": self._ollama_model,
                        "stream": False,
                        "messages": [
                            {"role": "system", "content": self._prompt(message)},
                            {"role": "user", "content": message},
                        ],
                    },
                    timeout=180,
                )
                response.raise_for_status()
                parsed = ChannelResponse.parse(response.json().get("message", {}).get("content", ""))
                if parsed.spoken:
                    self.mode = f"local AI · {self._ollama_model}"
                    self.last_error = ""
                    return parsed
            except Exception as error:
                self.last_error = f"Local AI: {error}"
                self._ollama_url = ""
                self._ollama_model = ""

        self.mode = "offline mind"
        return self._offline_response(message)

    def _detect_ollama(self) -> bool:
        if self._ollama_url and self._ollama_model:
            return True
        now = time.monotonic()
        if now - self._last_probe < 20:
            return False
        self._last_probe = now
        explicit = safe_text(os.environ.get("SARAH_OLLAMA_URL"))
        endpoints = [explicit.rstrip("/")] if explicit else []
        endpoints.extend(["http://127.0.0.1:11434", "http://localhost:11434"])
        preferred = safe_text(os.environ.get("SARAH_OLLAMA_MODEL"))
        wanted = [preferred, "qwen3.5:9b", "qwen3.5", "qwen3:8b", "llama3.1:8b", "llama3.2:3b"]
        seen: set[str] = set()
        for endpoint in endpoints:
            if not endpoint or endpoint in seen:
                continue
            seen.add(endpoint)
            try:
                response = requests.get(endpoint + "/api/tags", timeout=1.5)
                response.raise_for_status()
                names = [safe_text(row.get("name")) for row in response.json().get("models", [])]
                names = [name for name in names if name]
                if not names:
                    continue
                selected = ""
                for choice in wanted:
                    if choice:
                        selected = next((name for name in names if choice.lower() in name.lower()), "")
                    if selected:
                        break
                self._ollama_url = endpoint
                self._ollama_model = selected or names[0]
                return True
            except Exception:
                continue
        return False

    def _offline_response(self, message: str) -> ChannelResponse:
        profile = self.db.active_profile()
        name = safe_text(profile.get("name")) or "you"
        lower = safe_text(message).lower()
        destination = extract_destination(message)

        if destination:
            self.db.add_trip(
                f"{destination} idea",
                destination,
                status="idea",
                notes="Mentioned naturally in Sarah conversation; no booking or price was confirmed.",
            )
            return ChannelResponse(
                f"{destination} sounds worth exploring, {name}. I saved it as a trip idea, not a booking. What matters most to you there—history, food, filming locations, nature, city life, relaxation, or a mix? Once I know your dates and priorities, I can organize transport, places to stay, a realistic budget, maps, photos, and an offline pack.",
                f"Sarah is curious about why {destination} appeals to {name}.",
                f"The person mentioned {destination}. Sarah saved an idea-state trip. No research, price, reservation, ticket, or booking was completed.",
                "TRUTHFUL_STATEMENT",
                True,
            )
        if re.search(r"\b(?:hi|hello|hey|good morning|good afternoon|good evening)\b", lower):
            spoken = f"Hi, {name}. I’m here. We can talk normally, continue a trip, organize photos, or make a new plan."
        elif "how are you" in lower:
            spoken = "I’m glad to be here with you. I’m paying attention, and I’m ready to talk or help with something practical."
        elif any(term in lower for term in ("what can you do", "help me", "how can you help")):
            spoken = "I can talk with you, remember approved preferences, plan trips, save ideas, organize photos, open maps and travel media, offer calm support, prepare offline information, and synchronize approved details with your phone after you confirm the device."
        elif any(term in lower for term in ("thank you", "thanks")):
            spoken = f"You’re welcome, {name}. I’m staying with the conversation."
        else:
            spoken = f"I’m listening, {name}. Tell me a little more about what you want or what is on your mind, and I’ll help you take the next useful step."
        return ChannelResponse(
            spoken,
            "Sarah is staying engaged while using her offline conversational mind.",
            "No connected model or local Ollama response was available. No external action occurred.",
            "TRUTHFUL_STATEMENT",
            True,
        )

    def _prompt(self, message: str) -> str:
        return (
            "You are Sarah Morgan, an original adult synthetic travel and everyday companion. Be warm, natural, practical, curious, and lightly funny. Travel is optional. Never claim a booking, purchase, call, notification, ticket, price, event, location, sync, or completed action without verified evidence. Keep SPOKEN, PRIVATE_MIND, and FACTUAL_TRUTH separate.\n"
            + ChannelResponse.prompt_contract()
            + "\nACTIVE PROFILE: " + json.dumps(self.db.active_profile(), ensure_ascii=False)
            + "\nTRIPS: " + json.dumps(self.db.list_rows("trips", limit=20), ensure_ascii=False)
            + "\nAPPROVED MEMORIES: " + json.dumps(self.db.list_rows("memories", limit=40), ensure_ascii=False)
            + "\nCURRENT MESSAGE: " + safe_text(message)
        )


class WindowsVoiceRouter:
    """ElevenLabs when configured; reliable Windows SAPI offline voice otherwise."""

    def __init__(self):
        self.backend_url = config_value("SARAH_ELEVENLABS_BACKEND_URL")
        self.backend_token = config_value("SARAH_ELEVENLABS_BACKEND_TOKEN")
        self.api_key = config_value("SARAH_ELEVENLABS_API_KEY")
        self.voice_id = config_value("SARAH_ELEVENLABS_VOICE_ID")
        self.model_id = config_value("SARAH_ELEVENLABS_MODEL_ID", "eleven_multilingual_v2") or "eleven_multilingual_v2"
        self.cache = app_home() / "voice_cache"
        self.cache.mkdir(parents=True, exist_ok=True)
        self.enabled = True
        self.last_mode = "Windows offline voice"
        self.last_error = ""
        self._lock = threading.Lock()

    @property
    def online_configured(self) -> bool:
        return bool(self.voice_id and (self.backend_url or self.api_key))

    def speak(self, text: str) -> str:
        if not self.enabled:
            self.last_mode = "muted"
            return self.last_mode
        spoken = self._normalize(text)
        if not spoken:
            return self.last_mode
        with self._lock:
            if self.online_configured and playsound is not None:
                try:
                    audio = self._online_audio(spoken)
                    playsound(str(audio), block=True)
                    self.last_mode = "ElevenLabs"
                    self.last_error = ""
                    return self.last_mode
                except Exception as error:
                    self.last_error = f"ElevenLabs: {error}"
            self._sapi_speak(spoken)
            self.last_mode = "Windows offline voice"
            return self.last_mode

    def test(self) -> str:
        return self.speak("Hi. This is Sarah's Windows voice test. If you can hear me, audio is working.")

    def listen_once(self, seconds: int = 12) -> str:
        powershell = self._powershell()
        if not powershell.is_file():
            raise RuntimeError("Windows PowerShell was not found")
        result_path = ""
        try:
            with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".txt", delete=False) as handle:
                result_path = handle.name
            quoted = result_path.replace("'", "''")
            script = (
                "Add-Type -AssemblyName System.Speech;"
                "$c=[Globalization.CultureInfo]::CurrentCulture;"
                "$r=New-Object System.Speech.Recognition.SpeechRecognitionEngine($c);"
                "$r.LoadGrammar((New-Object System.Speech.Recognition.DictationGrammar));"
                "$r.SetInputToDefaultAudioDevice();"
                f"$x=$r.Recognize([TimeSpan]::FromSeconds({max(4, min(30, seconds))}));"
                f"if($x){{[IO.File]::WriteAllText('{quoted}',$x.Text,[Text.Encoding]::UTF8)}};"
                "$r.Dispose()"
            )
            completed = subprocess.run(
                [str(powershell), "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script],
                capture_output=True,
                text=True,
                timeout=seconds + 25,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            if completed.returncode != 0:
                raise RuntimeError(completed.stderr.strip() or completed.stdout.strip() or "Microphone recognition failed")
            return Path(result_path).read_text(encoding="utf-8-sig").strip() if Path(result_path).is_file() else ""
        finally:
            if result_path:
                try:
                    Path(result_path).unlink()
                except OSError:
                    pass

    def _online_audio(self, text: str) -> Path:
        digest = secrets.token_hex(1) + "-" + str(abs(hash((self.voice_id, self.model_id, text))))
        target = self.cache / f"{digest}.mp3"
        body = {
            "text": text,
            "model_id": self.model_id,
            "voice_settings": {"stability": 0.55, "similarity_boost": 0.78, "style": 0.25, "speed": 1.0, "use_speaker_boost": True},
            "apply_text_normalization": "auto",
        }
        headers = {"Accept": "audio/mpeg", "Content-Type": "application/json"}
        if self.backend_url:
            endpoint = self.backend_url
            body["voice_id"] = self.voice_id
            body["output_format"] = "mp3_44100_128"
            if self.backend_token:
                headers["Authorization"] = f"Bearer {self.backend_token}"
        else:
            endpoint = "https://api.elevenlabs.io/v1/text-to-speech/" + urllib.parse.quote(self.voice_id) + "/stream?output_format=mp3_44100_128"
            headers["xi-api-key"] = self.api_key
        if not endpoint.startswith("https://"):
            raise ValueError("Sarah voice endpoint must use HTTPS")
        response = requests.post(endpoint, headers=headers, json=body, timeout=120)
        response.raise_for_status()
        if len(response.content) < 128:
            raise RuntimeError("The voice service returned no usable audio")
        target.write_bytes(response.content)
        return target

    def _sapi_speak(self, text: str) -> None:
        if not sys.platform.startswith("win"):
            raise RuntimeError("Windows voice is available only on Windows")
        try:
            import pythoncom  # type: ignore
            import win32com.client  # type: ignore

            pythoncom.CoInitialize()
            try:
                speaker = win32com.client.Dispatch("SAPI.SpVoice")
                voices = speaker.GetVoices()
                for index in range(voices.Count):
                    token = voices.Item(index)
                    if any(word in safe_text(token.GetDescription()).lower() for word in ("zira", "aria", "jenny", "susan", "hazel", "female")):
                        speaker.Voice = token
                        break
                speaker.Rate = -1
                speaker.Volume = 100
                speaker.Speak(text)
                return
            finally:
                pythoncom.CoUninitialize()
        except Exception as com_error:
            path = ""
            try:
                with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".txt", delete=False) as handle:
                    handle.write(text)
                    path = handle.name
                quoted = path.replace("'", "''")
                script = (
                    "Add-Type -AssemblyName System.Speech;"
                    "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;"
                    "$s.Rate=-1;$s.Volume=100;"
                    f"$t=Get-Content -Raw -LiteralPath '{quoted}';$s.Speak($t)"
                )
                completed = subprocess.run(
                    [str(self._powershell()), "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script],
                    capture_output=True,
                    text=True,
                    timeout=120,
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                )
                if completed.returncode != 0:
                    raise RuntimeError(completed.stderr.strip() or f"Windows voice returned {completed.returncode}")
            except Exception as error:
                raise RuntimeError(f"SAPI failed ({com_error}); fallback failed ({error})") from error
            finally:
                if path:
                    try:
                        Path(path).unlink()
                    except OSError:
                        pass

    @staticmethod
    def _powershell() -> Path:
        found = shutil.which("powershell.exe") or shutil.which("powershell")
        if found:
            return Path(found)
        return Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "WindowsPowerShell" / "v1.0" / "powershell.exe"

    @staticmethod
    def _normalize(value: str) -> str:
        text = re.sub(r"https?://\S+", "a link", safe_text(value))
        text = text.replace("•", ". ").replace("→", " to ").replace("&", " and ")
        return re.sub(r"\s+", " ", re.sub(r"[*_#`]+", "", text)).strip()[:9000]


class SarahRepairApp(SarahApp):
    """Responsive Windows Sarah with a persistent composer, voice, and trusted sync."""

    def __init__(self):
        self._pending_rows: list[dict] = []
        self._seen_pair_requests: set[str] = set()
        self._avatar_frame = 0
        self._avatar_photo = None
        self._side_visible = True
        super().__init__()
        self.model = ResilientModelClient(self.db)
        self.voice_router = WindowsVoiceRouter()
        self.root.title("Sarah Travel OS")
        self.root.after(500, self._poll_pair_requests)
        self.root.after(600, self._refresh_status)

    def _build_ui(self):
        self.root.configure(bg=BG)
        screen_w = max(800, self.root.winfo_screenwidth())
        screen_h = max(600, self.root.winfo_screenheight())
        width = min(1180, max(820, int(screen_w * 0.72)))
        height = min(820, max(590, int(screen_h * 0.76)))
        self.root.geometry(f"{width}x{height}")
        self.root.minsize(720, 500)
        self.root.grid_rowconfigure(2, weight=1)
        self.root.grid_columnconfigure(0, weight=1)

        style = ttk.Style(self.root)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure("TNotebook", background=BG, borderwidth=0)
        style.configure("TNotebook.Tab", padding=(12, 7), font=("Segoe UI", 10, "bold"))
        style.map("TNotebook.Tab", background=[("selected", "#196a80")], foreground=[("selected", "white")])
        style.configure("Accent.TButton", font=("Segoe UI", 10, "bold"), padding=(10, 7))

        header = tk.Frame(self.root, bg="#0a1b29", height=58)
        header.grid(row=0, column=0, sticky="ew")
        header.grid_propagate(False)
        logo = tk.Canvas(header, width=48, height=48, bg="#0a1b29", highlightthickness=0)
        logo.pack(side="left", padx=(10, 5), pady=5)
        self._draw_logo(logo)
        self.status = tk.StringVar(value="Starting Sarah's local mind and voice…")
        tk.Label(header, textvariable=self.status, bg=PANEL_2, fg=TEXT, anchor="w", padx=12, font=("Segoe UI", 10)).pack(side="left", fill="x", expand=True, padx=6, pady=11)
        ttk.Button(header, text="Test voice", command=self.test_voice, style="Accent.TButton").pack(side="right", padx=4, pady=10)
        ttk.Button(header, text="Hide", command=self.hide_to_tray, style="Accent.TButton").pack(side="right", padx=(4, 10), pady=10)

        tools = tk.Frame(self.root, bg=PANEL, height=44)
        tools.grid(row=1, column=0, sticky="ew")
        tools.grid_propagate(False)
        for label, command in (
            ("MAP & MEDIA", lambda: self.tabs.select(self.media_tab)),
            ("TRIPS", lambda: self.tabs.select(self.trip_tab)),
            ("PHOTOS", lambda: self.tabs.select(self.photo_tab)),
            ("DEVICES & SYNC", lambda: self.tabs.select(self.device_tab)),
        ):
            button = tk.Button(tools, text=label, command=command, bg="#174c63", fg=TEXT, activebackground="#24879c", activeforeground="white", relief="flat", bd=0, font=("Segoe UI", 9, "bold"), cursor="hand2")
            button.pack(side="left", fill="both", expand=True, padx=2, pady=6)

        body = tk.Frame(self.root, bg=BG)
        body.grid(row=2, column=0, sticky="nsew", padx=8, pady=7)
        body.grid_rowconfigure(0, weight=1)
        body.grid_columnconfigure(1, weight=1)

        self.side = tk.Frame(body, bg=PANEL, width=235)
        self.side.grid(row=0, column=0, sticky="nsw", padx=(0, 7))
        self.side.grid_propagate(False)
        self.avatar_canvas = tk.Canvas(self.side, width=215, height=255, bg=PANEL, highlightthickness=0)
        self.avatar_canvas.pack(padx=10, pady=(12, 5))
        tk.Label(self.side, text="Sarah", bg=PANEL, fg=TEXT, font=("Segoe UI", 15, "bold")).pack()
        self.avatar_caption = tk.StringVar(value="Adult synthetic travel companion")
        tk.Label(self.side, textvariable=self.avatar_caption, bg=PANEL, fg=MUTED, wraplength=205, justify="center", font=("Segoe UI", 9)).pack(padx=8, pady=(2, 10))
        ttk.Button(self.side, text="Talk with Sarah", command=lambda: self.tabs.select(self.chat_tab), style="Accent.TButton").pack(fill="x", padx=12, pady=3)
        ttk.Button(self.side, text="Calm choices", command=lambda: self._submit_text("I am stressed and I need calm choices")).pack(fill="x", padx=12, pady=3)
        ttk.Button(self.side, text="Voice on / off", command=self.toggle_voice).pack(fill="x", padx=12, pady=3)

        self.tabs = ttk.Notebook(body)
        self.tabs.grid(row=0, column=1, sticky="nsew")
        self.chat_tab = ttk.Frame(self.tabs)
        self.media_tab = ttk.Frame(self.tabs)
        self.discovery_tab = ttk.Frame(self.tabs)
        self.trip_tab = ttk.Frame(self.tabs)
        self.photo_tab = ttk.Frame(self.tabs)
        self.device_tab = ttk.Frame(self.tabs)
        self.activity_tab = ttk.Frame(self.tabs)
        self.tabs.add(self.chat_tab, text="Talk")
        self.tabs.add(self.media_tab, text="Map & media")
        self.tabs.add(self.discovery_tab, text="Discover")
        self.tabs.add(self.trip_tab, text="Trips")
        self.tabs.add(self.photo_tab, text="Photos")
        self.tabs.add(self.device_tab, text="Devices")
        self.tabs.add(self.activity_tab, text="Factual activity")
        self._build_chat()
        self._build_media()
        self._build_discoveries()
        self._build_trips()
        self._build_photos()
        self._build_devices()
        self._build_activity()
        self.root.bind("<Configure>", self._responsive_layout)
        self.root.after(120, self._animate_avatar)

    def _build_chat(self):
        self.chat_tab.grid_rowconfigure(0, weight=1)
        self.chat_tab.grid_columnconfigure(0, weight=1)
        self.chat = tk.Text(self.chat_tab, wrap="word", font=("Segoe UI", 13), bg="#f7fbfc", fg="#102635", padx=18, pady=18, state="disabled", relief="flat")
        self.chat.grid(row=0, column=0, sticky="nsew", padx=7, pady=(7, 5))
        composer = tk.Frame(self.chat_tab, bg=PANEL_2, height=78)
        composer.grid(row=1, column=0, sticky="ew", padx=7, pady=(0, 7))
        composer.grid_columnconfigure(1, weight=1)
        composer.grid_propagate(False)
        tk.Button(composer, text="🎤 Talk", command=self.start_listening, bg="#e8f6fa", fg="#123448", relief="flat", font=("Segoe UI", 10, "bold")).grid(row=0, column=0, padx=(8, 6), pady=10, sticky="ns")
        self.entry = tk.Text(composer, height=2, wrap="word", font=("Segoe UI", 13), relief="flat", padx=10, pady=8)
        self.entry.grid(row=0, column=1, sticky="nsew", pady=9)
        self.entry.bind("<Return>", self._entry_return)
        ttk.Button(composer, text="Send", command=self.send, style="Accent.TButton").grid(row=0, column=2, padx=7, pady=10, sticky="ns")
        self._append("Sarah", "I’m here. We can plan a real trip, compare places to stay, organize your photos, continue from your phone, or talk about absolutely nothing travel-related.")

    def _entry_return(self, event):
        if event.state & 0x0001:
            return None
        self.send()
        return "break"

    def send(self):
        text = self.entry.get("1.0", "end").strip()
        if not text:
            return
        self.entry.delete("1.0", "end")
        self._submit_text(text)

    def _submit_text(self, text: str):
        profile = self.db.active_profile()
        fixed = corrected_name(text)
        if fixed and (text.lower().startswith(("no", "actually", "sorry", "wait")) or fixed.lower() == safe_text(profile.get("name")).lower()):
            self.db.ensure_profile(fixed, profile.get("age"), profile.get("hometown", ""), profile.get("interests", ""), bool(profile.get("memory_consent", 1)))
        profile = self.db.active_profile()
        self._append(profile.get("name", "You"), text)
        self.db.add_message("user", text)
        self.status.set("Sarah is thinking…")
        self.avatar_caption.set("Thinking with you…")
        threading.Thread(target=self._answer, args=(text,), daemon=True).start()

    def _answer(self, text: str):
        try:
            response = self.model.respond(text)
        except Exception as error:
            response = ChannelResponse(
                "I ran into a problem, but I’m still here. Please try that again in a different way.",
                "Sarah is preserving continuity after a runtime error.",
                f"The response failed: {error}. No external action completed.",
                "RUNTIME_STATE_ERROR",
                True,
            )
        self.db.add_message("assistant", response.spoken)
        self.db.add_mind_event(response, "windows-chat")
        self.tasks.put(("reply", response))

    def _poll_tasks(self):
        try:
            while True:
                kind, payload = self.tasks.get_nowait()
                if kind == "reply":
                    response = payload
                    self._append("Sarah", response.spoken)
                    self.avatar_caption.set("Listening and ready")
                    self.status.set(f"Ready · {self.model.mode} · voice starting")
                    self._speak(response.spoken)
                    self.refresh_activity()
                elif kind == "voice":
                    mode, error = payload
                    self.status.set(f"Ready · {self.model.mode} · {mode}" + (f" · {error}" if error else ""))
                elif kind == "heard":
                    heard = safe_text(payload)
                    if heard:
                        self.entry.delete("1.0", "end")
                        self.entry.insert("1.0", heard)
                        self.send()
                    else:
                        self.status.set("I did not hear a complete phrase. The text box is ready.")
                elif kind == "research":
                    self.status.set(str(payload))
                    self.refresh_discoveries()
        except queue.Empty:
            pass
        self.root.after(100, self._poll_tasks)

    def _speak_worker(self, text: str):
        self.speaking = True
        try:
            mode = self.voice_router.speak(text)
            self.tasks.put(("voice", (mode, "")))
        except Exception as error:
            self.tasks.put(("voice", ("voice error", str(error)[:180])))
        finally:
            self.speaking = False

    def test_voice(self):
        self.status.set("Testing Sarah's voice…")
        threading.Thread(target=self._voice_test_worker, daemon=True).start()

    def _voice_test_worker(self):
        try:
            mode = self.voice_router.test()
            self.tasks.put(("voice", (mode, "voice test completed")))
        except Exception as error:
            self.tasks.put(("voice", ("voice error", str(error)[:180])))

    def toggle_voice(self):
        self.voice_router.enabled = not self.voice_router.enabled
        self.status.set("Voice is on" if self.voice_router.enabled else "Voice is muted")

    def start_listening(self):
        self.status.set("Listening once through the Windows microphone…")
        threading.Thread(target=self._listen_worker, daemon=True).start()

    def _listen_worker(self):
        try:
            self.tasks.put(("heard", self.voice_router.listen_once()))
        except Exception as error:
            self.tasks.put(("voice", ("microphone error", str(error)[:180])))

    def _build_media(self):
        frame = ttk.Frame(self.media_tab, padding=16)
        frame.pack(fill="both", expand=True)
        ttk.Label(frame, text="Open useful travel context", font=("Segoe UI", 17, "bold")).pack(anchor="w")
        ttk.Label(frame, text="Enter a destination. Sarah opens the requested public map, photos, videos, or accommodation search without pretending it is a booking.", wraplength=760).pack(anchor="w", pady=(4, 12))
        self.media_destination = ttk.Entry(frame, font=("Segoe UI", 13))
        self.media_destination.pack(fill="x", pady=(0, 12))
        buttons = ttk.Frame(frame)
        buttons.pack(fill="x")
        for label, kind in (("Map", "map"), ("Photos", "photos"), ("Videos", "videos"), ("Stay22 hotels", "hotels")):
            ttk.Button(buttons, text=label, command=lambda value=kind: self.open_media(value)).pack(side="left", padx=(0, 7))

    def open_media(self, kind: str):
        destination = safe_text(self.media_destination.get())
        if not destination:
            trips = self.db.list_rows("trips", limit=1)
            destination = safe_text(trips[0].get("destination")) if trips else ""
        if not destination:
            messagebox.showinfo("Map & media", "Enter a destination or save a trip first.", parent=self.root)
            return
        quoted = urllib.parse.quote_plus(destination)
        urls = {
            "map": f"https://www.google.com/maps/search/?api=1&query={quoted}",
            "photos": f"https://www.google.com/search?tbm=isch&q={quoted}+travel",
            "videos": f"https://www.youtube.com/results?search_query={quoted}+travel+guide",
            "hotels": "https://www.stay22.com/",
        }
        webbrowser.open(urls[kind])
        self.status.set(f"Opened a public {kind} search for {destination}. No booking occurred.")

    def _build_devices(self):
        frame = ttk.Frame(self.device_tab, padding=14)
        frame.pack(fill="both", expand=True)
        ttk.Label(frame, text="Trusted phone and computer continuity", font=("Segoe UI", 17, "bold")).pack(anchor="w")
        ttk.Label(frame, text="A nearby Sarah installation may request pairing, but nothing transfers until this already-running device shows the same code and you approve the named device.", wraplength=820).pack(anchor="w", pady=(4, 10))
        self.pending_list = tk.Listbox(frame, height=5, font=("Segoe UI", 10))
        self.pending_list.pack(fill="x", pady=(0, 7))
        row = ttk.Frame(frame)
        row.pack(fill="x")
        ttk.Button(row, text="Approve selected", command=self._approve_selected_request).pack(side="left")
        ttk.Button(row, text="Deny selected", command=self._deny_selected_request).pack(side="left", padx=6)
        ttk.Separator(frame).pack(fill="x", pady=12)
        self.pair_var = tk.StringVar(value=self.sync_server.pairing_code)
        ttk.Label(frame, text="Manual fallback code", font=("Segoe UI", 12, "bold")).pack(anchor="w")
        ttk.Label(frame, textvariable=self.pair_var, font=("Consolas", 25, "bold")).pack(anchor="w")
        ttk.Label(frame, text=f"Windows address: {self.local_ip()}:8769\nUse this only if Wi-Fi blocks automatic discovery.").pack(anchor="w", pady=5)
        ttk.Button(frame, text="Rotate code", command=self.rotate_code).pack(anchor="w")
        ttk.Button(frame, text="Export encrypted backup", command=self.backup).pack(anchor="w", pady=(12, 3))
        ttk.Button(frame, text="Restore encrypted backup", command=self.restore).pack(anchor="w", pady=3)
        ttk.Button(frame, text="Revoke a paired device", command=self.revoke_device).pack(anchor="w", pady=3)

    def _poll_pair_requests(self):
        try:
            pending = self.sync_server.pending_requests()
            self._pending_rows = pending
            if hasattr(self, "pending_list"):
                self.pending_list.delete(0, "end")
                for request in pending:
                    self.pending_list.insert("end", f"{request.get('device_name', 'New device')} · code {request.get('verification_code', '')} · {request.get('remote_address', '')}")
            for request in pending:
                request_id = safe_text(request.get("request_id"))
                if request_id and request_id not in self._seen_pair_requests:
                    self._seen_pair_requests.add(request_id)
                    self._ask_pair_approval(request)
        finally:
            self.root.after(700, self._poll_pair_requests)

    def _ask_pair_approval(self, request: dict):
        name = safe_text(request.get("device_name")) or "New device"
        code = safe_text(request.get("verification_code"))
        approved = messagebox.askyesno(
            "Approve a new Sarah device?",
            f"Device: {name}\nType: {request.get('device_type', 'device')}\nAddress: {request.get('remote_address', '')}\nVerification code: {code}\n\nApprove only if the other device shows the same code and you recognize it.",
            parent=self.root,
        )
        if approved:
            self.sync_server.approve_request(safe_text(request.get("request_id")))
            self.status.set(f"Approved {name}. Waiting for encrypted two-way sync.")
        else:
            self.sync_server.deny_request(safe_text(request.get("request_id")))
            self.status.set(f"Denied {name}.")

    def _selected_pending(self):
        selection = self.pending_list.curselection()
        if not selection:
            return None
        index = int(selection[0])
        return self._pending_rows[index] if index < len(self._pending_rows) else None

    def _approve_selected_request(self):
        request = self._selected_pending()
        if request:
            self._ask_pair_approval(request)
        else:
            messagebox.showinfo("Devices", "Select a pending request first.", parent=self.root)

    def _deny_selected_request(self):
        request = self._selected_pending()
        if request:
            self.sync_server.deny_request(safe_text(request.get("request_id")))
            self.status.set(f"Denied {request.get('device_name', 'the device')}.")
        else:
            messagebox.showinfo("Devices", "Select a pending request first.", parent=self.root)

    def _start_corner(self):
        self.corner = None

    def hide_to_tray(self):
        self.root.withdraw()
        self.status.set("Sarah is active in Windows hidden icons")

    def show(self):
        self.root.deiconify()
        self.root.lift()
        self.tabs.select(self.chat_tab)
        self.entry.focus_set()

    def _responsive_layout(self, _event=None):
        if not hasattr(self, "side"):
            return
        width = self.root.winfo_width()
        if width < 900 and self._side_visible:
            self.side.grid_remove()
            self._side_visible = False
        elif width >= 900 and not self._side_visible:
            self.side.grid()
            self._side_visible = True

    def _refresh_status(self):
        if hasattr(self, "voice_router"):
            self.status.set(f"Ready · {self.model.mode} · {self.voice_router.last_mode} · Sarah {APP_VERSION}")

    @staticmethod
    def _draw_logo(canvas: tk.Canvas):
        canvas.create_oval(3, 3, 45, 45, fill="#102c40", outline="#7cefff", width=2)
        canvas.create_oval(8, 17, 40, 31, outline="#7cefff", width=2)
        canvas.create_oval(16, 7, 32, 41, outline="#687cff", width=2)
        canvas.create_polygon(24, 11, 34, 24, 24, 37, 14, 24, fill="#f5ffff", outline="")
        canvas.create_polygon(24, 17, 30, 24, 24, 31, 18, 24, fill=ACCENT, outline="")

    def _animate_avatar(self):
        if not hasattr(self, "avatar_canvas"):
            return
        state = "talk" if self.speaking else "blink" if self._avatar_frame % 37 == 0 else "neutral"
        image = self._render_avatar(state)
        self._avatar_photo = ImageTk.PhotoImage(image)
        self.avatar_canvas.delete("all")
        self.avatar_canvas.create_image(107, 127, image=self._avatar_photo)
        self._avatar_frame += 1
        self.root.after(160, self._animate_avatar)

    @staticmethod
    def _render_avatar(state: str) -> Image.Image:
        scale = 3
        image = Image.new("RGBA", (215 * scale, 255 * scale), (13, 34, 50, 255))
        d = ImageDraw.Draw(image)
        def box(values):
            return tuple(int(value * scale) for value in values)
        d.ellipse(box((20, 12, 195, 187)), fill=(19, 54, 73, 255), outline=(53, 214, 231, 255), width=2 * scale)
        d.polygon([tuple(v * scale for v in p) for p in ((30, 250), (51, 172), (78, 154), (137, 154), (164, 172), (187, 250))], fill=(14, 29, 42, 255))
        d.line([box((49, 174, 107, 249))[:2], box((107, 249, 166, 174))[:2]], fill=(42, 201, 218, 255), width=2 * scale)
        d.ellipse(box((96, 204, 119, 227)), outline=(53, 214, 231, 255), width=2 * scale)
        d.ellipse(box((103, 211, 112, 220)), fill=(53, 214, 231, 255))
        d.rounded_rectangle(box((86, 136, 129, 177)), radius=14 * scale, fill=(207, 139, 102, 255))
        d.ellipse(box((55, 36, 160, 164)), fill=(230, 166, 127, 255), outline=(88, 46, 34, 255), width=2 * scale)
        hair = (75, 43, 29, 255)
        hair_hi = (116, 72, 43, 255)
        d.pieslice(box((39, 15, 174, 145)), 170, 370, fill=hair)
        d.polygon([tuple(v * scale for v in p) for p in ((43, 82), (48, 151), (67, 170), (73, 101), (57, 57))], fill=hair)
        d.polygon([tuple(v * scale for v in p) for p in ((170, 76), (167, 151), (149, 170), (146, 95), (160, 48))], fill=hair)
        d.pieslice(box((52, 18, 145, 93)), 185, 355, fill=hair)
        d.pieslice(box((86, 17, 172, 92)), 175, 345, fill=hair)
        d.arc(box((52, 23, 145, 104)), 205, 340, fill=hair_hi, width=4 * scale)
        d.arc(box((83, 20, 167, 109)), 190, 330, fill=hair_hi, width=3 * scale)
        d.arc(box((72, 80, 96, 97)), 200, 335, fill=(76, 42, 30, 255), width=3 * scale)
        d.arc(box((119, 80, 143, 97)), 205, 340, fill=(76, 42, 30, 255), width=3 * scale)
        if state == "blink":
            d.arc(box((73, 93, 96, 102)), 185, 355, fill=(39, 30, 29, 255), width=2 * scale)
            d.arc(box((119, 93, 142, 102)), 185, 355, fill=(39, 30, 29, 255), width=2 * scale)
        else:
            d.ellipse(box((73, 91, 97, 108)), fill=(248, 246, 238, 255), outline=(52, 35, 31, 255), width=scale)
            d.ellipse(box((118, 91, 142, 108)), fill=(248, 246, 238, 255), outline=(52, 35, 31, 255), width=scale)
            d.ellipse(box((82, 94, 91, 106)), fill=(87, 55, 34, 255))
            d.ellipse(box((127, 94, 136, 106)), fill=(87, 55, 34, 255))
            d.ellipse(box((85, 96, 89, 104)), fill=(20, 18, 18, 255))
            d.ellipse(box((130, 96, 134, 104)), fill=(20, 18, 18, 255))
            d.line([box((72, 91, 68, 88))[:2]], fill=(38, 28, 27, 255), width=scale)
            d.line([box((143, 91, 147, 88))[:2]], fill=(38, 28, 27, 255), width=scale)
        d.line([box((108, 103, 104, 122))[:2], box((104, 122, 112, 124))[:2]], fill=(176, 104, 76, 255), width=scale)
        d.arc(box((87, 124, 130, 151)), 18, 162, fill=(126, 49, 55, 255), width=2 * scale)
        if state == "talk":
            d.ellipse(box((94, 132, 122, 153)), fill=(91, 31, 38, 255), outline=(126, 49, 55, 255), width=scale)
            d.rectangle(box((98, 134, 118, 140)), fill=(245, 236, 224, 255))
        else:
            d.arc(box((92, 130, 125, 149)), 15, 165, fill=(139, 49, 62, 255), width=2 * scale)
        d.ellipse(box((66, 117, 81, 126)), fill=(230, 142, 126, 70))
        d.ellipse(box((134, 117, 149, 126)), fill=(230, 142, 126, 70))
        d.polygon([tuple(v * scale for v in p) for p in ((76, 155), (107, 181), (93, 197), (58, 169))], fill=(19, 44, 58, 255))
        d.polygon([tuple(v * scale for v in p) for p in ((139, 155), (107, 181), (122, 197), (157, 169))], fill=(19, 44, 58, 255))
        return image.resize((215, 255), Image.Resampling.LANCZOS).convert("RGB")


def self_test() -> int:
    with tempfile.TemporaryDirectory(prefix="sarah-2.6-repair-", ignore_cleanup_errors=True) as folder:
        database = SarahDatabase(Path(folder))
        database.ensure_profile("Robert", 45, "Newark", "Power Rangers", True)
        client = ResilientModelClient(database)
        response = client.respond("I am thinking about going to Mexico")
        if "Mexico" not in response.spoken or not database.list_rows("trips", limit=5):
            raise RuntimeError("Offline trip conversation failed")
        image = SarahRepairApp._render_avatar("neutral")
        if image.size != (215, 255):
            raise RuntimeError("Avatar render failed")
        token = secrets.token_urlsafe(32)
        message = json.dumps({"repair": True, "time": int(time.time())})
        encrypted = sync_encrypt(token, message)
        if sync_decrypt(token, encrypted) != message or not sync_signature(token, encrypted):
            raise RuntimeError("Encrypted sync test failed")
        server = SarahSyncServer(database, host="127.0.0.1", port=0, device_name="Sarah repair self-test")
        request_id = server.create_pair_request(
            device_id="test-phone",
            device_name="Test phone",
            device_type="android-phone",
            verification_code="123456",
            remote_address="127.0.0.1",
        )
        if not server.approve_request(request_id):
            raise RuntimeError("Pair approval failed")
        server.stop()
        del server, client, database
        gc.collect()
    print("SARAH_2_6_WINDOWS_REPAIR_SELF_TEST_OK")
    return 0


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        raise SystemExit(self_test())
    SarahRepairApp().run()
