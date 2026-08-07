from __future__ import annotations

import gc
import json
import math
from pathlib import Path
import secrets
import sys
import tempfile
import threading
import time
import tkinter as tk
from tkinter import messagebox, ttk
import urllib.parse
import webbrowser

from PIL import Image, ImageDraw, ImageTk

from sarah_conversation import ConversationEngine
from sarah_core import SarahDatabase, sync_decrypt, sync_encrypt, sync_signature
from sarah_sync_server import SarahSyncServer
from sarah_voice import WindowsVoiceEngine
from sarah_windows import SarahApp


BG = "#07131f"
PANEL = "#0d2232"
PANEL_2 = "#123448"
TEAL = "#35bed1"
TEAL_LIGHT = "#9cf5ff"
TEXT = "#e9fcff"
MUTED = "#a6c7d2"


def render_avatar_frame(expression: str = "neutral", width: int = 250, height: int = 410) -> Image.Image:
    """Render Sarah as a layered, anti-aliased humanoid cartoon instead of a placeholder icon."""
    scale = 4
    w, h = width * scale, height * scale
    image = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    def box(values):
        return tuple(int(value * scale) for value in values)

    def points(values):
        return [(int(x * scale), int(y * scale)) for x, y in values]

    # Soft cyan floor glow and shadow.
    draw.ellipse(box((42, 372, 208, 405)), fill=(18, 210, 225, 32))
    draw.ellipse(box((70, 382, 184, 400)), fill=(0, 0, 0, 75))

    # Legs and boots.
    pants = (18, 30, 39, 255)
    pants_light = (27, 43, 55, 255)
    draw.rounded_rectangle(box((79, 267, 121, 378)), radius=13 * scale, fill=pants)
    draw.rounded_rectangle(box((130, 267, 172, 378)), radius=13 * scale, fill=pants)
    draw.polygon(points(((88, 281), (118, 281), (112, 370), (84, 370))), fill=pants_light)
    draw.polygon(points(((136, 281), (166, 281), (168, 370), (140, 370))), fill=pants_light)
    draw.rounded_rectangle(box((65, 360, 122, 391)), radius=10 * scale, fill=(9, 15, 22, 255))
    draw.rounded_rectangle(box((129, 360, 190, 391)), radius=10 * scale, fill=(9, 15, 22, 255))
    draw.line(points(((80, 377), (116, 377))), fill=(52, 202, 218, 210), width=2 * scale)
    draw.line(points(((142, 377), (181, 377))), fill=(52, 202, 218, 210), width=2 * scale)

    # Hair behind the head, made from overlapping shapes for a softer wavy silhouette.
    hair_dark = (64, 39, 29, 255)
    hair_mid = (91, 55, 35, 255)
    hair_light = (126, 77, 43, 220)
    draw.ellipse(box((57, 27, 194, 169)), fill=hair_dark)
    for cx, cy, rx, ry in [
        (64, 78, 29, 62), (78, 45, 35, 43), (104, 33, 39, 38),
        (142, 35, 43, 42), (171, 61, 34, 58), (180, 100, 25, 58),
        (68, 123, 27, 50), (93, 143, 30, 38), (151, 142, 31, 39),
    ]:
        draw.ellipse(box((cx-rx, cy-ry, cx+rx, cy+ry)), fill=hair_mid)

    # Neck and ears.
    skin = (226, 171, 132, 255)
    skin_light = (241, 195, 158, 255)
    skin_shadow = (196, 132, 103, 255)
    draw.rounded_rectangle(box((108, 142, 145, 196)), radius=12 * scale, fill=skin)
    draw.ellipse(box((55, 89, 78, 126)), fill=skin)
    draw.ellipse(box((175, 89, 198, 126)), fill=skin)

    # Body and fitted futuristic jacket.
    jacket = (13, 28, 40, 255)
    jacket_mid = (18, 44, 58, 255)
    jacket_edge = (45, 188, 209, 255)
    draw.polygon(points(((83, 172), (105, 157), (146, 157), (172, 173), (190, 283), (61, 283))), fill=jacket)
    draw.rounded_rectangle(box((67, 174, 184, 286)), radius=28 * scale, fill=jacket_mid)
    draw.polygon(points(((66, 180), (47, 203), (32, 291), (57, 300), (91, 213))), fill=jacket)
    draw.polygon(points(((184, 180), (203, 203), (218, 291), (193, 300), (159, 213))), fill=jacket)
    draw.line(points(((124, 162), (126, 282))), fill=jacket_edge, width=3 * scale)
    draw.line(points(((83, 181), (67, 275))), fill=(36, 118, 139, 255), width=2 * scale)
    draw.line(points(((168, 181), (184, 275))), fill=(36, 118, 139, 255), width=2 * scale)
    draw.rounded_rectangle(box((101, 164, 151, 188)), radius=11 * scale, fill=(9, 21, 31, 255))
    draw.arc(box((111, 185, 141, 215)), 0, 360, fill=TEAL, width=2 * scale)
    draw.ellipse(box((121, 195, 131, 205)), fill=(110, 245, 255, 255))

    # Hands.
    draw.ellipse(box((29, 284, 60, 318)), fill=skin)
    draw.ellipse(box((190, 284, 221, 318)), fill=skin)
    draw.line(points(((38, 294), (52, 307))), fill=skin_shadow, width=2 * scale)
    draw.line(points(((211, 294), (197, 307))), fill=skin_shadow, width=2 * scale)

    # Face and cheek shading.
    draw.ellipse(box((70, 49, 184, 158)), fill=skin)
    draw.ellipse(box((82, 58, 172, 145)), fill=skin_light)
    draw.ellipse(box((83, 112, 104, 131)), fill=(237, 150, 145, 38))
    draw.ellipse(box((151, 112, 172, 131)), fill=(237, 150, 145, 38))

    # Hair framing the face and layered highlights.
    draw.pieslice(box((58, 35, 190, 142)), 184, 354, fill=hair_mid)
    draw.polygon(points(((68, 48), (96, 33), (117, 42), (91, 84), (70, 109))), fill=hair_mid)
    draw.polygon(points(((118, 40), (155, 31), (181, 55), (184, 111), (166, 84), (150, 60))), fill=hair_mid)
    draw.arc(box((70, 38, 153, 115)), 205, 330, fill=hair_light, width=4 * scale)
    draw.arc(box((113, 31, 181, 116)), 200, 326, fill=hair_light, width=3 * scale)

    # Brows and eyes.
    draw.arc(box((88, 78, 116, 96)), 190, 338, fill=(79, 48, 35, 255), width=3 * scale)
    draw.arc(box((139, 78, 167, 96)), 202, 350, fill=(79, 48, 35, 255), width=3 * scale)
    blink = expression == "blink"
    if blink:
        draw.arc(box((90, 92, 116, 108)), 185, 355, fill=(40, 31, 30, 255), width=3 * scale)
        draw.arc(box((139, 92, 165, 108)), 185, 355, fill=(40, 31, 30, 255), width=3 * scale)
    else:
        draw.ellipse(box((90, 91, 116, 111)), fill=(255, 255, 255, 255))
        draw.ellipse(box((139, 91, 165, 111)), fill=(255, 255, 255, 255))
        gaze = 2 if expression == "talk" else 0
        for left in (101, 150):
            draw.ellipse(box((left-5+gaze, 96, left+7+gaze, 108)), fill=(91, 67, 45, 255))
            draw.ellipse(box((left-1+gaze, 98, left+5+gaze, 107)), fill=(22, 25, 28, 255))
            draw.ellipse(box((left+1+gaze, 98, left+3+gaze, 101)), fill=(255, 255, 255, 230))
        draw.arc(box((89, 88, 117, 113)), 190, 350, fill=(37, 29, 29, 255), width=2 * scale)
        draw.arc(box((138, 88, 166, 113)), 190, 350, fill=(37, 29, 29, 255), width=2 * scale)

    # Nose.
    draw.line(points(((128, 102), (124, 119), (132, 121))), fill=skin_shadow, width=2 * scale)

    # Mouth and expression.
    if expression == "talk":
        draw.ellipse(box((113, 128, 143, 148)), fill=(103, 39, 48, 255))
        draw.arc(box((115, 130, 141, 145)), 180, 360, fill=(250, 226, 218, 255), width=3 * scale)
    elif expression == "smile":
        draw.arc(box((108, 124, 148, 148)), 12, 168, fill=(133, 52, 65, 255), width=3 * scale)
        draw.arc(box((112, 128, 144, 144)), 15, 165, fill=(255, 247, 242, 255), width=2 * scale)
    else:
        draw.arc(box((111, 126, 145, 144)), 18, 162, fill=(133, 52, 65, 255), width=3 * scale)

    # Small jacket highlights and seams.
    draw.line(points(((92, 222), (83, 274))), fill=(33, 75, 89, 255), width=2 * scale)
    draw.line(points(((158, 222), (167, 274))), fill=(33, 75, 89, 255), width=2 * scale)
    draw.arc(box((68, 169, 184, 286)), 190, 350, fill=(77, 222, 235, 110), width=2 * scale)

    return image.resize((width, height), Image.Resampling.LANCZOS)


