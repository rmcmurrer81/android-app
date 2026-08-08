from __future__ import annotations

import gc
import json
from pathlib import Path
import secrets
import socket
import sys
import tempfile
import time
import tkinter as tk
from tkinter import messagebox, simpledialog, ttk
import webbrowser

from PIL import Image

from sarah_live_avatar import AudioEnvelope, AvatarMotionModel, PortraitFrameRenderer
from sarah_core import (
    SarahDatabase, bundled_event_config_path, load_bundled_event_config,
    runtime_setting, sync_decrypt, sync_encrypt, sync_signature,
)
from sarah_sync_server import SarahSyncServer
from sarah_windows import (
    SARAH_PORTRAIT_DISPLAY_SIZE,
    SarahApp,
    portrait_packaged_self_test,
    resolve_sarah_portrait,
)


class SarahEventReadyApp(SarahApp):
    """Futuristic public shell over Sarah's complete Windows companion."""

    def __init__(self):
        self._seen_pair_requests: set[str] = set()
        self._pending_rows: list[dict] = []
        super().__init__()
        self.root.title("Sarah Travel OS")

    def _build_ui(self):
        self.root.configure(bg="#07131f")
        style = ttk.Style(self.root)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure("TNotebook", background="#07131f", borderwidth=0)
        style.configure("TNotebook.Tab", padding=(13, 7), font=("Segoe UI", 10, "bold"))
        style.map("TNotebook.Tab", background=[("selected", "#1a6d82")], foreground=[("selected", "white")])
        style.configure("Accent.TButton", font=("Segoe UI", 10, "bold"), padding=(11, 7))

        header = tk.Frame(self.root, bg="#0b1b2b", height=70)
        header.pack(fill="x")
        header.pack_propagate(False)

        logo = tk.Canvas(header, width=62, height=62, bg="#0b1b2b", highlightthickness=0)
        logo.pack(side="left", padx=(12, 4), pady=4)
        self._draw_orbit_logo(logo)

        configured = bool(runtime_setting("SARAH_MODEL_BACKEND_URL", root=self.db.root))
        initial_route = (
            "Connected route configured • verifying with the next message"
            if configured else "Offline mind ready • no connected route configured"
        )
        self.status = tk.StringVar(value=initial_route + " • device sync setup required")
        status_label = tk.Label(
            header,
            textvariable=self.status,
            fg="#e9fcff",
            bg="#123448",
            font=("Segoe UI", 10),
            anchor="w",
            padx=13,
            pady=9,
        )
        status_label.pack(side="left", fill="x", expand=True, padx=8, pady=14)

        ttk.Button(header, text="Hide", command=self.hide_to_tray, style="Accent.TButton").pack(side="right", padx=(4, 12), pady=14)
        ttk.Button(header, text="Online setup", command=self.configure_online_services, style="Accent.TButton").pack(side="right", padx=4, pady=14)

        tools = tk.Frame(self.root, bg="#102b3d", height=52)
        tools.pack(fill="x")
        tools.pack_propagate(False)
        self._quick_button(tools, "MAP & MEDIA", lambda: self.tabs.select(self.discovery_tab))
        self._quick_button(tools, "TRIP PHOTOS", lambda: self.tabs.select(self.photo_tab))
        self._quick_button(tools, "ROUTES & TRIPS", lambda: self.tabs.select(self.trip_tab))
        self._quick_button(tools, "DEVICES & BACKUP", lambda: self.tabs.select(self.device_tab))

        self.tabs = ttk.Notebook(self.root)
        self.tabs.pack(fill="both", expand=True, padx=9, pady=(7, 9))
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
        canvas.create_oval(5, 5, 57, 57, fill="#11283a", outline="#78e8f4", width=2)
        canvas.create_oval(12, 23, 52, 42, outline="#89f3ff", width=2)
        canvas.create_oval(20, 10, 43, 53, outline="#6c8cff", width=2)
        canvas.create_polygon(31, 17, 43, 31, 31, 45, 19, 31, fill="#f6fcff", outline="")
        canvas.create_polygon(31, 23, 38, 31, 31, 39, 24, 31, fill="#36d7e8", outline="")
        canvas.create_oval(47, 11, 54, 18, fill="white", outline="")

    def _quick_button(self, parent: tk.Widget, text: str, command) -> None:
        button = tk.Button(
            parent,
            text=text,
            command=command,
            bg="#174c63",
            fg="#eefcff",
            activebackground="#237d92",
            activeforeground="white",
            relief="flat",
            bd=0,
            font=("Segoe UI", 9, "bold"),
            cursor="hand2",
        )
        button.pack(side="left", fill="both", expand=True, padx=3, pady=7)

    def _build_chat(self):
        hero = tk.Frame(self.chat_tab, bg="#14394d", height=60)
        hero.pack(fill="x", padx=8, pady=(8, 0))
        hero.pack_propagate(False)
        tk.Label(
            hero,
            text="Maps, live public photos, routes, trip context, and calm support appear when they are useful — not as permanent clutter.",
            bg="#14394d",
            fg="#ddf8ff",
            font=("Segoe UI", 10),
            wraplength=820,
            justify="left",
        ).pack(side="left", fill="x", expand=True, padx=14, pady=10)
        tk.Button(
            hero,
            text="Open media",
            command=lambda: self.tabs.select(self.discovery_tab),
            bg="#35bed1",
            fg="#06131d",
            relief="flat",
            font=("Segoe UI", 9, "bold"),
        ).pack(side="right", padx=12, pady=12)

        self.chat = tk.Text(
            self.chat_tab,
            wrap="word",
            font=("Segoe UI", 12),
            bg="#f8fbfc",
            fg="#152a38",
            insertbackground="#152a38",
            padx=16,
            pady=16,
            state="disabled",
            relief="flat",
        )
        self.chat.pack(fill="both", expand=True, padx=8, pady=8)
        row = ttk.Frame(self.chat_tab)
        row.pack(fill="x", padx=8, pady=(0, 8))
        self.entry = ttk.Entry(row, font=("Segoe UI", 12))
        self.entry.pack(side="left", fill="x", expand=True)
        self.entry.bind("<Return>", lambda _event: self.send())
        self.send_button = ttk.Button(row, text="Send", command=self.send, style="Accent.TButton")
        self.send_button.pack(side="left", padx=5)
        self.calm_button = ttk.Button(
            row,
            text="Calm choices",
            command=lambda: self._submit_text("I am stressed and I need calm choices"),
        )
        self.calm_button.pack(side="left")
        self.stop_voice_button = ttk.Button(row, text="Stop voice", command=self.stop_voice)
        self.stop_voice_button.pack(side="left", padx=(5, 0))
        self._append(
            "Sarah",
            "I’m here. We can plan a real trip, compare places to stay, organize your photos, continue from your phone, or talk about absolutely nothing travel-related.",
        )

    def _build_devices(self):
        frame = ttk.Frame(self.device_tab, padding=14)
        frame.pack(fill="both", expand=True)

        tk.Label(frame, text="Trusted device sync", font=("Segoe UI", 17, "bold"), fg="#15394c").pack(anchor="w")
        tk.Label(
            frame,
            text=(
                "Setup required. R2 does not expose a pairing or encryption key over plain local-network HTTP. "
                "Phone/Windows sync remains off until an authenticated TLS or key-agreement path is accepted."
            ),
            font=("Segoe UI", 10),
            wraplength=860,
            justify="left",
        ).pack(anchor="w", pady=(5, 12))

        separator = ttk.Separator(frame, orient="horizontal")
        separator.pack(fill="x", pady=12)
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
                    self.pending_list.insert(
                        "end",
                        f"{request.get('device_name', 'New device')}  •  code {request.get('verification_code', '')}  •  {request.get('remote_address', '')}",
                    )
            for request in pending:
                request_id = str(request.get("request_id", ""))
                if not request_id or request_id in self._seen_pair_requests:
                    continue
                self._seen_pair_requests.add(request_id)
                self._ask_pair_approval(request)
        finally:
            self.root.after(700, self._poll_pair_requests)

    def _ask_pair_approval(self, request: dict) -> None:
        name = str(request.get("device_name", "New device"))
        code = str(request.get("verification_code", ""))
        device_type = str(request.get("device_type", "device"))
        address = str(request.get("remote_address", ""))
        approved = messagebox.askyesno(
            "Approve a new Sarah device?",
            f"Sarah found a pairing request.\n\nDevice: {name}\nType: {device_type}\nAddress: {address}\nVerification code: {code}\n\nApprove only when the other device shows the same code and you recognize its name. After approval Sarah will synchronize approved details in both directions.",
            parent=self.root,
        )
        if approved:
            self.sync_server.approve_request(str(request.get("request_id", "")))
            self.status.set(f"Approved {name}. Waiting for its first encrypted sync.")
        else:
            self.sync_server.deny_request(str(request.get("request_id", "")))
            self.status.set(f"Denied the pairing request from {name}.")

    def _selected_pending(self) -> dict | None:
        if not hasattr(self, "pending_list"):
            return None
        selection = self.pending_list.curselection()
        if not selection:
            return None
        index = int(selection[0])
        return self._pending_rows[index] if 0 <= index < len(self._pending_rows) else None

    def _approve_selected_request(self):
        request = self._selected_pending()
        if not request:
            messagebox.showinfo("Devices", "Select a pending request first.", parent=self.root)
            return
        self._ask_pair_approval(request)

    def _deny_selected_request(self):
        request = self._selected_pending()
        if not request:
            messagebox.showinfo("Devices", "Select a pending request first.", parent=self.root)
            return
        self.sync_server.deny_request(str(request.get("request_id", "")))
        self.status.set(f"Denied {request.get('device_name', 'the new device')}.")