class SarahWindowsRepairApp(SarahApp):
    """Responsive Windows shell created from the failures seen in Robert's screenshots."""

    def __init__(self):
        self._seen_pair_requests: set[str] = set()
        self._pending_rows: list[dict] = []
        self._media_visible = False
        self._companion_pinned = False
        self._avatar_expression = "neutral"
        self._avatar_tick = 0
        self.voice_engine: WindowsVoiceEngine | None = None
        super().__init__()
        self.root.title("Sarah Travel OS")
        self.model = ConversationEngine(self.db)
        self.voice_engine = WindowsVoiceEngine()
        self.root.after(350, self._post_startup)
        self.root.after(700, self._poll_pair_requests)

    def _post_startup(self):
        mode = self.model.mode
        voice = self.voice_engine.preferred_mode if self.voice_engine else "Voice unavailable"
        self.status.set(f"Ready • {mode}")
        self.voice_state.set(voice)
        self._refresh_media_context()

    def _build_ui(self):
        self.root.configure(bg=BG)
        self.root.geometry("1180x760")
        self.root.minsize(860, 560)
        self.root.grid_rowconfigure(2, weight=1)
        self.root.grid_columnconfigure(0, weight=1)

        style = ttk.Style(self.root)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure("TNotebook", background=BG, borderwidth=0)
        style.configure("TNotebook.Tab", padding=(13, 7), font=("Segoe UI", 10, "bold"))
        style.map("TNotebook.Tab", background=[("selected", "#1a6d82")], foreground=[("selected", "white")])
        style.configure("Accent.TButton", font=("Segoe UI", 10, "bold"), padding=(10, 7))

        header = tk.Frame(self.root, bg="#0b1b2b", height=68)
        header.grid(row=0, column=0, sticky="ew")
        header.grid_propagate(False)
        header.grid_columnconfigure(1, weight=1)

        logo = tk.Canvas(header, width=54, height=54, bg="#0b1b2b", highlightthickness=0)
        logo.grid(row=0, column=0, padx=(10, 4), pady=6)
        self._draw_orbit_logo(logo)

        status_frame = tk.Frame(header, bg=PANEL_2)
        status_frame.grid(row=0, column=1, sticky="ew", padx=7, pady=12)
        status_frame.grid_columnconfigure(0, weight=1)
        self.status = tk.StringVar(value="Starting Sarah’s local mind…")
        self.voice_state = tk.StringVar(value="Checking voice…")
        tk.Label(status_frame, textvariable=self.status, fg=TEXT, bg=PANEL_2, font=("Segoe UI", 10), anchor="w").grid(row=0, column=0, sticky="ew", padx=12, pady=(7, 0))
        tk.Label(status_frame, textvariable=self.voice_state, fg=TEAL_LIGHT, bg=PANEL_2, font=("Segoe UI", 9), anchor="w").grid(row=1, column=0, sticky="ew", padx=12, pady=(0, 7))

        ttk.Button(header, text="Test voice", command=self.test_voice, style="Accent.TButton").grid(row=0, column=2, padx=4, pady=12)
        ttk.Button(header, text="Companion", command=self.toggle_companion, style="Accent.TButton").grid(row=0, column=3, padx=4, pady=12)
        ttk.Button(header, text="Hide", command=self.hide_to_tray, style="Accent.TButton").grid(row=0, column=4, padx=(4, 10), pady=12)

        tools = tk.Frame(self.root, bg="#102b3d", height=48)
        tools.grid(row=1, column=0, sticky="ew")
        tools.grid_propagate(False)
        for index in range(4):
            tools.grid_columnconfigure(index, weight=1, uniform="tool")
        self._quick_button(tools, 0, "MAP & MEDIA", self._toggle_media)
        self._quick_button(tools, 1, "TRIP PHOTOS", lambda: self.tabs.select(self.photo_tab))
        self._quick_button(tools, 2, "ROUTES & TRIPS", lambda: self.tabs.select(self.trip_tab))
        self._quick_button(tools, 3, "DEVICES & SYNC", lambda: self.tabs.select(self.device_tab))

        self.tabs = ttk.Notebook(self.root)
        self.tabs.grid(row=2, column=0, sticky="nsew", padx=8, pady=(7, 8))
        self.chat_tab = ttk.Frame(self.tabs)
        self.discovery_tab = ttk.Frame(self.tabs)
        self.trip_tab = ttk.Frame(self.tabs)
        self.photo_tab = ttk.Frame(self.tabs)
        self.device_tab = ttk.Frame(self.tabs)
        self.activity_tab = ttk.Frame(self.tabs)
        self.tabs.add(self.chat_tab, text="Talk")
        self.tabs.add(self.discovery_tab, text="Discover")
        self.tabs.add(self.trip_tab, text="Trips & routes")
        self.tabs.add(self.photo_tab, text="Photos")
        self.tabs.add(self.device_tab, text="Devices")
        self.tabs.add(self.activity_tab, text="Factual activity")
        self._build_chat()
        self._build_discoveries()
        self._build_trips()
        self._build_photos()
        self._build_devices()
        self._build_activity()

    def _draw_orbit_logo(self, canvas: tk.Canvas) -> None:
        canvas.create_oval(4, 4, 50, 50, fill="#11283a", outline="#78e8f4", width=2)
        canvas.create_oval(10, 20, 46, 37, outline="#89f3ff", width=2)
        canvas.create_oval(18, 8, 39, 47, outline="#6c8cff", width=2)
        canvas.create_polygon(27, 15, 38, 27, 27, 39, 16, 27, fill="#f6fcff", outline="")
        canvas.create_polygon(27, 21, 33, 27, 27, 33, 21, 27, fill=TEAL, outline="")
        canvas.create_oval(43, 8, 49, 14, fill="white", outline="")

    def _quick_button(self, parent: tk.Widget, column: int, text: str, command) -> None:
        button = tk.Button(parent, text=text, command=command, bg="#174c63", fg="#eefcff", activebackground="#237d92", activeforeground="white", relief="flat", bd=0, font=("Segoe UI", 9, "bold"), cursor="hand2")
        button.grid(row=0, column=column, sticky="nsew", padx=3, pady=6)

    def _build_chat(self):
        self.chat_tab.grid_rowconfigure(1, weight=1)
        self.chat_tab.grid_columnconfigure(0, weight=1)

        self.context_var = tk.StringVar(value="Conversation ready • maps and photos open only when useful")
        context = tk.Frame(self.chat_tab, bg="#14394d", height=42)
        context.grid(row=0, column=0, sticky="ew", padx=7, pady=(7, 0))
        context.grid_propagate(False)
        context.grid_columnconfigure(0, weight=1)
        tk.Label(context, textvariable=self.context_var, bg="#14394d", fg="#ddf8ff", font=("Segoe UI", 9), anchor="w").grid(row=0, column=0, sticky="ew", padx=12, pady=9)
        tk.Button(context, text="Media", command=self._toggle_media, bg=TEAL, fg="#06131d", relief="flat", font=("Segoe UI", 9, "bold")).grid(row=0, column=1, padx=9, pady=7)

        body = tk.Frame(self.chat_tab, bg="#e9eef1")
        body.grid(row=1, column=0, sticky="nsew", padx=7, pady=7)
        body.grid_rowconfigure(0, weight=1)
        body.grid_columnconfigure(0, weight=1)
        body.grid_columnconfigure(1, weight=0)

        chat_frame = tk.Frame(body, bg="#f8fbfc")
        chat_frame.grid(row=0, column=0, sticky="nsew")
        chat_frame.grid_rowconfigure(0, weight=1)
        chat_frame.grid_columnconfigure(0, weight=1)
        self.chat = tk.Text(chat_frame, wrap="word", font=("Segoe UI", 12), bg="#f8fbfc", fg="#152a38", insertbackground="#152a38", padx=18, pady=18, state="disabled", relief="flat")
        self.chat.grid(row=0, column=0, sticky="nsew")
        scrollbar = ttk.Scrollbar(chat_frame, orient="vertical", command=self.chat.yview)
        scrollbar.grid(row=0, column=1, sticky="ns")
        self.chat.configure(yscrollcommand=scrollbar.set)
        self.chat.tag_configure("who", foreground="#176a7c", font=("Segoe UI", 10, "bold"), spacing1=8)
        self.chat.tag_configure("message", foreground="#152a38", font=("Segoe UI", 12), lmargin1=4, lmargin2=4, spacing3=10)

        self.media_panel = tk.Frame(body, bg=PANEL, width=300)
        self.media_panel.grid(row=0, column=1, sticky="ns", padx=(7, 0))
        self.media_panel.grid_propagate(False)
        tk.Label(self.media_panel, text="TRIP CONTEXT", bg=PANEL, fg=TEAL_LIGHT, font=("Segoe UI", 11, "bold")).pack(anchor="w", padx=14, pady=(15, 3))
        self.media_destination = tk.StringVar(value="No active destination")
        tk.Label(self.media_panel, textvariable=self.media_destination, bg=PANEL, fg=TEXT, font=("Segoe UI", 14, "bold"), wraplength=265, justify="left").pack(anchor="w", padx=14, pady=(0, 12))
        tk.Button(self.media_panel, text="Open map", command=lambda: self._open_destination("map"), bg="#174c63", fg=TEXT, relief="flat").pack(fill="x", padx=14, pady=4)
        tk.Button(self.media_panel, text="Public photos", command=lambda: self._open_destination("photos"), bg="#174c63", fg=TEXT, relief="flat").pack(fill="x", padx=14, pady=4)
        tk.Button(self.media_panel, text="Travel videos", command=lambda: self._open_destination("videos"), bg="#174c63", fg=TEXT, relief="flat").pack(fill="x", padx=14, pady=4)
        tk.Button(self.media_panel, text="Close media", command=self._toggle_media, bg="#233847", fg=MUTED, relief="flat").pack(fill="x", padx=14, pady=(18, 4))
        self.media_panel.grid_remove()

        composer = tk.Frame(self.chat_tab, bg="#dbe5e9", height=66)
        composer.grid(row=2, column=0, sticky="ew", padx=7, pady=(0, 7))
        composer.grid_propagate(False)
        composer.grid_columnconfigure(0, weight=1)
        self.entry = ttk.Entry(composer, font=("Segoe UI", 12))
        self.entry.grid(row=0, column=0, sticky="ew", padx=(9, 5), pady=12, ipady=7)
        self.entry.bind("<Return>", lambda _event: self.send())
        ttk.Button(composer, text="Send", command=self.send, style="Accent.TButton").grid(row=0, column=1, padx=4, pady=10)
        ttk.Button(composer, text="Calm choices", command=lambda: self._submit_text("I am stressed and I need calm choices")).grid(row=0, column=2, padx=(4, 9), pady=10)

        self._append("Sarah", "I’m here. We can talk normally, plan a real trip, compare places to stay, organize your photos, or continue from your phone.")

    def _append(self, who: str, text: str):
        self.chat.configure(state="normal")
        self.chat.insert("end", who + "\n", "who")
        self.chat.insert("end", text + "\n", "message")
        self.chat.see("end")
        self.chat.configure(state="disabled")

    def _toggle_media(self):
        self._media_visible = not self._media_visible
        if self._media_visible:
            self._refresh_media_context()
            self.media_panel.grid()
        else:
            self.media_panel.grid_remove()

    def _refresh_media_context(self):
        trips = self.db.list_rows("trips", limit=1)
        destination = str(trips[0].get("destination", "")).strip() if trips else ""
        self.media_destination.set(destination or "No active destination")
        self.context_var.set(f"Current trip context • {destination}" if destination else "Conversation ready • maps and photos open only when useful")

    def _open_destination(self, kind: str):
        destination = self.media_destination.get().strip()
        if not destination or destination == "No active destination":
            messagebox.showinfo("Trip context", "Save or discuss a destination first, then Sarah can open matching map and media searches.", parent=self.root)
            return
        query = urllib.parse.quote_plus(destination)
        urls = {
            "map": f"https://www.google.com/maps/search/?api=1&query={query}",
            "photos": f"https://www.google.com/search?tbm=isch&q={query}+travel",
            "videos": f"https://www.youtube.com/results?search_query={query}+travel+guide",
        }
        webbrowser.open(urls[kind])

    def _poll_tasks(self):
        try:
            while True:
                kind, payload = self.tasks.get_nowait()
                if kind == "reply":
                    response = payload
                    self._append("Sarah", response.spoken)
                    self.status.set(f"Ready • {self.model.mode}")
                    self._speak(response.spoken)
                    self.refresh_activity()
                    self._refresh_media_context()
                elif kind == "research":
                    self.status.set(str(payload))
                    self.refresh_discoveries()
                elif kind == "voice_status":
                    self.voice_state.set(str(payload))
        except Exception as error:
            if error.__class__.__name__ != "Empty":
                self.voice_state.set(f"Voice status error • {error}")
        self.root.after(100, self._poll_tasks)

    def _speak_worker(self, text: str):
        self.speaking = True
        try:
            if not self.voice_engine:
                self.tasks.put(("voice_status", "Voice engine is starting"))
                return
            result = self.voice_engine.speak(text)
            label = result.mode if result.ok else f"Voice failed • {result.detail}"
            self.tasks.put(("voice_status", label))
        finally:
            self.speaking = False

    def test_voice(self):
        self.voice_state.set("Testing voice…")
        def worker():
            result = self.voice_engine.self_test() if self.voice_engine else None
            if result is None:
                self.tasks.put(("voice_status", "Voice engine unavailable"))
            elif result.ok:
                self.tasks.put(("voice_status", f"Voice test passed • {result.mode}"))
            else:
                self.tasks.put(("voice_status", f"Voice test failed • {result.detail}"))
        threading.Thread(target=worker, daemon=True).start()

    def _build_devices(self):
        frame = ttk.Frame(self.device_tab, padding=14)
        frame.pack(fill="both", expand=True)
        tk.Label(frame, text="Automatic private-Wi-Fi discovery", font=("Segoe UI", 17, "bold"), fg="#15394c").pack(anchor="w")
        tk.Label(frame, text="A nearby Sarah installation can request pairing, but nothing is copied until this trusted device shows the same code and you approve the named device.", font=("Segoe UI", 10), wraplength=860, justify="left").pack(anchor="w", pady=(5, 12))
        self.pending_list = tk.Listbox(frame, height=5, font=("Segoe UI", 10))
        self.pending_list.pack(fill="x", pady=(0, 8))
        pending_buttons = ttk.Frame(frame)
        pending_buttons.pack(fill="x", pady=(0, 12))
        ttk.Button(pending_buttons, text="Approve selected", command=self._approve_selected_request).pack(side="left")
        ttk.Button(pending_buttons, text="Deny selected", command=self._deny_selected_request).pack(side="left", padx=6)
        ttk.Separator(frame, orient="horizontal").pack(fill="x", pady=7)
        self.pair_var = tk.StringVar(value=self.sync_server.pairing_code)
        ttk.Label(frame, text="Manual fallback pairing code", font=("Segoe UI", 12, "bold")).pack(anchor="w")
        ttk.Label(frame, textvariable=self.pair_var, font=("Consolas", 25, "bold")).pack(anchor="w")
        ttk.Label(frame, text=f"Windows address: {self.local_ip()}:8769\nUse this only when the Wi-Fi blocks automatic discovery.").pack(anchor="w", pady=5)
        ttk.Button(frame, text="Rotate manual code", command=self.rotate_code).pack(anchor="w")
        ttk.Separator(frame, orient="horizontal").pack(fill="x", pady=12)
        ttk.Button(frame, text="Export encrypted .sarahmind backup", command=self.backup).pack(anchor="w", pady=3)
        ttk.Button(frame, text="Restore encrypted backup", command=self.restore).pack(anchor="w", pady=3)
        ttk.Button(frame, text="Upload encrypted backup to Google Drive appDataFolder", command=self.drive_backup).pack(anchor="w", pady=3)
        ttk.Button(frame, text="Restore newest encrypted Google Drive backup", command=self.drive_restore).pack(anchor="w", pady=3)
        ttk.Button(frame, text="Revoke a paired device", command=self.revoke_device).pack(anchor="w", pady=3)

    def _poll_pair_requests(self):
        try:
            pending = self.sync_server.pending_requests()
            self._pending_rows = pending
            if hasattr(self, "pending_list"):
                self.pending_list.delete(0, "end")
                for request in pending:
                    self.pending_list.insert("end", f"{request.get('device_name', 'New device')} • code {request.get('verification_code', '')} • {request.get('remote_address', '')}")
            for request in pending:
                request_id = str(request.get("request_id", ""))
                if request_id and request_id not in self._seen_pair_requests:
                    self._seen_pair_requests.add(request_id)
                    self._ask_pair_approval(request)
        finally:
            self.root.after(700, self._poll_pair_requests)

    def _ask_pair_approval(self, request: dict):
        name = str(request.get("device_name", "New device"))
        code = str(request.get("verification_code", ""))
        approved = messagebox.askyesno("Approve a new Sarah device?", f"Device: {name}\nType: {request.get('device_type', 'device')}\nAddress: {request.get('remote_address', '')}\nVerification code: {code}\n\nApprove only when the other device shows the same code and you recognize its name.", parent=self.root)
        if approved:
            self.sync_server.approve_request(str(request.get("request_id", "")))
            self.status.set(f"Approved {name} • waiting for first encrypted sync")
        else:
            self.sync_server.deny_request(str(request.get("request_id", "")))
            self.status.set(f"Denied pairing request from {name}")

    def _selected_pending(self):
        selection = self.pending_list.curselection() if hasattr(self, "pending_list") else ()
        if not selection:
            return None
        index = int(selection[0])
        return self._pending_rows[index] if 0 <= index < len(self._pending_rows) else None

    def _approve_selected_request(self):
        request = self._selected_pending()
        if request:
            self._ask_pair_approval(request)
        else:
            messagebox.showinfo("Devices", "Select a pending request first.", parent=self.root)

    def _deny_selected_request(self):
        request = self._selected_pending()
        if request:
            self.sync_server.deny_request(str(request.get("request_id", "")))
            self.status.set(f"Denied {request.get('device_name', 'the new device')}")
        else:
            messagebox.showinfo("Devices", "Select a pending request first.", parent=self.root)

    def _start_corner(self):
        self.corner = tk.Toplevel(self.root)
        self.corner.title("Sarah")
        self.corner.geometry("278x470")
        self.corner.attributes("-topmost", True)
        self.corner.configure(bg="#08131d")
        self.corner.overrideredirect(True)
        self.avatar_canvas = tk.Canvas(self.corner, width=270, height=420, bg="#08131d", highlightthickness=0)
        self.avatar_canvas.pack(fill="both", expand=True, padx=4, pady=(4, 0))
        self.avatar_canvas.bind("<ButtonPress-1>", self._drag_start)
        self.avatar_canvas.bind("<B1-Motion>", self._drag_move)
        controls = tk.Frame(self.corner, bg="#08131d")
        controls.pack(fill="x", padx=7, pady=7)
        tk.Button(controls, text="Talk", command=self.show, bg=TEAL, fg="#06131d", relief="flat", font=("Segoe UI", 9, "bold")).pack(side="left", fill="x", expand=True)
        tk.Button(controls, text="Hide", command=self.corner.withdraw, bg="#233847", fg=TEXT, relief="flat").pack(side="left", padx=(6, 0))
        self._position_corner()
        self.corner.withdraw()
        self._animate_avatar()

    def _position_corner(self):
        self.root.update_idletasks()
        x = max(0, self.root.winfo_screenwidth() - 300)
        y = max(0, self.root.winfo_screenheight() - 540)
        self.corner.geometry(f"278x470+{x}+{y}")

    def _animate_avatar(self):
        if not getattr(self, "corner", None):
            return
        self._avatar_tick += 1
        if self.speaking:
            expression = "talk" if self._avatar_tick % 2 else "smile"
        elif self._avatar_tick % 31 == 0:
            expression = "blink"
        elif self._avatar_tick % 47 in (0, 1, 2):
            expression = "smile"
        else:
            expression = "neutral"
        frame = render_avatar_frame(expression, 250, 410)
        self._avatar_photo = ImageTk.PhotoImage(frame)
        self.avatar_canvas.delete("all")
        self.avatar_canvas.create_image(135, 210, image=self._avatar_photo)
        self.root.after(180, self._animate_avatar)

    def toggle_companion(self):
        if self.corner.state() == "withdrawn":
            self._position_corner()
            self.corner.deiconify()
            self.corner.lift()
        else:
            self.corner.withdraw()

    def hide_to_tray(self):
        self.root.withdraw()
        self._position_corner()
        self.corner.deiconify()
        self.corner.lift()
        self.status.set("Sarah is active in hidden icons")

    def show(self):
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()
        self.corner.withdraw()
        self.tabs.select(self.chat_tab)
        self.entry.focus_set()


def self_test() -> int:
    frame = render_avatar_frame("talk", 250, 410)
    if frame.size != (250, 410) or frame.getbbox() is None:
        raise RuntimeError("Sarah avatar rendering failed")
    with tempfile.TemporaryDirectory(prefix="sarah-windows-repair-", ignore_cleanup_errors=True) as folder:
        database = SarahDatabase(Path(folder))
        engine = ConversationEngine(database)
        response = engine.respond("How are you?")
        if "model is not configured" in response.spoken.lower() or not response.spoken:
            raise RuntimeError("Offline conversation repair failed")
        trip = engine.respond("I am thinking about going to Mexico")
        if "Mexico" not in trip.spoken:
            raise RuntimeError("Destination-aware offline response failed")
        token = secrets.token_urlsafe(32)
        message = json.dumps({"windows_repair": True, "time": int(time.time())})
        encrypted = sync_encrypt(token, message)
        if sync_decrypt(token, encrypted) != message or not sync_signature(token, encrypted):
            raise RuntimeError("Encrypted sync round-trip failed")
        server = SarahSyncServer(database, host="127.0.0.1", port=0, device_name="Sarah repair self-test")
        request_id = server.create_pair_request(device_id="test-phone", device_name="Test phone", device_type="android-phone", verification_code="123456", remote_address="127.0.0.1")
        if not server.approve_request(request_id):
            raise RuntimeError("Approval gate failed")
        state = server.pair_status(request_id, "test-phone")
        if state.get("status") != "approved" or not state.get("token"):
            raise RuntimeError("Approved request did not produce a trusted token")
        del state, server, engine, database
        gc.collect()
        if sys.platform.startswith("win"):
            time.sleep(0.15)
    print("SARAH_WINDOWS_REPAIR_SELF_TEST_OK")
    return 0


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        raise SystemExit(self_test())
    SarahWindowsRepairApp().run()