def self_test() -> int:
    portrait_packaged_self_test()
    portrait_path = resolve_sarah_portrait()
    if portrait_path is None:
        raise RuntimeError("Approved Sarah portrait did not resolve for live-animation self-test")
    with Image.open(portrait_path) as portrait:
        portrait.load()
        renderer = PortraitFrameRenderer(portrait, SARAH_PORTRAIT_DISPLAY_SIZE)
    motion = AvatarMotionModel(seed=0x53415241, clock=lambda: 0.0)
    idle_frame = renderer.render(motion.pose_at(0.0))
    motion.start_audio(
        AudioEnvelope((0.0, 0.92), 0.1, 0.2, True, "packaged_self_test_pcm"),
        generation=1,
        now=0.0,
    )
    speaking_frame = renderer.render(motion.pose_at(0.1))
    if idle_frame.tobytes() == speaking_frame.tobytes():
        raise RuntimeError("Packaged Sarah live-avatar renderer produced no visible motion")
    if not motion.stop_speaking(1):
        raise RuntimeError("Packaged Sarah live-avatar generation stop failed")
    bundled_path = bundled_event_config_path()
    if bundled_path.is_file():
        raw_bundled = json.loads(bundled_path.read_text(encoding="utf-8"))
        if not isinstance(raw_bundled, dict):
            raise RuntimeError("Bundled event configuration is not an object")
        forbidden = sorted(
            str(key) for key in raw_bundled
            if any(marker in str(key) for marker in ("TOKEN", "API_KEY", "PASSWORD", "SECRET"))
        )
        if forbidden:
            raise RuntimeError(
                "Bundled event configuration contains reusable credential fields: "
                + ", ".join(forbidden)
            )
        bundled = load_bundled_event_config(bundled_path)
        if not bundled.get("SARAH_MODEL_BACKEND_URL", "").startswith("https://"):
            raise RuntimeError("Bundled event model backend URL is absent or is not HTTPS")
        if not bundled.get("SARAH_MODEL_PROVIDER") or not bundled.get("SARAH_MODEL_ID"):
            raise RuntimeError("Bundled event model provider configuration is incomplete")
    with tempfile.TemporaryDirectory(prefix="sarah-event-ready-", ignore_cleanup_errors=True) as folder:
        database = SarahDatabase(Path(folder))
        token = secrets.token_urlsafe(32)
        message = json.dumps({"event_ready": True, "time": int(time.time())})
        encrypted = sync_encrypt(token, message)
        if sync_decrypt(token, encrypted) != message:
            raise RuntimeError("Encrypted sync round-trip failed")
        if not sync_signature(token, encrypted):
            raise RuntimeError("Sync signature was empty")
        server = SarahSyncServer(database, host="127.0.0.1", port=0, device_name="Sarah self-test")
        request_id = server.create_pair_request(
            device_id="test-phone",
            device_name="Test phone",
            device_type="android-phone",
            verification_code="123456",
            remote_address="127.0.0.1",
        )
        if not server.approve_request(request_id):
            raise RuntimeError("Approval gate failed")
        state = server.pair_status(request_id, "test-phone")
        if state.get("status") != "approved" or not state.get("token"):
            raise RuntimeError("Approved request did not produce a trusted token")
        del state, server, database
        gc.collect()
        if sys.platform.startswith("win"):
            time.sleep(0.15)
    print("SARAH_EVENT_READY_SELF_TEST_OK")
    return 0


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        raise SystemExit(self_test())
    SarahEventReadyApp().run()
