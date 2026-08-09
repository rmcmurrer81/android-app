from __future__ import annotations

import gc
import hashlib
import io
import json
from pathlib import Path
import queue
import secrets
import socket
import sys
import tempfile
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, simpledialog, ttk
from urllib.parse import urlencode
import webbrowser

from PIL import Image, ImageTk

from sarah_live_avatar import (
    AudioEnvelope,
    AvatarPose,
    AvatarMotionModel,
    HIDDEN_AVATAR_POLL_INTERVAL_MS,
    LIVE_AVATAR_FRAME_INTERVAL_MS,
    PortraitFrameRenderer,
)
from sarah_core import (
    ElevenLabsVoice,
    SarahDatabase,
    bundled_event_config_path,
    load_bundled_event_config,
    load_runtime_config,
    needs_owner_identity_confirmation,
    runtime_setting,
    safe_text,
    save_runtime_config,
    sync_decrypt,
    sync_encrypt,
    sync_signature,
)
from sarah_sync_server import SarahSyncServer
from sarah_windows import (
    SARAH_PORTRAIT_DISPLAY_SIZE,
    SarahApp,
    portrait_packaged_self_test,
    pystray,
    resolve_sarah_portrait,
)


try:
    from sarah_gmail import (
        GMAIL_READONLY_SCOPE,
        GmailReadOnlyOAuth,
        GmailTokenVault,
        inspect_desktop_oauth_client,
        list_travel_message_candidates,
        resolve_desktop_oauth_client_path,
        revoke_google_authorization,
    )
except Exception:  # A packaged build without the optional module fails closed.
    GMAIL_READONLY_SCOPE = ""
    GmailReadOnlyOAuth = None
    GmailTokenVault = None
    inspect_desktop_oauth_client = None
    list_travel_message_candidates = None
    resolve_desktop_oauth_client_path = None
    revoke_google_authorization = None

try:
    from sarah_device_pairing import (
        PairingInitiator,
        PairingResponder,
        SarahDiscoveryResponder,
        SarahLocalDiscovery,
        SarahPairingResponderServer,
        begin_pairing_initiator,
    )
except Exception:  # A packaged build without the optional module fails closed.
    PairingInitiator = None
    PairingResponder = None
    SarahDiscoveryResponder = None
    SarahLocalDiscovery = None
    SarahPairingResponderServer = None
    begin_pairing_initiator = None

try:
    from sarah_secure_sync import (
        PairingCredentialVault,
        SarahSecureSyncService,
        import_reviewed_android_preview,
        pull_android_preview,
    )
except Exception:  # Post-trust sync is optional and fails closed in partial packages.
    PairingCredentialVault = None
    SarahSecureSyncService = None
    import_reviewed_android_preview = None
    pull_android_preview = None

try:
    from sarah_wallet import SarahWallet, WalletError, WalletValidationError
except Exception:  # A partial package must keep conversation usable and fail closed.
    SarahWallet = None
    WalletError = RuntimeError
    WalletValidationError = ValueError

try:
    from sarah_calendar import SarahCalendarError, SarahCalendarStore
except Exception:  # A partial package must never invent calendar or reminder state.
    SarahCalendarError = RuntimeError
    SarahCalendarStore = None


SARAH_WINDOWS_OWNER_VERSION = "Sarah 2.5 • current Windows owner build"
OWNER_PAGES = (
    "Talk with Sarah",
    "Travel Workbench",
    "Map & Discover",
    "Trips",
    "Calendar",
    "Photos",
    "Connections",
    "Activity",
)
OWNER_PORTRAIT_SIZE = (286, 286)

PALETTE = {
    "window": "#06121e",
    "header": "#091b2b",
    "rail": "#0b2234",
    "panel": "#0d273a",
    "panel_alt": "#113247",
    "field": "#071927",
    "cyan": "#56d7e7",
    "teal": "#2299ad",
    "teal_dark": "#176f83",
    "orange": "#f3a52d",
    "text": "#f2fbff",
    "muted": "#9fc3d0",
    "soft": "#cae7ef",
    "line": "#1e5268",
    "owner_bubble": "#17455b",
    "sarah_bubble": "#102f42",
}


def event_gmail_available() -> bool:
    """Whether this distribution intentionally exposes Gmail owner controls."""

    return runtime_setting("SARAH_EVENT_GMAIL_AVAILABLE", "1").lower() not in {
        "0", "false", "no", "off",
    }


def _clean_place(value: str) -> str:
    return " ".join(str(value or "").split())[:240]


def openstreetmap_handoff_url(origin: str, destination: str) -> str:
    """Build a browser-only OpenStreetMap handoff without an API key."""

    start = _clean_place(origin)
    finish = _clean_place(destination)
    if not start and not finish:
        raise ValueError("Enter a starting place or destination")
    if start and finish:
        return "https://www.openstreetmap.org/directions?" + urlencode(
            {
                "engine": "fossgis_osrm_car",
                "from": start,
                "to": finish,
            }
        )
    return "https://www.openstreetmap.org/search?" + urlencode(
        {"query": finish or start}
    )


def wikimedia_media_handoff_url(place: str) -> str:
    """Open a source-labeled public-media search without claiming a preview."""

    query = _clean_place(place)
    if not query:
        raise ValueError("Enter a place, landmark, or event")
    return "https://commons.wikimedia.org/w/index.php?" + urlencode(
        {
            "search": query,
            "title": "Special:MediaSearch",
            "type": "image",
        }
    )


def mind_status_text(route: str, configured: bool) -> str:
    exact = safe_text(route)
    if exact.startswith("ONLINE_") and exact != "ONLINE_FAILED_FELL_BACK_OFFLINE":
        return "Mind: online used"
    if exact in {"OFFLINE_LOCAL", "ONLINE_FAILED_FELL_BACK_OFFLINE"}:
        return "Mind: offline used"
    if exact in {"LOCAL_TOOL_RESULT", "PUBLIC_SOURCE_TOOL_RESULT", "TOOL_RESULT"}:
        return "Mind: on-device tool used"
    if exact == "TOOL_UNAVAILABLE":
        return "Mind: source unavailable"
    return "Mind: online set up" if configured else "Mind: offline ready"


def voice_status_text(receipt: dict[str, object] | None, configured: bool) -> str:
    actual = safe_text((receipt or {}).get("actual_route"))
    if actual == "ELEVENLABS":
        return "Voice: ElevenLabs used"
    if actual == "WINDOWS_SYSTEM_SPEECH":
        return "Voice: offline used"
    if actual == "TEXT_ONLY":
        return "Voice: text only"
    return "Voice: ElevenLabs set up" if configured else "Voice: setup needed"


def owner_surface_contract() -> dict[str, object]:
    """Stable, headless contract used by packaging and owner-surface tests."""

    return {
        "version": SARAH_WINDOWS_OWNER_VERSION,
        "pages": OWNER_PAGES,
        "conversation_primary": True,
        "technical_tabs_hidden": True,
        "portrait_required": True,
        "vector_portrait_fallback": False,
        "portrait_motion": ("blink", "head", "eyes", "audio_bound_mouth"),
        "power_saving": "stops_portrait_render_loop_text_and_voice_remain",
        "map_provider": "OpenStreetMap browser handoff",
        "public_media_provider": "Wikimedia Commons browser handoff",
        "gmail_scope": "gmail.readonly",
        "email_calendar": "candidate_then_owner_confirmed_event_then_opt_in_local_reminder",
        "device_trust": "matching_code_confirmed_on_both_devices",
        "owner_wallet": "profile_isolated_dpapi_wrapped_encrypted_records",
        "ticket_images": "sanitized_png_decrypted_in_memory_only",
        "provider_secrets_bundled": False,
        "gpu_required": False,
    }


class SarahEventReadyApp(SarahApp):
    """One owner-facing Sarah 2.5 shell over the existing tested backend."""

    def __init__(self):
        self._last_mind_route = ""
        self._latest_voice_receipt: dict[str, object] = {}
        self._gmail_connected: bool | None = None
        self._gmail_account = ""
        self._gmail_connect_in_flight = False
        self._gmail_check_in_flight = False
        self._gmail_monitor_after_id = None
        self._calendar_after_id = None
        self._gmail_candidate_proposals: list[dict[str, object]] = []
        self.calendar_store = None
        self._avatar_after_id = None
        self._portrait_power_saving = False
        self._device_scan_in_flight = False
        self._known_device_instances: set[str] = set()
        self._discovered_devices: list[object] = []
        self._owner_portrait_renderer: PortraitFrameRenderer | None = None
        self._owner_portrait_photo = None
        self._corner_portrait_photo = None
        self._discovery_responder = None
        self._pairing_responder = None
        self._pairing_credential_vault = None
        self._secure_sync_service = None
        self._local_discovery = None
        self.wallet = None
        if SarahLocalDiscovery is not None:
            try:
                self._local_discovery = SarahLocalDiscovery(
                    device_name=f"Sarah on {socket.gethostname() or 'Windows'}",
                    device_type="windows",
                    pairing_port=0,
                )
            except Exception:
                self._local_discovery = None
        super().__init__()
        if SarahCalendarStore is not None:
            try:
                self.calendar_store = SarahCalendarStore(self.db)
            except Exception:
                self.calendar_store = None
        self.refresh_calendar()
        self._schedule_calendar_tick(delay_ms=2_000)
        if SarahWallet is not None:
            try:
                self.wallet = SarahWallet(self.db)
            except Exception as error:
                self.wallet = None
                self._wallet_startup_error = safe_text(error)
        self._refresh_wallet_summary()
        if PairingCredentialVault is not None and SarahSecureSyncService is not None:
            try:
                self._pairing_credential_vault = PairingCredentialVault(self.db.root)
                self._secure_sync_service = SarahSecureSyncService(
                    self.db, self._pairing_credential_vault
                )
            except Exception:
                self._pairing_credential_vault = None
                self._secure_sync_service = None
        self.root.title("Sarah Morgan • Windows 2.5")
        if event_gmail_available():
            self._schedule_gmail_monitor_tick()
        if self._local_discovery is not None and SarahPairingResponderServer is not None:
            try:
                self._pairing_responder = SarahPairingResponderServer(
                    self._local_discovery,
                    on_pending=lambda pending: self.tasks.put(("pairing_pending", pending)),
                    on_complete=lambda credential: self.tasks.put(("pairing_complete", credential)),
                    on_secure_sync=self._secure_sync_service,
                    on_error=lambda error: self.tasks.put(("pairing_failed", safe_text(error))),
                )
                self._pairing_responder.start()
            except Exception:
                self._pairing_responder = None
        if (self._local_discovery is not None
                and self._pairing_responder is not None
                and self._pairing_responder.running
                and SarahDiscoveryResponder is not None):
            try:
                self._discovery_responder = SarahDiscoveryResponder(self._local_discovery)
                self._discovery_responder.start()
            except Exception:
                self._discovery_responder = None
        self.root.after(1200, self._auto_device_scan)

    def _maybe_onboard(self):
        """Use one dark in-shell profile card instead of Tk's dialog sequence."""

        profile = self.db.active_profile()
        if not needs_owner_identity_confirmation(profile):
            return
        existing = getattr(self, "_onboarding_card", None)
        if existing is not None and existing.winfo_exists():
            return

        shade = tk.Frame(
            self.root,
            bg=PALETTE["window"],
            highlightbackground=PALETTE["cyan"],
            highlightthickness=2,
            padx=28,
            pady=24,
        )
        shade.place(relx=0.5, rely=0.5, anchor="center", width=720, height=525)
        shade.lift()
        self._onboarding_card = shade
        try:
            shade.grab_set()
        except tk.TclError:
            pass

        tk.Label(
            shade,
            text="Meet Sarah",
            bg=PALETTE["window"],
            fg=PALETTE["text"],
            font=("Segoe UI", 24, "bold"),
        ).pack(anchor="w")
        tk.Label(
            shade,
            text="If you already use Sarah, connect that device first so Windows can offer your continuity before asking you to create a new local profile. Choose fresh setup only when this is your first Sarah device. Only your name is required in fresh setup; everything else may remain unknown.",
            bg=PALETTE["window"],
            fg=PALETTE["muted"],
            font=("Segoe UI", 10),
            wraplength=650,
            justify="left",
        ).pack(anchor="w", pady=(4, 16))
        tk.Label(
            shade,
            text="Already use Sarah on another device? Windows is available for secure discovery now, before you enter a name. Nothing is copied unless you approve the same code on both devices.",
            bg=PALETTE["window"],
            fg=PALETTE["cyan"],
            font=("Segoe UI", 9, "bold"),
            wraplength=650,
            justify="left",
        ).pack(anchor="w", pady=(0, 6))

        form = tk.Frame(shade, bg=PALETTE["window"])
        fields: dict[str, ttk.Entry] = {}
        specs = (
            ("name", "What should Sarah call you?", "Required"),
            ("age", "Age or birth year", "Optional — unknown stays unknown"),
            ("home", "Home or approximate area", "Optional"),
            ("interests", "Things you enjoy", "Optional"),
        )
        for row, (key, label, hint) in enumerate(specs):
            tk.Label(
                form,
                text=label,
                bg=PALETTE["window"],
                fg=PALETTE["soft"],
                font=("Segoe UI", 10, "bold"),
            ).grid(row=row * 2, column=0, sticky="w", pady=(6, 0))
            tk.Label(
                form,
                text=hint,
                bg=PALETTE["window"],
                fg=PALETTE["muted"],
                font=("Segoe UI", 8),
            ).grid(row=row * 2, column=1, sticky="e", pady=(6, 0))
            field = self._owner_entry(form)
            field.grid(row=row * 2 + 1, column=0, columnspan=2, sticky="ew", pady=(3, 2))
            fields[key] = field
        form.grid_columnconfigure(0, weight=1)
        form.grid_columnconfigure(1, weight=1)
        self._onboarding_fields = fields
        self._onboarding_error = tk.StringVar(value="")
        error_label = tk.Label(
            shade,
            textvariable=self._onboarding_error,
            bg=PALETTE["window"],
            fg=PALETTE["orange"],
            font=("Segoe UI", 9, "bold"),
        )
        error_label.pack(anchor="w", pady=(8, 0))
        buttons = tk.Frame(shade, bg=PALETTE["window"])
        buttons.pack(side="bottom", fill="x")
        start_button = self._owner_button(buttons, "Start with Sarah", self._finish_onboarding)
        self._owner_button(buttons, "Import encrypted backup", self._onboarding_restore).pack(side="right", padx=8)
        self._owner_button(buttons, "Set up later", self._dismiss_onboarding).pack(side="left")
        self._owner_button(
            buttons,
            "Find my other Sarah device",
            lambda: self._begin_device_scan(automatic=False),
        ).pack(side="left", padx=8)
        self._owner_button(
            buttons,
            "This is my first Sarah device",
            self._show_fresh_profile_form,
        ).pack(side="left", padx=8)
        self._onboarding_profile_form = form
        self._onboarding_error_label = error_label
        self._onboarding_start_button = start_button

    def _show_fresh_profile_form(self) -> None:
        form = getattr(self, "_onboarding_profile_form", None)
        button = getattr(self, "_onboarding_start_button", None)
        error_label = getattr(self, "_onboarding_error_label", None)
        if form is None or button is None:
            return
        if not form.winfo_manager():
            options = {"fill": "x"}
            if error_label is not None:
                options["before"] = error_label
            form.pack(**options)
        if not button.winfo_manager():
            button.pack(side="right")
        self._onboarding_error.set(
            "Fresh local setup selected. Sarah will ask for a name now; you can still return to device import."
        )
        fields = getattr(self, "_onboarding_fields", {})
        if fields.get("name") is not None:
            fields["name"].focus_set()

    def _finish_onboarding(self) -> None:
        fields = getattr(self, "_onboarding_fields", {})
        name = safe_text(fields.get("name").get() if fields.get("name") else "")
        if not name:
            self._onboarding_error.set("Enter the name Sarah should use.")
            return
        age_text = safe_text(fields.get("age").get() if fields.get("age") else "")
        age = None
        if age_text:
            try:
                number = int(age_text)
                age = time.localtime().tm_year - number if number >= 1900 else number
                if age < 1 or age > 120:
                    raise ValueError
            except (TypeError, ValueError):
                self._onboarding_error.set(
                    "Use a valid age or four-digit birth year, or leave it blank so it remains unknown."
                )
                return
        home = safe_text(fields.get("home").get() if fields.get("home") else "")
        interests = safe_text(
            fields.get("interests").get() if fields.get("interests") else ""
        )
        try:
            self.db.rename_active_profile(name)
            self.db.ensure_profile(
                name,
                age,
                home,
                interests,
                True,
                age_known=age is not None,
            )
        except ValueError as error:
            self._onboarding_error.set(safe_text(error) or "Enter a valid name.")
            return
        self.owner_notice.set(f"Sarah is ready to talk with {name}.")
        self._dismiss_onboarding()
        self.root.after(0, self._offer_gmail_after_profile)

    def _dismiss_onboarding(self) -> None:
        card = getattr(self, "_onboarding_card", None)
        self._onboarding_card = None
        if card is not None:
            try:
                card.grab_release()
            except tk.TclError:
                pass
            card.destroy()

    def _onboarding_restore(self) -> None:
        self._dismiss_onboarding()
        self.restore()

    # ------------------------------------------------------------------
    # Owner shell
    # ------------------------------------------------------------------
    def _build_ui(self):
        self._portrait_power_saving = self.db.get_setting(
            "owner_portrait_power_saving", "0"
        ) == "1"
        self.root.configure(bg=PALETTE["window"])
        self.root.geometry("1240x810")
        self.root.minsize(960, 640)

        style = ttk.Style(self.root)
        try:
            style.theme_use("clam")
        except tk.TclError:
            pass
        style.configure(
            "Owner.TNotebook",
            background=PALETTE["window"],
            borderwidth=0,
            tabmargins=0,
        )
        style.layout("Owner.TNotebook.Tab", [])
        style.configure(
            "Owner.TButton",
            background=PALETTE["teal_dark"],
            foreground=PALETTE["text"],
            borderwidth=0,
            focusthickness=1,
            focuscolor=PALETTE["cyan"],
            font=("Segoe UI", 10, "bold"),
            padding=(13, 9),
        )
        style.map(
            "Owner.TButton",
            background=[("active", PALETTE["teal"]), ("disabled", "#24404d")],
            foreground=[("disabled", "#77919b")],
        )
        style.configure(
            "Owner.TEntry",
            fieldbackground=PALETTE["field"],
            foreground=PALETTE["text"],
            insertcolor=PALETTE["cyan"],
            bordercolor=PALETTE["line"],
            lightcolor=PALETTE["line"],
            darkcolor=PALETTE["line"],
            padding=9,
        )

        header = tk.Frame(self.root, bg=PALETTE["header"], height=82)
        header.pack(fill="x")
        header.pack_propagate(False)
        title_box = tk.Frame(header, bg=PALETTE["header"])
        title_box.pack(side="left", padx=(20, 12), pady=11)
        tk.Label(
            title_box,
            text="SARAH",
            bg=PALETTE["header"],
            fg=PALETTE["text"],
            font=("Segoe UI", 23, "bold"),
        ).pack(anchor="w")
        tk.Label(
            title_box,
            text="TRAVEL COMPANION  •  WINDOWS 2.5",
            bg=PALETTE["header"],
            fg=PALETTE["cyan"],
            font=("Segoe UI", 8, "bold"),
        ).pack(anchor="w")

        chip_row = tk.Frame(header, bg=PALETTE["header"])
        chip_row.pack(side="left", fill="x", expand=True, padx=12, pady=20)
        self._status_chip_vars: dict[str, tk.StringVar] = {}
        for key in ("mind", "voice", "gmail", "devices"):
            value = tk.StringVar(value=f"{key.title()}: checking")
            self._status_chip_vars[key] = value
            tk.Label(
                chip_row,
                textvariable=value,
                bg=PALETTE["panel_alt"],
                fg=PALETTE["soft"],
                font=("Segoe UI", 9, "bold"),
                padx=10,
                pady=7,
            ).pack(side="left", padx=4)

        ttk.Button(
            header,
            text="Connect Sarah",
            command=self.connect_private_access,
            style="Owner.TButton",
        ).pack(side="right", padx=(6, 18), pady=18)
        ttk.Button(
            header,
            text="Hide",
            command=self.hide_to_tray,
            style="Owner.TButton",
        ).pack(side="right", padx=2, pady=18)
        self._power_saving_text = tk.StringVar()
        self._update_power_saving_label()
        ttk.Button(
            header,
            textvariable=self._power_saving_text,
            command=self.toggle_portrait_power_saving,
            style="Owner.TButton",
        ).pack(side="right", padx=2, pady=18)

        shell = tk.Frame(self.root, bg=PALETTE["window"])
        shell.pack(fill="both", expand=True)
        rail = tk.Frame(shell, bg=PALETTE["rail"], width=314)
        rail.pack(side="left", fill="y")
        rail.pack_propagate(False)

        portrait_frame = tk.Frame(
            rail,
            bg=PALETTE["panel_alt"],
            highlightbackground=PALETTE["cyan"],
            highlightthickness=1,
        )
        portrait_frame.pack(padx=13, pady=(15, 8))
        self.portrait_canvas = tk.Canvas(
            portrait_frame,
            width=OWNER_PORTRAIT_SIZE[0],
            height=OWNER_PORTRAIT_SIZE[1],
            bg=PALETTE["panel"],
            highlightthickness=0,
        )
        self.portrait_canvas.pack(padx=2, pady=2)
        self._load_required_portrait()

        tk.Label(
            rail,
            text="Sarah Morgan",
            bg=PALETTE["rail"],
            fg=PALETTE["text"],
            font=("Segoe UI", 17, "bold"),
        ).pack(anchor="w", padx=18)
        self._portrait_mode_label = tk.Label(
            rail,
            text=(
                "Power saving • static portrait • text + voice"
                if self._portrait_power_saving
                else "Live portrait • lightweight CPU animation"
            ),
            bg=PALETTE["rail"],
            fg=PALETTE["muted"],
            font=("Segoe UI", 9),
        )
        self._portrait_mode_label.pack(anchor="w", padx=18, pady=(1, 12))

        self._nav_buttons: dict[str, tk.Button] = {}
        nav_specs = (
            ("talk", "Talk with Sarah"),
            ("workbench", "Travel Workbench"),
            ("discover", "Map & Discover"),
            ("trips", "Trips"),
            ("calendar", "Calendar"),
            ("photos", "Photos"),
            ("connections", "Connections"),
            ("activity", "Activity"),
        )
        for key, label in nav_specs:
            button = tk.Button(
                rail,
                text=label,
                command=lambda selected=key: self._show_page(selected),
                anchor="w",
                bg=PALETTE["rail"],
                fg=PALETTE["soft"],
                activebackground=PALETTE["teal_dark"],
                activeforeground=PALETTE["text"],
                relief="flat",
                bd=0,
                padx=20,
                pady=9,
                font=("Segoe UI", 10, "bold"),
                cursor="hand2",
            )
            button.pack(fill="x", padx=9, pady=1)
            self._nav_buttons[key] = button

        tk.Label(
            rail,
            text=SARAH_WINDOWS_OWNER_VERSION,
            bg=PALETTE["rail"],
            fg=PALETTE["muted"],
            font=("Segoe UI", 8),
            wraplength=275,
            justify="left",
        ).pack(side="bottom", anchor="w", padx=18, pady=14)

        content = tk.Frame(shell, bg=PALETTE["window"])
        content.pack(side="left", fill="both", expand=True)
        self.tabs = ttk.Notebook(content, style="Owner.TNotebook")
        self.tabs.pack(fill="both", expand=True, padx=(10, 14), pady=(12, 4))

        self.chat_tab = tk.Frame(self.tabs, bg=PALETTE["window"])
        self.workbench_tab = tk.Frame(self.tabs, bg=PALETTE["window"])
        self.discovery_tab = tk.Frame(self.tabs, bg=PALETTE["window"])
        self.trip_tab = tk.Frame(self.tabs, bg=PALETTE["window"])
        self.calendar_tab = tk.Frame(self.tabs, bg=PALETTE["window"])
        self.photo_tab = tk.Frame(self.tabs, bg=PALETTE["window"])
        self.device_tab = tk.Frame(self.tabs, bg=PALETTE["window"])
        self.activity_tab = tk.Frame(self.tabs, bg=PALETTE["window"])
        self._page_frames = {
            "talk": self.chat_tab,
            "workbench": self.workbench_tab,
            "discover": self.discovery_tab,
            "trips": self.trip_tab,
            "calendar": self.calendar_tab,
            "photos": self.photo_tab,
            "connections": self.device_tab,
            "activity": self.activity_tab,
        }
        for key, frame in self._page_frames.items():
            self.tabs.add(frame, text=key)

        self.status = tk.StringVar(value="ready")
        self.owner_notice = tk.StringVar(value="Ready when you are.")
        self._build_chat()
        self._build_workbench()
        self._build_discoveries()
        self._build_trips()
        self._build_calendar()
        self._build_photos()
        self._build_devices()
        self._build_activity()

        footer = tk.Frame(content, bg=PALETTE["header"], height=34)
        footer.pack(fill="x", padx=(10, 14), pady=(0, 9))
        footer.pack_propagate(False)
        tk.Label(
            footer,
            textvariable=self.owner_notice,
            bg=PALETTE["header"],
            fg=PALETTE["soft"],
            anchor="w",
            padx=12,
            font=("Segoe UI", 9),
        ).pack(fill="both", expand=True)

        self._show_page("talk")
        self._refresh_gmail_connection_state()
        self._refresh_status_chips()

    def _show_page(self, name: str) -> None:
        frame = self._page_frames.get(name)
        if frame is None:
            return
        self.tabs.select(frame)
        for key, button in self._nav_buttons.items():
            selected = key == name
            button.configure(
                bg=PALETTE["teal_dark"] if selected else PALETTE["rail"],
                fg=PALETTE["text"] if selected else PALETTE["soft"],
            )
        if name == "talk" and hasattr(self, "entry"):
            self.entry.focus_set()

    def _page_heading(self, parent: tk.Widget, title: str, description: str) -> None:
        heading = tk.Frame(parent, bg=PALETTE["window"])
        heading.pack(fill="x", pady=(2, 12))
        tk.Label(
            heading,
            text=title,
            bg=PALETTE["window"],
            fg=PALETTE["text"],
            font=("Segoe UI", 21, "bold"),
        ).pack(anchor="w")
        tk.Label(
            heading,
            text=description,
            bg=PALETTE["window"],
            fg=PALETTE["muted"],
            font=("Segoe UI", 10),
            wraplength=800,
            justify="left",
        ).pack(anchor="w", pady=(3, 0))

    def _owner_button(self, parent: tk.Widget, text: str, command) -> ttk.Button:
        button = ttk.Button(parent, text=text, command=command, style="Owner.TButton")
        return button

    def _owner_entry(self, parent: tk.Widget, *, width: int | None = None) -> ttk.Entry:
        options = {"style": "Owner.TEntry", "font": ("Segoe UI", 11)}
        if width is not None:
            options["width"] = width
        return ttk.Entry(parent, **options)

    def _dark_listbox(self, parent: tk.Widget, **options) -> tk.Listbox:
        return tk.Listbox(
            parent,
            bg=PALETTE["field"],
            fg=PALETTE["soft"],
            selectbackground=PALETTE["teal_dark"],
            selectforeground=PALETTE["text"],
            highlightbackground=PALETTE["line"],
            highlightcolor=PALETTE["cyan"],
            relief="flat",
            bd=0,
            font=("Segoe UI", 10),
            activestyle="none",
            **options,
        )

    # ------------------------------------------------------------------
    # Conversation: primary owner experience, no backend jargon
    # ------------------------------------------------------------------
    def _build_chat(self):
        body = tk.Frame(self.chat_tab, bg=PALETTE["window"])
        body.pack(fill="both", expand=True, padx=4, pady=2)
        self._page_heading(
            body,
            "Talk with Sarah",
            "Talk naturally about a trip, something nearby, your plans, or anything else.",
        )
        quick = tk.Frame(body, bg=PALETTE["window"])
        quick.pack(fill="x", pady=(0, 9))
        self._owner_button(
            quick,
            "Explore a place",
            lambda: self._show_page("workbench"),
        ).pack(side="left")
        self._owner_button(
            quick,
            "Open a map",
            lambda: self._show_page("discover"),
        ).pack(side="left", padx=8)
        self.chat = tk.Text(
            body,
            wrap="word",
            font=("Segoe UI", 12),
            bg=PALETTE["field"],
            fg=PALETTE["text"],
            insertbackground=PALETTE["cyan"],
            padx=18,
            pady=16,
            state="disabled",
            relief="flat",
            bd=0,
            spacing1=3,
            spacing3=9,
        )
        self.chat.tag_configure(
            "sarah_name", foreground=PALETTE["cyan"], font=("Segoe UI", 10, "bold")
        )
        self.chat.tag_configure(
            "owner_name", foreground=PALETTE["orange"], font=("Segoe UI", 10, "bold")
        )
        self.chat.tag_configure("message", foreground=PALETTE["text"], lmargin2=4)
        self.chat.pack(fill="both", expand=True)

        composer = tk.Frame(body, bg=PALETTE["panel"], pady=10, padx=10)
        composer.pack(fill="x", pady=(10, 0))
        self.entry = self._owner_entry(composer)
        self.entry.pack(side="left", fill="x", expand=True)
        self.entry.bind("<Return>", lambda _event: self.send())
        self.send_button = self._owner_button(composer, "Send", self.send)
        self.send_button.pack(side="left", padx=(8, 0))
        self.calm_button = self._owner_button(
            composer,
            "Calm with Sarah",
            lambda: self._submit_text("I feel nervous and I want you to stay with me and help me feel calmer."),
        )
        self.calm_button.pack(side="left", padx=(8, 0))
        self.stop_voice_button = self._owner_button(composer, "Stop voice", self.stop_voice)
        self.stop_voice_button.pack(side="left", padx=(8, 0))
        self._append(
            "Sarah",
            "I’m here. Tell me what you’re thinking about, and we can take it from there.",
        )

    def _append(self, who: str, text: str, route: str = ""):
        del route  # Route truth remains in Sarah's database; it is not chat clutter.
        if not hasattr(self, "chat"):
            return
        label = safe_text(who) or "Sarah"
        tag = "sarah_name" if label.lower() == "sarah" else "owner_name"
        self.chat.configure(state="normal")
        self.chat.insert("end", label + "\n", tag)
        self.chat.insert("end", safe_text(text) + "\n\n", "message")
        self.chat.see("end")
        self.chat.configure(state="disabled")

    def _submit_text(self, text: str):
        """Keep SarahApp's turn path and surface any durable trip destination."""

        super()._submit_text(text)
        self.root.after(0, self._surface_latest_trip_in_workbench)

    def _surface_latest_trip_in_workbench(self) -> None:
        if not hasattr(self, "workbench_place"):
            return
        trips = self.db.list_rows("trips", limit=1)
        destination = _clean_place(trips[0].get("destination", "")) if trips else ""
        current = _clean_place(self.workbench_place.get())
        previous = _clean_place(getattr(self, "_workbench_auto_value", ""))
        if destination and (not current or current == previous):
            self.workbench_place.delete(0, "end")
            self.workbench_place.insert(0, destination)
            self._workbench_auto_value = destination

    # ------------------------------------------------------------------
    # Travel workbench: clear entry to sponsor and source-bound tools
    # ------------------------------------------------------------------
    def _build_workbench(self):
        body = tk.Frame(self.workbench_tab, bg=PALETTE["window"])
        body.pack(fill="both", expand=True, padx=4, pady=2)
        self._page_heading(
            body,
            "Travel Workbench",
            "Start with a place. Sarah can move it into conversation, a map, public media, trip planning, or connected travel services.",
        )
        place_card = tk.Frame(body, bg=PALETTE["panel"], padx=16, pady=15)
        place_card.pack(fill="x")
        tk.Label(
            place_card,
            text="Place, landmark, or event",
            bg=PALETTE["panel"],
            fg=PALETTE["muted"],
            font=("Segoe UI", 9, "bold"),
        ).pack(anchor="w")
        row = tk.Frame(place_card, bg=PALETTE["panel"])
        row.pack(fill="x", pady=(5, 0))
        self.workbench_place = self._owner_entry(row)
        self.workbench_place.pack(side="left", fill="x", expand=True)
        self._workbench_auto_value = ""
        self._owner_button(row, "Talk with Sarah", self._talk_about_workbench_place).pack(side="left", padx=(8, 0))

        actions = tk.Frame(body, bg=PALETTE["window"])
        actions.pack(fill="x", pady=12)
        self._owner_button(actions, "Open map", self._open_workbench_map).pack(side="left")
        self._owner_button(actions, "Public photos & media", self._open_public_media).pack(side="left", padx=8)
        self._owner_button(actions, "Plan a trip", self._plan_workbench_trip).pack(side="left")
        self._owner_button(actions, "Places to stay", self._find_places_to_stay).pack(side="left", padx=8)

        cards = tk.Frame(body, bg=PALETTE["window"])
        cards.pack(fill="both", expand=True)
        cards.grid_columnconfigure(0, weight=1, uniform="workbench")
        cards.grid_columnconfigure(1, weight=1, uniform="workbench")
        source_card = self._connection_card(
            cards,
            "Maps and public media",
            "Map views open in OpenStreetMap. Public image and video discovery opens in Wikimedia Commons, where every result retains its source and license page.",
            0,
            0,
        )
        tk.Label(
            source_card,
            text="Sarah does not call a browser search a viewed or saved photo.",
            bg=PALETTE["panel"],
            fg=PALETTE["cyan"],
            wraplength=370,
            justify="left",
        ).pack(anchor="w")
        sponsor_card = self._connection_card(
            cards,
            "Travel services & sponsors",
            "Hotel and partner services remain optional. Sarah keeps live availability, prices, and bookings unclaimed until a connected service returns a receipt.",
            0,
            1,
        )
        self._owner_button(sponsor_card, "Service status", self.show_sponsors).pack(anchor="w")
        if event_gmail_available():
            self._owner_button(sponsor_card, "Gmail travel offers", lambda: self._show_page("connections")).pack(anchor="w", pady=(8, 0))

        wallet_card = self._connection_card(
            cards,
            "Loyalty cards, tickets & passes",
            "Keep loyalty details and owner-selected ticket or QR images in an encrypted, active-profile wallet. Sarah never treats a saved image as proof of purchase or admission.",
            1,
            0,
        )
        wallet_card.grid(columnspan=2)
        self._wallet_summary = tk.StringVar(value="Wallet is starting.")
        tk.Label(
            wallet_card,
            textvariable=self._wallet_summary,
            bg=PALETTE["panel"],
            fg=PALETTE["cyan"],
            wraplength=760,
            justify="left",
        ).pack(anchor="w", pady=(0, 8))
        wallet_actions = tk.Frame(wallet_card, bg=PALETTE["panel"])
        wallet_actions.pack(anchor="w")
        self._owner_button(wallet_actions, "Add loyalty card", self._add_loyalty_wallet_record).pack(side="left")
        self._owner_button(wallet_actions, "Add ticket / QR pass", self._add_ticket_wallet_record).pack(side="left", padx=8)
        self._owner_button(wallet_actions, "Review wallet", self._review_owner_wallet).pack(side="left")

    def _wallet_ready(self) -> bool:
        if self.wallet is not None:
            return True
        message = safe_text(getattr(self, "_wallet_startup_error", ""))
        messagebox.showerror(
            "Wallet unavailable",
            "Sarah could not open this Windows user's encrypted wallet. Conversation and travel tools remain available."
            + (f"\n\nDetails: {message}" if message else ""),
            parent=self.root,
        )
        return False

    def _refresh_wallet_summary(self) -> None:
        summary = getattr(self, "_wallet_summary", None)
        if summary is None:
            return
        if self.wallet is None:
            summary.set("Encrypted wallet unavailable on this installation.")
            return
        try:
            rows = self.wallet.list_records()
            loyalty = sum(row.get("record_type") == "loyalty" for row in rows)
            passes = sum(row.get("record_type") == "ticket_pass" for row in rows)
            summary.set(f"Active profile: {loyalty} loyalty card(s) and {passes} ticket/pass record(s).")
        except Exception:
            summary.set("Encrypted wallet could not be read; no record was changed.")

    def _add_loyalty_wallet_record(self) -> None:
        if not self._wallet_ready():
            return
        program = simpledialog.askstring(
            "Add loyalty card", "Program name:", parent=self.root
        )
        if not safe_text(program):
            return
        member_id = simpledialog.askstring(
            "Add loyalty card",
            "Member number or identifier (never enter a password, PIN, CVV, or payment-card number):",
            parent=self.root,
        )
        if not safe_text(member_id):
            return
        tier = simpledialog.askstring(
            "Add loyalty card", "Tier or status (optional):", parent=self.root
        ) or ""
        official_url = simpledialog.askstring(
            "Add loyalty card", "Exact official HTTPS website (optional):", parent=self.root
        ) or ""
        image_path = None
        if messagebox.askyesno(
            "Loyalty QR or barcode",
            "Add an owner-selected image of the loyalty QR/barcode? Sarah will sanitize it and remove metadata.",
            parent=self.root,
        ):
            selected = filedialog.askopenfilename(
                title="Choose loyalty QR or barcode image",
                filetypes=(("Image files", "*.png;*.jpg;*.jpeg;*.webp;*.bmp"), ("All files", "*.*")),
                parent=self.root,
            )
            image_path = Path(selected) if selected else None
        try:
            self.wallet.add_loyalty(
                program_name=program,
                member_identifier=member_id,
                tier=tier,
                official_url=official_url,
                code_image_path=image_path,
            )
        except (WalletValidationError, WalletError, OSError) as error:
            messagebox.showerror("Loyalty card not saved", safe_text(error), parent=self.root)
            return
        self._refresh_wallet_summary()
        self.owner_notice.set("Loyalty card saved in the active profile's encrypted wallet.")

    def _add_ticket_wallet_record(self) -> None:
        if not self._wallet_ready():
            return
        selected = filedialog.askopenfilename(
            title="Choose ticket, boarding pass, or QR image",
            filetypes=(("Image files", "*.png;*.jpg;*.jpeg;*.webp;*.bmp"), ("All files", "*.*")),
            parent=self.root,
        )
        if not selected:
            return
        title = simpledialog.askstring(
            "Add ticket or pass", "Title shown in Sarah's wallet:", parent=self.root
        )
        if not safe_text(title):
            return
        official_url = simpledialog.askstring(
            "Add ticket or pass",
            "Exact official HTTPS event, issuer, or ticket website:",
            parent=self.root,
        )
        if not safe_text(official_url):
            return
        event_name = simpledialog.askstring(
            "Add ticket or pass", "Event or trip name (optional):", parent=self.root
        ) or ""
        try:
            self.wallet.add_ticket_pass(
                title=title,
                official_url=official_url,
                image_path=Path(selected),
                metadata={"event_name": event_name},
            )
        except (WalletValidationError, WalletError, OSError) as error:
            messagebox.showerror("Ticket/pass not saved", safe_text(error), parent=self.root)
            return
        self._refresh_wallet_summary()
        self.owner_notice.set(
            "Ticket/pass reference saved. This record does not claim purchase, validity, or admission."
        )

    def _review_owner_wallet(self) -> None:
        if not self._wallet_ready():
            return
        try:
            records = self.wallet.list_records()
        except (WalletValidationError, WalletError, OSError) as error:
            messagebox.showerror("Wallet unavailable", safe_text(error), parent=self.root)
            return
        window = tk.Toplevel(self.root)
        window.title("Sarah's encrypted owner wallet")
        window.geometry("820x540")
        window.configure(bg=PALETTE["window"])
        tk.Label(
            window,
            text="Loyalty cards, tickets & passes",
            bg=PALETTE["window"],
            fg=PALETTE["text"],
            font=("Segoe UI", 19, "bold"),
        ).pack(anchor="w", padx=18, pady=(16, 2))
        tk.Label(
            window,
            text="Saved records are owner references, not proof of payment, validity, booking, or admission.",
            bg=PALETTE["window"],
            fg=PALETTE["muted"],
        ).pack(anchor="w", padx=18, pady=(0, 10))
        rows = tk.Frame(window, bg=PALETTE["window"])
        rows.pack(fill="both", expand=True, padx=18)
        listing = self._dark_listbox(rows)
        listing.pack(side="left", fill="both", expand=True)
        scrollbar = ttk.Scrollbar(rows, orient="vertical", command=listing.yview)
        scrollbar.pack(side="right", fill="y")
        listing.configure(yscrollcommand=scrollbar.set)
        for record in records:
            fields = record.get("fields") or {}
            title = fields.get("program_name") or fields.get("title") or "Untitled"
            listing.insert("end", f"{record.get('record_type', '').replace('_', ' ').title()}  •  {title}")

        def selected_record():
            selection = listing.curselection()
            if not selection:
                messagebox.showinfo("Choose a wallet item", "Select an item first.", parent=window)
                return None
            return records[int(selection[0])]

        def open_official():
            record = selected_record()
            if record is None:
                return
            url = safe_text(record.get("official_url"))
            if not url:
                messagebox.showinfo("No official website saved", "This item has no saved official website.", parent=window)
                return
            webbrowser.open(url, new=2)

        def show_image():
            record = selected_record()
            if record is None:
                return
            try:
                data, _mime = self.wallet.get_image_bytes(record["record_id"])
                with Image.open(io.BytesIO(data)) as opened:
                    image = opened.convert("RGBA")
                    image.thumbnail((620, 620), Image.Resampling.LANCZOS)
                    photo = ImageTk.PhotoImage(image)
            except (WalletValidationError, WalletError, OSError) as error:
                messagebox.showerror("Image unavailable", safe_text(error), parent=window)
                return
            preview = tk.Toplevel(window)
            preview.title("Owner-selected wallet image")
            preview.configure(bg=PALETTE["window"])
            label = tk.Label(preview, image=photo, bg=PALETTE["window"])
            label.image = photo
            label.pack(padx=16, pady=16)

        def remove_selected():
            record = selected_record()
            if record is None:
                return
            if not messagebox.askyesno(
                "Remove wallet item",
                "Remove this item from Sarah's active-profile wallet? Logical recovery is not supported; forensic storage recovery has not been assessed.",
                parent=window,
            ):
                return
            try:
                self.wallet.remove_record(record["record_id"])
            except (WalletValidationError, WalletError, OSError) as error:
                messagebox.showerror("Wallet item not removed", safe_text(error), parent=window)
                return
            index = int(listing.curselection()[0])
            listing.delete(index)
            records.pop(index)
            self._refresh_wallet_summary()

        buttons = tk.Frame(window, bg=PALETTE["window"])
        buttons.pack(fill="x", padx=18, pady=14)
        self._owner_button(buttons, "View QR / pass", show_image).pack(side="left")
        self._owner_button(buttons, "Open official website", open_official).pack(side="left", padx=8)
        self._owner_button(buttons, "Remove", remove_selected).pack(side="left")

    def _workbench_value(self) -> str:
        return _clean_place(self.workbench_place.get())

    def _require_workbench_place(self) -> str:
        place = self._workbench_value()
        if not place:
            messagebox.showinfo(
                "Choose a place",
                "Enter a place, landmark, or event first.",
                parent=self.root,
            )
        return place

    def _talk_about_workbench_place(self) -> None:
        place = self._require_workbench_place()
        if not place:
            return
        self._show_page("talk")
        self._submit_text(f"I want to talk about visiting {place}.")

    def _open_workbench_map(self) -> None:
        place = self._require_workbench_place()
        if not place:
            return
        webbrowser.open(openstreetmap_handoff_url("", place), new=2)
        self.owner_notice.set(f"{place} opened in OpenStreetMap.")

    def _open_public_media(self) -> None:
        place = self._require_workbench_place()
        if not place:
            return
        webbrowser.open(wikimedia_media_handoff_url(place), new=2)
        self.owner_notice.set(
            "Wikimedia Commons opened with source and license pages for each result."
        )

    def _plan_workbench_trip(self) -> None:
        place = self._require_workbench_place()
        if not place:
            return
        self.trip_dest.delete(0, "end")
        self.trip_dest.insert(0, place)
        self._show_page("trips")

    def _find_places_to_stay(self) -> None:
        place = self._require_workbench_place()
        if not place:
            return
        self._show_page("talk")
        self._submit_text(
            f"Help me compare places to stay in {place}. Do not claim live prices or availability unless a connected travel service verifies them."
        )

    def show_sponsors(self):
        stay_configured = bool(runtime_setting("SARAH_STAY22_BACKEND_URL", root=self.db.root))
        research_configured = bool(getattr(self.research, "configured", False))
        lines = [
            "Current-source search: " + ("set up" if research_configured else "not connected"),
            "Places-to-stay service: " + ("set up" if stay_configured else "not connected"),
            "ElevenLabs voice: " + ("set up" if self.voice.configured else "not connected"),
        ]
        if event_gmail_available():
            lines.append("Gmail travel updates: " + ("connected read-only" if self._gmail_connected else "not connected"))
        messagebox.showinfo(
            "Travel service status",
            "\n".join(lines)
            + "\n\nSarah will not claim a search, price, room, offer, reservation, or booking without a real result from that service.",
            parent=self.root,
        )

    # ------------------------------------------------------------------
    # Map, discoveries, trips, photos
    # ------------------------------------------------------------------
    def _build_discoveries(self):
        body = tk.Frame(self.discovery_tab, bg=PALETTE["window"])
        body.pack(fill="both", expand=True, padx=4, pady=2)
        self._page_heading(
            body,
            "Map & Discover",
            "Open a real OpenStreetMap view, then let Sarah keep source-backed ideas together.",
        )
        map_card = tk.Frame(body, bg=PALETTE["panel"], padx=14, pady=14)
        map_card.pack(fill="x")
        tk.Label(map_card, text="Starting place", bg=PALETTE["panel"], fg=PALETTE["muted"]).grid(row=0, column=0, sticky="w")
        tk.Label(map_card, text="Destination", bg=PALETTE["panel"], fg=PALETTE["muted"]).grid(row=0, column=1, sticky="w", padx=(10, 0))
        self.map_origin = self._owner_entry(map_card)
        self.map_origin.grid(row=1, column=0, sticky="ew", pady=(4, 0))
        self.map_destination = self._owner_entry(map_card)
        self.map_destination.grid(row=1, column=1, sticky="ew", padx=(10, 0), pady=(4, 0))
        self._owner_button(map_card, "Open map", self.open_map).grid(row=1, column=2, padx=(10, 0), pady=(4, 0))
        map_card.grid_columnconfigure(0, weight=1)
        map_card.grid_columnconfigure(1, weight=1)

        actions = tk.Frame(body, bg=PALETTE["window"])
        actions.pack(fill="x", pady=10)
        self._owner_button(actions, "Check current sources", self.research_now).pack(side="left")
        self._owner_button(actions, "Nearby settings", self.set_nearby_permission).pack(side="left", padx=8)
        self.background_research_button = self._owner_button(actions, "Background discoveries", self.toggle_background_research)
        self.background_research_button.pack(side="left")
        self.discovery_list = self._dark_listbox(body)
        self.discovery_list.pack(fill="both", expand=True)
        self.discovery_list.bind("<Double-1>", self.open_discovery)
        self.discovery_rows = []
        self.refresh_discoveries()
        self._refresh_background_research_label()

    def open_map(self) -> None:
        try:
            url = openstreetmap_handoff_url(
                self.map_origin.get(), self.map_destination.get()
            )
        except ValueError as error:
            messagebox.showinfo("Open a map", str(error), parent=self.root)
            return
        opened = bool(webbrowser.open(url, new=2))
        self.owner_notice.set(
            "OpenStreetMap opened in your browser."
            if opened else
            "Windows could not open the map browser. Nothing else was changed."
        )

    def _build_trips(self):
        body = tk.Frame(self.trip_tab, bg=PALETTE["window"])
        body.pack(fill="both", expand=True, padx=4, pady=2)
        self._page_heading(
            body,
            "Trips",
            "Save places you are considering so Sarah can keep the conversation and discoveries connected.",
        )
        form = tk.Frame(body, bg=PALETTE["panel"], padx=14, pady=14)
        form.pack(fill="x")
        self.trip_title = self._owner_entry(form)
        self.trip_title.insert(0, "My trip")
        self.trip_title.grid(row=0, column=0, sticky="ew")
        self.trip_dest = self._owner_entry(form)
        self.trip_dest.grid(row=0, column=1, sticky="ew", padx=8)
        self._owner_button(form, "Save trip", self.add_trip).grid(row=0, column=2)
        self._owner_button(form, "Open destination map", self._open_trip_map).grid(row=0, column=3, padx=(8, 0))
        form.grid_columnconfigure(0, weight=1)
        form.grid_columnconfigure(1, weight=1)
        self.trip_list = self._dark_listbox(body)
        self.trip_list.pack(fill="both", expand=True, pady=(10, 0))
        self.refresh_trips()

    def _open_trip_map(self) -> None:
        destination = self.trip_dest.get()
        if not _clean_place(destination):
            messagebox.showinfo("Destination map", "Enter a destination first.", parent=self.root)
            return
        webbrowser.open(openstreetmap_handoff_url("", destination), new=2)
        self.owner_notice.set("The destination opened in OpenStreetMap.")

    def _build_calendar(self) -> None:
        body = tk.Frame(self.calendar_tab, bg=PALETTE["window"])
        body.pack(fill="both", expand=True, padx=4, pady=2)
        self._page_heading(
            body,
            "Calendar",
            "Email suggestions stay pending until you choose. Saving an item or setting a reminder never claims that you attended or completed the trip.",
        )
        columns = tk.Frame(body, bg=PALETTE["window"])
        columns.pack(fill="both", expand=True)
        columns.grid_columnconfigure(0, weight=1, uniform="calendar")
        columns.grid_columnconfigure(1, weight=1, uniform="calendar")
        columns.grid_rowconfigure(0, weight=1)

        pending = tk.Frame(columns, bg=PALETTE["panel"], padx=14, pady=13)
        pending.grid(row=0, column=0, sticky="nsew", padx=(0, 5))
        tk.Label(
            pending, text="Waiting for your decision", bg=PALETTE["panel"],
            fg=PALETTE["text"], font=("Segoe UI", 13, "bold"),
        ).pack(anchor="w")
        tk.Label(
            pending,
            text="Sarah can suggest an exact email item, but only you can put it on the calendar.",
            bg=PALETTE["panel"], fg=PALETTE["muted"], wraplength=420,
            justify="left", font=("Segoe UI", 9),
        ).pack(anchor="w", pady=(2, 8))
        self.calendar_pending_list = self._dark_listbox(pending, height=10)
        self.calendar_pending_list.pack(fill="both", expand=True)
        pending_buttons = tk.Frame(pending, bg=PALETTE["panel"])
        pending_buttons.pack(fill="x", pady=(8, 0))
        self._owner_button(pending_buttons, "Remember selected", self.remember_selected_email_item).pack(side="left")
        self._owner_button(pending_buttons, "Do not remember", self.reject_selected_email_item).pack(side="left", padx=7)

        saved = tk.Frame(columns, bg=PALETTE["panel"], padx=14, pady=13)
        saved.grid(row=0, column=1, sticky="nsew", padx=(5, 0))
        tk.Label(
            saved, text="Your saved calendar", bg=PALETTE["panel"],
            fg=PALETTE["text"], font=("Segoe UI", 13, "bold"),
        ).pack(anchor="w")
        tk.Label(
            saved,
            text="Reminders are optional local notices while Sarah is running.",
            bg=PALETTE["panel"], fg=PALETTE["muted"], wraplength=420,
            justify="left", font=("Segoe UI", 9),
        ).pack(anchor="w", pady=(2, 8))
        self.calendar_event_list = self._dark_listbox(saved, height=10)
        self.calendar_event_list.pack(fill="both", expand=True)
        saved_buttons = tk.Frame(saved, bg=PALETTE["panel"])
        saved_buttons.pack(fill="x", pady=(8, 0))
        self._owner_button(saved_buttons, "Add reminder", self.add_selected_calendar_reminder).pack(side="left")
        self._owner_button(saved_buttons, "Refresh", self.refresh_calendar).pack(side="left", padx=7)
        self._calendar_pending_rows: list[dict[str, object]] = []
        self._calendar_event_rows: list[dict[str, object]] = []

    def refresh_calendar(self) -> None:
        if not hasattr(self, "calendar_pending_list"):
            return
        self.calendar_pending_list.delete(0, "end")
        self.calendar_event_list.delete(0, "end")
        self._calendar_pending_rows = []
        self._calendar_event_rows = []
        if self.calendar_store is None:
            self.calendar_pending_list.insert("end", "Calendar module unavailable in this build")
            return
        try:
            self._calendar_pending_rows = self.calendar_store.proposals(
                status="pending_owner_decision"
            )
            self._calendar_event_rows = self.calendar_store.events()
        except Exception:
            self.calendar_pending_list.insert("end", "Calendar state could not be verified")
            return
        for proposal in self._calendar_pending_rows:
            kind = safe_text(proposal.get("kind")) or "item"
            title = safe_text(proposal.get("title")) or "Email suggestion"
            self.calendar_pending_list.insert("end", f"{kind.title()} â€¢ {title}")
        for event in self._calendar_event_rows:
            start = safe_text(event.get("start_local"))
            end = safe_text(event.get("end_local"))
            if end:
                start = f"{start} to {end}"
            title = safe_text(event.get("title")) or "Calendar item"
            self.calendar_event_list.insert("end", f"{start} â€¢ {title}")

    def _selected_pending_email_item(self) -> dict[str, object] | None:
        selection = self.calendar_pending_list.curselection()
        if not selection:
            messagebox.showinfo(
                "Choose an email suggestion",
                "Select one exact suggestion first.",
                parent=self.root,
            )
            return None
        index = int(selection[0])
        return self._calendar_pending_rows[index] if index < len(self._calendar_pending_rows) else None

    def _remember_email_proposal(self, proposal: dict[str, object]) -> None:
        if self.calendar_store is None:
            return
        title = simpledialog.askstring(
            "Calendar title",
            "What should Sarah call this calendar item?",
            initialvalue=safe_text(proposal.get("title")) or "Travel or event item",
            parent=self.root,
        )
        if title is None:
            return
        start = simpledialog.askstring(
            "Date and time",
            "When does it start? Use YYYY-MM-DD HH:MM.",
            initialvalue=safe_text(proposal.get("suggested_start_local")),
            parent=self.root,
        )
        if start is None:
            return
        end = ""
        if safe_text(proposal.get("kind")) in {"flight", "train", "bus"}:
            end = simpledialog.askstring(
                "Arrival time (optional)",
                "When does it arrive? Use YYYY-MM-DD HH:MM, or leave this blank if it is not established.",
                initialvalue=safe_text(proposal.get("suggested_end_local")),
                parent=self.root,
            )
            if end is None:
                return
        location = simpledialog.askstring(
            "Place (optional)",
            "Where is it? You may leave this blank.",
            initialvalue="",
            parent=self.root,
        )
        if location is None:
            return
        try:
            event = self.calendar_store.decide(
                safe_text(proposal.get("proposal_id")),
                remember=True,
                owner_action="Owner chose Remember selected in Sarah Calendar",
                title=title,
                start_local=start,
                end_local=end,
                location=location,
                kind=safe_text(proposal.get("kind")),
            )
        except Exception as error:
            messagebox.showerror("Calendar item not saved", safe_text(error), parent=self.root)
            return
        self.refresh_calendar()
        self.owner_notice.set("The exact email suggestion was added to your calendar after your confirmation.")
        if event and messagebox.askyesno(
            "Add a reminder?",
            "Would you like a local reminder before this item? Sarah will notify you only while she is running.",
            parent=self.root,
        ):
            minutes = simpledialog.askinteger(
                "Reminder",
                "How many minutes before?",
                initialvalue=60,
                minvalue=0,
                maxvalue=525600,
                parent=self.root,
            )
            if minutes is not None:
                try:
                    self.calendar_store.add_reminder(
                        safe_text(event.get("event_id")),
                        lead_minutes=minutes,
                        owner_action=f"Owner requested a local reminder {minutes} minutes before",
                    )
                    self.owner_notice.set("Your opt-in local reminder was saved.")
                except Exception as error:
                    messagebox.showerror("Reminder not saved", safe_text(error), parent=self.root)

    def remember_selected_email_item(self) -> None:
        proposal = self._selected_pending_email_item()
        if proposal is not None:
            self._remember_email_proposal(proposal)

    def reject_selected_email_item(self) -> None:
        proposal = self._selected_pending_email_item()
        if proposal is None or self.calendar_store is None:
            return
        try:
            self.calendar_store.decide(
                safe_text(proposal.get("proposal_id")),
                remember=False,
                owner_action="Owner chose Do not remember for this exact email suggestion",
            )
        except Exception as error:
            messagebox.showerror("Decision not saved", safe_text(error), parent=self.root)
            return
        self.refresh_calendar()
        self.owner_notice.set("That exact suggestion was not added. Its decision history was preserved.")

    def add_selected_calendar_reminder(self) -> None:
        if self.calendar_store is None:
            return
        selection = self.calendar_event_list.curselection()
        if not selection:
            messagebox.showinfo("Choose a calendar item", "Select one saved item first.", parent=self.root)
            return
        event = self._calendar_event_rows[int(selection[0])]
        minutes = simpledialog.askinteger(
            "Reminder",
            "How many minutes before?",
            initialvalue=60,
            minvalue=0,
            maxvalue=525600,
            parent=self.root,
        )
        if minutes is None:
            return
        try:
            self.calendar_store.add_reminder(
                safe_text(event.get("event_id")),
                lead_minutes=minutes,
                owner_action=f"Owner requested a local reminder {minutes} minutes before",
            )
        except Exception as error:
            messagebox.showerror("Reminder not saved", safe_text(error), parent=self.root)
            return
        self.owner_notice.set("Your opt-in local reminder was saved.")

    def _offer_new_email_proposal(self, proposal: dict[str, object]) -> None:
        title = safe_text(proposal.get("title")) or "an event or travel item"
        answer = messagebox.askyesnocancel(
            "Sarah found something in your email",
            f'I saw "{title}" in your read-only Gmail results. Do you want me to remember it on your calendar?\n\nYes = review and save\nNo = do not remember this exact item\nCancel = leave it waiting',
            parent=self.root,
        )
        if answer is True:
            self._show_page("calendar")
            self._remember_email_proposal(proposal)
        elif answer is False and self.calendar_store is not None:
            try:
                self.calendar_store.decide(
                    safe_text(proposal.get("proposal_id")),
                    remember=False,
                    owner_action="Owner answered No to Sarah's exact email-calendar suggestion",
                )
            except Exception:
                return
            self.refresh_calendar()

    def _schedule_calendar_tick(self, *, delay_ms: int = 60_000) -> None:
        if self._calendar_after_id is not None:
            try:
                self.root.after_cancel(self._calendar_after_id)
            except (AttributeError, tk.TclError):
                pass
        self._calendar_after_id = self.root.after(delay_ms, self._calendar_tick)

    def _calendar_tick(self) -> None:
        self._calendar_after_id = None
        if self.calendar_store is not None:
            try:
                for reminder in self.calendar_store.claim_due_reminders():
                    text = f"{safe_text(reminder.get('title'))} starts at {safe_text(reminder.get('start_local'))}."
                    delivered = False
                    tray = getattr(self, "tray", None)
                    if tray is not None and hasattr(tray, "notify"):
                        try:
                            tray.notify(text, "Sarah reminder")
                            delivered = True
                        except Exception:
                            delivered = False
                    if not delivered:
                        messagebox.showinfo("Sarah reminder", text, parent=self.root)
                        delivered = True
                    if delivered:
                        self.calendar_store.mark_reminder_delivered(
                            safe_text(reminder.get("reminder_id")),
                            delivery_token=safe_text(reminder.get("delivery_token")),
                        )
                    else:
                        self.calendar_store.release_reminder_claim(
                            safe_text(reminder.get("reminder_id")),
                            delivery_token=safe_text(reminder.get("delivery_token")),
                        )
            except Exception:
                self.owner_notice.set("Sarah could not verify the calendar reminder state.")
        self._schedule_calendar_tick()

    def _build_photos(self):
        body = tk.Frame(self.photo_tab, bg=PALETTE["window"])
        body.pack(fill="both", expand=True, padx=4, pady=2)
        self._page_heading(
            body,
            "Photos",
            "Bring selected trip photos into Sarah's private, duplicate-checked photo library.",
        )
        actions = tk.Frame(body, bg=PALETTE["window"])
        actions.pack(fill="x", pady=(0, 10))
        self._owner_button(actions, "Import photos", self.import_photos).pack(side="left")
        self._owner_button(actions, "Open photo folder", self._open_photo_folder).pack(side="left", padx=8)
        self.photo_list = self._dark_listbox(body)
        self.photo_list.pack(fill="both", expand=True)
        self.refresh_photos()

    def _open_photo_folder(self) -> None:
        folder = self.db.root / "photos"
        folder.mkdir(parents=True, exist_ok=True)
        if hasattr(__import__("os"), "startfile"):
            __import__("os").startfile(folder)
        else:
            self.owner_notice.set("The photo folder is available from Sarah's app-data folder.")

    # ------------------------------------------------------------------
    # Connections: private route, ElevenLabs, Gmail, secure devices
    # ------------------------------------------------------------------
    def _build_devices(self):
        body = tk.Frame(self.device_tab, bg=PALETTE["window"])
        body.pack(fill="both", expand=True, padx=4, pady=2)
        self._page_heading(
            body,
            "Connections",
            "Connect only what you choose. Sarah never treats finding a device as proof that it belongs to you.",
        )
        grid = tk.Frame(body, bg=PALETTE["window"])
        grid.pack(fill="both", expand=True)
        grid.grid_columnconfigure(0, weight=1, uniform="connections")
        grid.grid_columnconfigure(1, weight=1, uniform="connections")
        grid.grid_rowconfigure(1, weight=1)

        voice = self._connection_card(
            grid,
            "Sarah's voice",
            "Hear the approved ElevenLabs route. A test never silently counts Windows speech as ElevenLabs.",
            0,
            0,
        )
        self._owner_button(voice, "Hear Sarah (ElevenLabs)", self.hear_sarah_elevenlabs).pack(anchor="w")
        self._owner_button(voice, "Connect Sarah private access", self.connect_private_access).pack(anchor="w", pady=(8, 0))

        self._gmail_monitor_enabled = tk.BooleanVar(value=False)
        if event_gmail_available():
            gmail = self._connection_card(
                grid,
                "Gmail travel updates",
                "Optional read-only access. Sarah cannot send, delete, mark read, or change your mail.",
                0,
                1,
            )
            gmail_actions = tk.Frame(gmail, bg=PALETTE["panel"])
            gmail_actions.pack(fill="x")
            self._owner_button(gmail_actions, "Connect Gmail read-only", self.connect_gmail).pack(side="left")
            self._owner_button(gmail_actions, "Check travel mail", self.review_travel_mail).pack(side="left", padx=7)
            self._owner_button(gmail_actions, "Disconnect", self.disconnect_gmail).pack(side="left")
            self._owner_button(gmail, "Review email suggestions in Calendar", lambda: self._show_page("calendar")).pack(anchor="w", pady=(8, 0))
            self._gmail_monitor_enabled.set(
                self.db.get_setting(self._gmail_setting_key("gmail_monitor_enabled"), "0") == "1"
            )
            tk.Checkbutton(
                gmail,
                text="Monitor travel updates about every 6 hours while Sarah is running",
                variable=self._gmail_monitor_enabled,
                command=self._owner_changed_gmail_monitor,
                bg=PALETTE["panel"],
                fg=PALETTE["soft"],
                activebackground=PALETTE["panel"],
                activeforeground=PALETTE["text"],
                selectcolor=PALETTE["field"],
                font=("Segoe UI", 9),
                anchor="w",
            ).pack(fill="x", pady=(8, 0))
            self.gmail_list = self._dark_listbox(gmail, height=5)
            self.gmail_list.pack(fill="both", expand=True, pady=(10, 0))
        else:
            # Keep internal callbacks fail-closed without presenting Gmail as
            # an event feature. This hidden widget is never packed.
            self.gmail_list = self._dark_listbox(body, height=1)

        devices = self._connection_card(
            grid,
            "Your Sarah devices",
            "Sarah can discover nearby Sarah apps, but trust begins only after the same short code is approved on both devices.",
            1,
            0,
        )
        device_actions = tk.Frame(devices, bg=PALETTE["panel"])
        device_actions.pack(fill="x")
        self._owner_button(device_actions, "Find my Sarah devices", self.scan_for_devices).pack(side="left")
        self._owner_button(device_actions, "Verify selected device", self.verify_selected_device).pack(side="left", padx=7)
        self.device_results_list = self._dark_listbox(devices, height=6)
        self.device_results_list.pack(fill="both", expand=True, pady=(10, 0))

        backup = self._connection_card(
            grid,
            "Private backup",
            "Move Sarah's encrypted owner archive without exposing its contents.",
            1,
            1,
        )
        self._owner_button(backup, "Create encrypted backup", self.backup).pack(anchor="w")
        self._owner_button(backup, "Restore encrypted backup", self.restore).pack(anchor="w", pady=(8, 0))
        self._owner_button(backup, "Revoke a trusted device", self.revoke_device).pack(anchor="w", pady=(8, 0))

    def _connection_card(
        self,
        parent: tk.Widget,
        title: str,
        description: str,
        row: int,
        column: int,
    ) -> tk.Frame:
        card = tk.Frame(parent, bg=PALETTE["panel"], padx=14, pady=13)
        card.grid(row=row, column=column, sticky="nsew", padx=5, pady=5)
        tk.Label(
            card,
            text=title,
            bg=PALETTE["panel"],
            fg=PALETTE["text"],
            font=("Segoe UI", 13, "bold"),
        ).pack(anchor="w")
        tk.Label(
            card,
            text=description,
            bg=PALETTE["panel"],
            fg=PALETTE["muted"],
            wraplength=375,
            justify="left",
            font=("Segoe UI", 9),
        ).pack(anchor="w", pady=(3, 10))
        return card

    def configure_online_services(self):
        self.connect_private_access()

    def connect_private_access(self) -> None:
        endpoint = runtime_setting("SARAH_MODEL_BACKEND_URL", root=self.db.root)
        if not endpoint.startswith("https://"):
            messagebox.showinfo(
                "Connect Sarah",
                "This build does not include Sarah's protected service address. No access code was requested or saved.",
                parent=self.root,
            )
            return
        code = simpledialog.askstring(
            "Connect Sarah",
            "Enter your Sarah private access code. This is encrypted for this Windows account. Do not enter an ElevenLabs or other provider key here.",
            show="*",
            parent=self.root,
        )
        if not safe_text(code):
            return
        settings = load_runtime_config(self.db.root)
        for name in (
            "SARAH_MODEL_BACKEND_URL",
            "SARAH_MODEL_PROVIDER",
            "SARAH_MODEL_ID",
            "SARAH_ELEVENLABS_BACKEND_URL",
            "SARAH_ELEVENLABS_VOICE_ID",
            "SARAH_ELEVENLABS_MODEL_ID",
            "SARAH_TAVILY_BACKEND_URL",
            "SARAH_STAY22_BACKEND_URL",
        ):
            value = runtime_setting(name, root=self.db.root)
            if value:
                settings[name] = value
        settings["SARAH_MODEL_BACKEND_TOKEN"] = safe_text(code)
        settings["SARAH_ELEVENLABS_BACKEND_TOKEN"] = safe_text(code)
        try:
            save_runtime_config(settings, self.db.root)
            self.voice = ElevenLabsVoice(self.db.root)
        except Exception as error:
            messagebox.showerror("Connect Sarah", str(error), parent=self.root)
            return
        self.owner_notice.set("Sarah's private connection was saved for this Windows account.")
        self._refresh_status_chips()

    def hear_sarah_elevenlabs(self) -> None:
        if not self.voice.configured:
            messagebox.showinfo(
                "Hear Sarah",
                "ElevenLabs is not connected yet. Choose Connect Sarah private access first. No substitute voice was played.",
                parent=self.root,
            )
            return
        generation = self._begin_voice_generation("owner_started_elevenlabs_test")
        profile = self.db.active_profile()
        person_id = profile.get("person_id") or self.db.get_setting("active_person_id")
        self.owner_notice.set("Sarah is preparing the ElevenLabs voice test…")
        threading.Thread(
            target=self._elevenlabs_test_worker,
            args=(generation, person_id),
            daemon=True,
        ).start()

    def _elevenlabs_test_worker(self, generation: int, person_id: str | None) -> None:
        text = "Hi. I'm Sarah. If you can hear me, my ElevenLabs voice is connected."
        requested = int(time.time() * 1000)
        synthesis_start = int(time.time() * 1000)
        synthesis_end = playback_start = playback_end = 0
        actual = "TEXT_ONLY"
        outcome = "The ElevenLabs test did not complete. No substitute voice was played."
        failure = ""
        self.speaking = True
        try:
            audio_path = self.voice.synthesize(
                text,
                should_cancel=lambda: not self._voice_request_is_current(person_id, generation),
                total_budget_seconds=15.0,
            )
            synthesis_end = int(time.time() * 1000)
            if not self._voice_request_is_current(person_id, generation):
                failure = self._voice_request_failure_reason(person_id, generation)
                return
            playback_start = int(time.time() * 1000)
            played, reason = self._play_audio_file(audio_path, generation)
            playback_end = int(time.time() * 1000)
            if reason:
                failure = reason
            elif played:
                actual = "ELEVENLABS"
                outcome = "Sarah's ElevenLabs voice test completed."
            else:
                failure = "windows_audio_player_failed"
        except Exception as error:
            if not synthesis_end:
                synthesis_end = int(time.time() * 1000)
            failure = type(error).__name__
        finally:
            self.speaking = False
            self._record_voice_receipt(
                "ELEVENLABS",
                actual,
                outcome,
                requested,
                synthesis_start,
                synthesis_end,
                playback_start,
                playback_end,
                failure,
                len(text),
                bool(getattr(self.voice, "last_cache_hit", False)),
                str(getattr(self.voice, "last_cache_key", "")),
                str(getattr(self.voice, "model", "")),
                str(getattr(self.voice, "voice_id", "")),
                str(getattr(self.voice, "last_route_identity", "")),
                str(getattr(self.voice, "last_content_type", "")),
                person_id,
                "owner-elevenlabs-test",
                str(getattr(self.voice, "last_route_receipt", "")),
            )

    def _gmail_person_id(self) -> str:
        return safe_text(self.db.get_setting("active_person_id"))

    def _gmail_setting_key(self, name: str, person_id: str | None = None) -> str:
        person = safe_text(person_id) or self._gmail_person_id()
        return f"{safe_text(name)}:{person}"

    def _gmail_vault(self, person_id: str | None = None):
        if GmailTokenVault is None:
            raise RuntimeError("Gmail token support is unavailable")
        return GmailTokenVault(self.db.root, safe_text(person_id) or self._gmail_person_id())

    def _gmail_backend_ready(self) -> bool:
        return event_gmail_available() and all(
            value is not None
            for value in (
                GmailReadOnlyOAuth,
                GmailTokenVault,
                inspect_desktop_oauth_client,
                list_travel_message_candidates,
                resolve_desktop_oauth_client_path,
                revoke_google_authorization,
            )
        )

    def connect_gmail(self) -> None:
        if not self._gmail_backend_ready():
            self._gmail_unavailable()
            return
        path = self._saved_gmail_client_path()
        if path is None:
            messagebox.showinfo(
                "Gmail setup is not in this build",
                "This Sarah build has not been connected to the project's Google sign-in identity yet. "
                "No browser opened and no mail was accessed. Install a configured owner build, then press Connect Gmail again.",
                parent=self.root,
            )
            return
        self.owner_notice.set("Opening Google's read-only Gmail permission screen…")
        if self._gmail_connect_in_flight:
            self.owner_notice.set("Google sign-in is already waiting for this Sarah window.")
            return
        person_id = self._gmail_person_id()
        self._gmail_connect_in_flight = True
        threading.Thread(
            target=self._gmail_connect_worker,
            args=(path, person_id),
            daemon=True,
        ).start()

    def _offer_gmail_after_profile(self) -> None:
        """Offer optional Gmail only after local/imported owner identity exists."""

        if not event_gmail_available():
            return

        key = self._gmail_setting_key("gmail_profile_offer_shown")
        if self.db.get_setting(key, "0") == "1":
            return
        self.db.set_setting(key, "1")
        if not self._gmail_backend_ready():
            return
        wants_gmail = messagebox.askyesno(
            "Connect travel email?",
            "Would you like to connect Gmail with read-only access so Sarah can help find travel confirmations and updates? "
            "She cannot send, delete, mark read, or change messages. You can connect later from Connections.",
            parent=self.root,
        )
        if wants_gmail:
            self._show_page("connections")
            self.connect_gmail()

    def _owner_changed_gmail_monitor(self) -> None:
        enabled = bool(self._gmail_monitor_enabled.get())
        if enabled and not self._gmail_connected:
            self._gmail_monitor_enabled.set(False)
            self.db.set_setting(self._gmail_setting_key("gmail_monitor_enabled"), "0")
            messagebox.showinfo(
                "Connect Gmail first",
                "Connect Gmail read-only before enabling travel-update monitoring.",
                parent=self.root,
            )
            return
        self.db.set_setting(
            self._gmail_setting_key("gmail_monitor_enabled"),
            "1" if enabled else "0",
        )
        self.owner_notice.set(
            "Read-only Gmail travel monitoring is on while Sarah is running."
            if enabled
            else "Automatic Gmail travel monitoring is off."
        )
        if enabled:
            self.db.set_setting(self._gmail_setting_key("gmail_monitor_last_attempt_epoch"), "0")
            self.db.set_setting(
                self._gmail_setting_key("gmail_monitor_backoff_seconds"),
                str(6 * 60 * 60),
            )
            self._schedule_gmail_monitor_tick(delay_ms=500)

    def _schedule_gmail_monitor_tick(self, *, delay_ms: int = 60_000) -> None:
        if self._gmail_monitor_after_id is not None:
            try:
                self.root.after_cancel(self._gmail_monitor_after_id)
            except (AttributeError, tk.TclError):
                pass
        self._gmail_monitor_after_id = self.root.after(delay_ms, self._gmail_monitor_tick)

    def _gmail_monitor_tick(self) -> None:
        self._gmail_monitor_after_id = None
        person_id = self._gmail_person_id()
        enabled = self.db.get_setting(
            self._gmail_setting_key("gmail_monitor_enabled", person_id), "0"
        ) == "1"
        last_raw = self.db.get_setting(
            self._gmail_setting_key("gmail_monitor_last_attempt_epoch", person_id), "0"
        )
        backoff_raw = self.db.get_setting(
            self._gmail_setting_key("gmail_monitor_backoff_seconds", person_id),
            str(6 * 60 * 60),
        )
        try:
            last_attempt = float(last_raw)
        except (TypeError, ValueError):
            last_attempt = 0.0
        try:
            backoff = min(24 * 60 * 60, max(6 * 60 * 60, int(backoff_raw)))
        except (TypeError, ValueError):
            backoff = 6 * 60 * 60
        if (
            enabled
            and self._gmail_connected
            and not self._gmail_check_in_flight
            and time.time() - last_attempt >= backoff
        ):
            self._start_gmail_review(automatic=True, person_id=person_id)
        self._schedule_gmail_monitor_tick()

    def _gmail_connect_worker(self, client_path: Path, person_id: str) -> None:
        try:
            oauth = GmailReadOnlyOAuth(self._gmail_vault(person_id))
            receipt = oauth.connect(client_path)
            self.db.set_setting("gmail_oauth_client_path", "")
            self.db.set_setting(
                self._gmail_setting_key("gmail_oauth_client_sha256", person_id),
                receipt.client_sha256,
            )
            self.tasks.put(("gmail_connected", {"receipt": receipt, "person_id": person_id}))
        except Exception as error:
            self.tasks.put(("gmail_failed", {
                "error": safe_text(error) or type(error).__name__,
                "automatic": False,
                "person_id": person_id,
                "during_connect": True,
            }))

    def review_travel_mail(self) -> None:
        if not self._gmail_backend_ready():
            self._gmail_unavailable()
            return
        path = self._saved_gmail_client_path()
        if path is None:
            messagebox.showinfo(
                "Gmail travel updates",
                "Connect Gmail first. Sarah requested no mail and changed nothing.",
                parent=self.root,
            )
            return
        self._start_gmail_review(
            automatic=False,
            client_path=path,
            person_id=self._gmail_person_id(),
        )

    def _start_gmail_review(
        self,
        *,
        automatic: bool,
        client_path: Path | None = None,
        person_id: str | None = None,
    ) -> None:
        path = client_path or self._saved_gmail_client_path()
        if path is None or self._gmail_check_in_flight:
            return
        initiating_person = safe_text(person_id) or self._gmail_person_id()
        self._gmail_check_in_flight = True
        self.db.set_setting(
            self._gmail_setting_key("gmail_monitor_last_attempt_epoch", initiating_person),
            str(time.time()),
        )
        if not automatic:
            self.owner_notice.set("Checking read-only travel messages…")
        threading.Thread(
            target=self._gmail_review_worker,
            args=(path, initiating_person, automatic),
            daemon=True,
        ).start()

    def _gmail_review_worker(
        self,
        client_path: Path,
        person_id: str,
        automatic: bool = False,
    ) -> None:
        try:
            from googleapiclient.discovery import build

            oauth = GmailReadOnlyOAuth(self._gmail_vault(person_id))
            credentials = oauth.credentials(client_path)
            client = inspect_desktop_oauth_client(client_path)
            receipt = oauth.vault.receipt(client)
            service = build("gmail", "v1", credentials=credentials, cache_discovery=False)
            rows = list_travel_message_candidates(service, max_results=25)
            self.tasks.put(("gmail_results", {
                "rows": rows,
                "automatic": automatic,
                "person_id": person_id,
                "account_email": receipt.account_email,
            }))
        except Exception as error:
            self.tasks.put(("gmail_failed", {
                "error": safe_text(error) or type(error).__name__,
                "automatic": automatic,
                "person_id": person_id,
            }))

    def disconnect_gmail(self) -> None:
        if not self._gmail_backend_ready():
            self._gmail_unavailable()
            return
        person_id = self._gmail_person_id()
        vault = self._gmail_vault(person_id)
        path = self._saved_gmail_client_path()
        if path is None and not vault.path.is_file():
            self.owner_notice.set("Gmail is not connected on this computer.")
            return
        if not messagebox.askyesno(
            "Disconnect Gmail",
            "Remove Sarah's encrypted read-only Gmail authorization from this computer? This does not delete or change any mail.",
            parent=self.root,
        ):
            return
        try:
            result = None
            if path is not None:
                try:
                    client = inspect_desktop_oauth_client(path)
                    result = vault.disconnect(client, revoke=revoke_google_authorization)
                except Exception:
                    vault.remove_local()
            else:
                vault.remove_local()
            purged = 0
            if self.calendar_store is not None:
                purged = self.calendar_store.purge_unsaved_email_proposals(
                    person_id=person_id
                )
            self.db.set_setting("gmail_oauth_client_path", "")
            self._gmail_connected = False
            self._gmail_account = ""
            for name in (
                "gmail_monitor_enabled",
                "gmail_monitor_last_attempt_epoch",
                "gmail_monitor_backoff_seconds",
                "gmail_oauth_client_sha256",
            ):
                self.db.set_setting(self._gmail_setting_key(name, person_id), "")
            if hasattr(self, "_gmail_monitor_enabled"):
                self._gmail_monitor_enabled.set(False)
            self.gmail_list.delete(0, "end")
            self.refresh_calendar()
            self.owner_notice.set(
                (
                    f"Gmail was disconnected, Google access was revoked, and {purged} unsaved email suggestion(s) were removed."
                    if result is not None and result.remote_revocation_succeeded
                    else f"Gmail was disconnected locally and {purged} unsaved email suggestion(s) were removed. Google revocation could not be confirmed; remove Sarah from your Google Account connections if needed."
                )
            )
        except Exception as error:
            messagebox.showerror("Disconnect Gmail", str(error), parent=self.root)
        self._refresh_status_chips()

    def _gmail_unavailable(self) -> None:
        messagebox.showinfo(
            "Gmail unavailable",
            "This build does not contain Sarah's read-only Gmail connection module. No browser opened and no mail was accessed.",
            parent=self.root,
        )

    def _saved_gmail_client_path(self) -> Path | None:
        if resolve_desktop_oauth_client_path is not None:
            current = resolve_desktop_oauth_client_path()
            if current is not None:
                return current
        if bool(getattr(sys, "frozen", False)):
            return None
        # Compatibility for a previous source build. The normal owner surface
        # never asks Robert to select this path.
        value = safe_text(self.db.get_setting("gmail_oauth_client_path"))
        path = Path(value).expanduser() if value else None
        return path if path is not None and path.is_file() else None

    def _refresh_gmail_connection_state(self) -> None:
        self._gmail_connected = False
        self._gmail_account = ""
        if hasattr(self, "_gmail_monitor_enabled"):
            self._gmail_monitor_enabled.set(
                self.db.get_setting(
                    self._gmail_setting_key("gmail_monitor_enabled"), "0"
                ) == "1"
            )
        if not self._gmail_backend_ready():
            return
        path = self._saved_gmail_client_path()
        if path is None:
            return
        try:
            client = inspect_desktop_oauth_client(path)
            receipt = self._gmail_vault(self._gmail_person_id()).receipt(client)
            self._gmail_connected = bool(receipt.connected)
            self._gmail_account = safe_text(receipt.account_email)
        except Exception:
            self._gmail_connected = False

    def scan_for_devices(self) -> None:
        self._begin_device_scan(automatic=False)

    def _auto_device_scan(self) -> None:
        self._begin_device_scan(automatic=True)
        if self.root.winfo_exists():
            self.root.after(30_000, self._auto_device_scan)

    def _begin_device_scan(self, *, automatic: bool) -> None:
        if self._local_discovery is None:
            if not automatic:
                messagebox.showinfo(
                    "Find Sarah devices",
                    "Secure Sarah device discovery is unavailable in this build. No device was trusted or contacted.",
                    parent=self.root,
                )
            return
        if self._device_scan_in_flight:
            return
        self._device_scan_in_flight = True
        if not automatic:
            self.owner_notice.set("Looking for nearby Sarah devices…")
        threading.Thread(
            target=self._device_scan_worker,
            args=(automatic,),
            daemon=True,
        ).start()

    def _device_scan_worker(self, automatic: bool) -> None:
        try:
            rows = self._local_discovery.scan(timeout_seconds=1.2)
            self.tasks.put(("device_results", {"rows": rows, "automatic": automatic}))
        except Exception as error:
            self.tasks.put(("device_scan_failed", {"error": safe_text(error), "automatic": automatic}))

    def verify_selected_device(self) -> None:
        selection = self.device_results_list.curselection()
        if not selection:
            messagebox.showinfo("Verify a device", "Select a discovered device first.", parent=self.root)
            return
        index = int(selection[0])
        if index >= len(self._discovered_devices):
            return
        device = self._discovered_devices[index]
        self._offer_device_verification(device)

    def _offer_device_verification(self, device: object) -> None:
        name = safe_text(getattr(device, "device_name", "Nearby Sarah device"))
        host = safe_text(getattr(device, "host", "local network"))
        accepted = messagebox.askyesno(
            "Is this your device?",
            f"Sarah found {name} on your local network ({host}).\n\nIs this one of your devices? Discovery alone will not trust it.",
            parent=self.root,
        )
        if not accepted:
            self.owner_notice.set(f"{name} was not trusted.")
            return
        if begin_pairing_initiator is None:
            self.owner_notice.set("Windows cannot initiate secure Android pairing in this build; nothing was shared.")
            return
        self.owner_notice.set(
            f"Preparing the same six-digit code with {name}; both devices must approve it, and it is not trusted yet."
        )
        def begin() -> None:
            try:
                pending = begin_pairing_initiator(
                    device,
                    local_instance_id=self.db.device_id,
                    local_device_name=f"Sarah on {socket.gethostname() or 'Windows'}",
                    local_device_type="windows",
                )
                self.tasks.put(("pairing_initiator_pending", pending))
            except Exception as error:
                self.tasks.put(("pairing_failed", safe_text(error)))
        threading.Thread(target=begin, name="SarahWindowsPairingInitiator", daemon=True).start()

    # ------------------------------------------------------------------
    # Human-readable activity and task completion
    # ------------------------------------------------------------------
    def _build_activity(self):
        body = tk.Frame(self.activity_tab, bg=PALETTE["window"])
        body.pack(fill="both", expand=True, padx=4, pady=2)
        self._page_heading(
            body,
            "Activity",
            "A simple history of what Sarah said or completed. Technical receipts stay out of the normal view.",
        )
        actions = tk.Frame(body, bg=PALETTE["window"])
        actions.pack(fill="x", pady=(0, 10))
        self._owner_button(actions, "Refresh", self.refresh_activity).pack(side="left")
        self._owner_button(actions, "Advanced details", self.open_private_event).pack(side="left", padx=8)
        self.activity_list = self._dark_listbox(body)
        self.activity_list.pack(fill="both", expand=True)
        self.activity_rows = []
        self.refresh_activity()

    def refresh_activity(self):
        self.activity_rows = self.db.visible_activity(200)
        self.activity_list.delete(0, "end")
        for row in self.activity_rows:
            spoken = safe_text(row.get("spoken"))
            if spoken:
                self.activity_list.insert("end", "Sarah • " + spoken[:150])

    def _poll_tasks(self):
        try:
            while True:
                kind, payload = self.tasks.get_nowait()
                if kind == "reply":
                    item = dict(payload)
                    response = item["response"]
                    person_id = item.get("person_id")
                    if person_id != self.db.get_setting("active_person_id"):
                        self.turn_in_flight = False
                        self._set_composer_enabled(True)
                        self.owner_notice.set("A reply stayed with the profile that requested it.")
                        continue
                    self._last_mind_route = safe_text(response.route)
                    self._append("Sarah", response.spoken)
                    self.turn_in_flight = False
                    self._set_composer_enabled(True)
                    self.owner_notice.set("Sarah replied. Her voice is being prepared.")
                    self._speak(
                        response.spoken,
                        person_id,
                        item.get("turn_id", ""),
                        item.get("voice_generation"),
                    )
                    self.refresh_activity()
                elif kind == "research":
                    text = safe_text(payload).lower()
                    self.owner_notice.set(
                        "Sarah finished checking current sources."
                        if "finished" in text
                        else "Sarah could not complete that source check."
                    )
                    self.refresh_discoveries()
                elif kind == "voice_route":
                    self._load_latest_voice_receipt()
                    actual = safe_text(self._latest_voice_receipt.get("actual_route"))
                    self.owner_notice.set(
                        "Sarah's ElevenLabs voice finished playing."
                        if actual == "ELEVENLABS"
                        else "Sarah's voice was not ElevenLabs; Connections shows the current route."
                    )
                elif kind == "gmail_connected":
                    item = dict(payload)
                    receipt = item["receipt"]
                    person_id = safe_text(item.get("person_id"))
                    self._gmail_connect_in_flight = False
                    if person_id != self._gmail_person_id():
                        self.owner_notice.set(
                            "Google sign-in stayed with the profile that requested it."
                        )
                        continue
                    self._gmail_connected = bool(receipt.connected)
                    self._gmail_account = safe_text(receipt.account_email)
                    self.owner_notice.set("Gmail connected with read-only travel access.")
                    if hasattr(self, "_gmail_monitor_enabled"):
                        enable = messagebox.askyesno(
                            "Monitor travel updates?",
                            "Would you like Sarah to check read-only travel-message metadata about every six hours while she is running? This is off unless you choose Yes.",
                            parent=self.root,
                        )
                        self._gmail_monitor_enabled.set(enable)
                        self.db.set_setting(
                            self._gmail_setting_key("gmail_monitor_enabled", person_id),
                            "1" if enable else "0",
                        )
                        if enable:
                            self.db.set_setting(
                                self._gmail_setting_key("gmail_monitor_last_attempt_epoch", person_id),
                                "0",
                            )
                            self.db.set_setting(
                                self._gmail_setting_key("gmail_monitor_backoff_seconds", person_id),
                                str(6 * 60 * 60),
                            )
                            self._schedule_gmail_monitor_tick(delay_ms=500)
                elif kind == "gmail_results":
                    item = dict(payload)
                    rows = list(item.get("rows", []))
                    automatic = bool(item.get("automatic"))
                    person_id = safe_text(item.get("person_id"))
                    account_email = safe_text(item.get("account_email")).lower()
                    self._gmail_check_in_flight = False
                    self.db.set_setting(
                        self._gmail_setting_key("gmail_monitor_backoff_seconds", person_id),
                        str(6 * 60 * 60),
                    )
                    if (
                        person_id != self._gmail_person_id()
                        or not account_email
                        or account_email != safe_text(self._gmail_account).lower()
                    ):
                        self.owner_notice.set(
                            "A Gmail result stayed with the profile and account that requested it; no current-profile calendar data changed."
                        )
                        continue
                    self.gmail_list.delete(0, "end")
                    for row in rows:
                        subject = safe_text(row.get("subject")) or "No subject"
                        sender = safe_text(row.get("from")) or "Unknown sender"
                        date = safe_text(row.get("date"))
                        self.gmail_list.insert("end", f"{subject} • {sender} • {date}")
                    self.owner_notice.set(
                        f"Sarah found {len(rows)} read-only travel message candidate(s)"
                        + (" during the scheduled check." if automatic else ".")
                    )
                    created_proposals: list[dict[str, object]] = []
                    if self.calendar_store is not None:
                        try:
                            created_proposals = self.calendar_store.ingest_email_candidates(
                                rows,
                                account_email=account_email,
                                person_id=person_id,
                            )
                        except Exception:
                            self.owner_notice.set(
                                "Travel mail was read, but Sarah could not verify the private calendar-proposal store. Nothing was saved."
                            )
                    self._gmail_candidate_proposals = created_proposals
                    self.refresh_calendar()
                    if created_proposals:
                        self.root.after(
                            0,
                            lambda proposal=created_proposals[0]: self._offer_new_email_proposal(proposal),
                        )
                elif kind == "gmail_failed":
                    item = dict(payload) if isinstance(payload, dict) else {"error": payload}
                    if item.get("during_connect"):
                        self._gmail_connect_in_flight = False
                    self._gmail_check_in_flight = False
                    person_id = safe_text(item.get("person_id")) or self._gmail_person_id()
                    error_text = safe_text(item.get("error")).lower()
                    automatic = bool(item.get("automatic"))
                    if automatic:
                        previous = self.db.get_setting(
                            self._gmail_setting_key("gmail_monitor_backoff_seconds", person_id),
                            str(6 * 60 * 60),
                        )
                        try:
                            next_backoff = min(24 * 60 * 60, max(6 * 60 * 60, int(previous) * 2))
                        except (TypeError, ValueError):
                            next_backoff = 12 * 60 * 60
                        self.db.set_setting(
                            self._gmail_setting_key("gmail_monitor_backoff_seconds", person_id),
                            str(next_backoff),
                        )
                    if any(marker in error_text for marker in ("invalid_grant", "revoked", "expired without a refresh token")):
                        self.db.set_setting(
                            self._gmail_setting_key("gmail_monitor_enabled", person_id), "0"
                        )
                        if person_id == self._gmail_person_id() and hasattr(self, "_gmail_monitor_enabled"):
                            self._gmail_monitor_enabled.set(False)
                    if person_id == self._gmail_person_id():
                        self._refresh_gmail_connection_state()
                    self.owner_notice.set("Gmail did not connect or refresh. No mail was changed.")
                    if not automatic:
                        messagebox.showerror(
                            "Gmail travel updates",
                            "Gmail did not complete. Check the internet connection or reconnect Gmail. No mail was changed.",
                            parent=self.root,
                        )
                elif kind == "device_results":
                    self._device_scan_in_flight = False
                    item = dict(payload)
                    rows = list(item.get("rows", []))
                    self._discovered_devices = rows
                    self.device_results_list.delete(0, "end")
                    for device in rows:
                        self.device_results_list.insert(
                            "end",
                            f"{getattr(device, 'device_name', 'Sarah device')} • {getattr(device, 'device_type', 'device')} • not trusted",
                        )
                    if rows and not item.get("automatic"):
                        self.owner_notice.set(f"Sarah found {len(rows)} nearby device(s).")
                    elif not rows and not item.get("automatic"):
                        self.owner_notice.set("No nearby Sarah device answered this scan.")
                    for device in rows:
                        instance = safe_text(getattr(device, "instance_id", ""))
                        if instance and instance not in self._known_device_instances:
                            self._known_device_instances.add(instance)
                            self._offer_device_verification(device)
                            break
                elif kind == "device_scan_failed":
                    self._device_scan_in_flight = False
                    if not dict(payload).get("automatic"):
                        self.owner_notice.set("Device discovery did not complete; no device was trusted.")
                elif kind == "pairing_initiator_pending":
                    pending = payload
                    approved = messagebox.askyesno(
                        "Do both devices show this code?",
                        f"Windows code: {pending.sas_code}\n\n"
                        f"Other device: {pending.peer.device_name}\n\n"
                        "Approve only if the established Android Sarah shows the exact same six digits. "
                        "No profile, Gmail, model, provider, voice, or photo data has moved.",
                        parent=self.root,
                    )
                    if not approved:
                        pending.close()
                        self.owner_notice.set("Windows rejected the pairing code; nothing was trusted or imported.")
                        continue
                    self.owner_notice.set("Windows approved the code; waiting for Android approval.")
                    def finish_initiator(active=pending) -> None:
                        try:
                            credential = active.complete(owner_confirmed_matching_code=True)
                            self.tasks.put(("pairing_initiator_complete", {
                                "credential": credential, "peer": active.peer,
                            }))
                        except Exception as error:
                            self.tasks.put(("pairing_failed", safe_text(error)))
                    threading.Thread(
                        target=finish_initiator,
                        name="SarahWindowsPairingConfirmation",
                        daemon=True,
                    ).start()
                elif kind == "pairing_initiator_complete":
                    item = dict(payload)
                    credential = item["credential"]
                    peer = item["peer"]
                    timestamp = int(time.time() * 1000)
                    if self._pairing_credential_vault is None or pull_android_preview is None:
                        raise RuntimeError("Secure Android continuity support is unavailable")
                    self._pairing_credential_vault.save(credential)
                    token_hash = hashlib.sha256(
                        safe_text(credential.token).encode("utf-8")
                    ).hexdigest()
                    with self.db.connect() as database:
                        database.execute(
                            "INSERT INTO trusted_devices VALUES(?,?,?,?,?,0) "
                            "ON CONFLICT(device_id) DO UPDATE SET "
                            "device_name=excluded.device_name,token_hash=excluded.token_hash,"
                            "paired_at=excluded.paired_at,last_seen=excluded.last_seen,revoked=0",
                            (
                                safe_text(credential.peer_instance_id),
                                safe_text(credential.peer_device_name),
                                token_hash, timestamp, timestamp,
                            ),
                        )
                    self.owner_notice.set(
                        "Both screens approved the code. Requesting an encrypted Android continuity preview; nothing imports automatically."
                    )
                    def pull(peer_value=peer, token=safe_text(credential.token)) -> None:
                        try:
                            preview = pull_android_preview(
                                host=peer_value.host,
                                port=peer_value.pairing_port,
                                device_id=self.db.device_id,
                                token=token,
                            )
                            self.tasks.put(("reverse_sync_preview", preview))
                        except Exception as error:
                            self.tasks.put(("reverse_sync_failed", safe_text(error)))
                    threading.Thread(target=pull, name="SarahWindowsAndroidPreview", daemon=True).start()
                elif kind == "reverse_sync_preview":
                    preview = payload
                    approved = messagebox.askyesno(
                        "Import this Sarah continuity?",
                        preview.summary()
                        + "\n\nImport adds unseen records, keeps existing records, and writes append-only decision and result receipts.",
                        parent=self.root,
                    )
                    if not approved:
                        self.owner_notice.set("Android continuity preview declined. Nothing was imported; device trust remains.")
                        continue
                    if import_reviewed_android_preview is None:
                        self.owner_notice.set("Windows import support is unavailable; nothing was imported.")
                        continue
                    def apply_preview(reviewed=preview) -> None:
                        try:
                            counts = import_reviewed_android_preview(self.db, reviewed)
                            self.tasks.put(("reverse_sync_imported", counts))
                        except Exception as error:
                            self.tasks.put(("reverse_sync_failed", safe_text(error)))
                    threading.Thread(target=apply_preview, name="SarahWindowsAndroidImport", daemon=True).start()
                elif kind == "reverse_sync_imported":
                    counts = dict(payload)
                    total = sum(int(value) for value in counts.values())
                    profile = self.db.active_profile()
                    self._dismiss_onboarding()
                    self.root.after(0, self._offer_gmail_after_profile)
                    self.owner_notice.set(
                        f"Imported {total} new continuity item(s) for {safe_text(profile.get('name')) or 'the confirmed owner'}. Existing records were kept."
                    )
                    self._refresh_status_chips()
                elif kind == "reverse_sync_failed":
                    self.owner_notice.set(
                        f"Android continuity did not import: {safe_text(payload)}. Existing Windows data remains unchanged."
                    )
                elif kind == "pairing_pending":
                    pending = payload
                    wants_connection = messagebox.askyesno(
                        "Connect this Sarah device?",
                        f"Sarah found {pending.peer_device_name} on your private network.\n\n"
                        "Do you want to prepare a secure device connection? Declining leaves this "
                        "computer's local profile setup unchanged.",
                        parent=self.root,
                    )
                    if not wants_connection:
                        try:
                            pending.reject()
                        except Exception:
                            pass
                        self.owner_notice.set(
                            "The nearby device was declined. Continue local setup whenever you are ready."
                        )
                        onboarding_error = getattr(self, "_onboarding_error", None)
                        if onboarding_error is not None:
                            onboarding_error.set(
                                "No device was connected. You can continue this computer's local setup."
                            )
                        continue
                    approved = messagebox.askyesno(
                        "Do both devices show this code?",
                        f"Windows code: {pending.sas_code}\n\n"
                        f"Other device: {pending.peer_device_name}\n\n"
                        "Approve only if the other device shows the exact same six digits. "
                        "No profile, Gmail, model, provider, or travel data has been shared.",
                        parent=self.root,
                    )
                    try:
                        if approved:
                            pending.approve(expected_sas_code=pending.sas_code)
                            self.owner_notice.set(
                                "Windows approved the matching code; waiting for approval on the other device."
                            )
                        else:
                            pending.reject()
                            self.owner_notice.set("Device pairing was rejected; nothing was trusted.")
                    except Exception:
                        self.owner_notice.set("The pairing request expired or changed; nothing was trusted.")
                elif kind == "pairing_complete":
                    credential = payload
                    timestamp = int(time.time() * 1000)
                    token_hash = hashlib.sha256(
                        safe_text(credential.token).encode("utf-8")
                    ).hexdigest()
                    if self._pairing_credential_vault is None:
                        raise RuntimeError(
                            "Encrypted post-trust credential storage is unavailable"
                        )
                    self._pairing_credential_vault.save(credential)
                    with self.db.connect() as database:
                        database.execute(
                            "INSERT INTO trusted_devices VALUES(?,?,?,?,?,0) "
                            "ON CONFLICT(device_id) DO UPDATE SET "
                            "device_name=excluded.device_name,token_hash=excluded.token_hash,"
                            "paired_at=excluded.paired_at,last_seen=excluded.last_seen,revoked=0",
                            (
                                safe_text(credential.peer_instance_id),
                                safe_text(credential.peer_device_name),
                                token_hash,
                                timestamp,
                                timestamp,
                            ),
                        )
                    self.owner_notice.set(
                        f"{safe_text(credential.peer_device_name)} is trusted after matching-code approval on both devices. The other device may now request an encrypted owner-reviewed continuity preview."
                    )
                elif kind == "pairing_failed":
                    self.owner_notice.set(
                        "Secure device pairing did not complete; no device or credential was trusted."
                    )
                self._refresh_status_chips()
        except queue.Empty:
            pass
        self.root.after(100, self._poll_tasks)

    def _load_latest_voice_receipt(self) -> None:
        person_id = self.db.get_setting("active_person_id")
        raw = self.db.get_setting(f"voice_route_receipt:{person_id}")
        try:
            value = json.loads(raw) if raw else {}
            self._latest_voice_receipt = value if isinstance(value, dict) else {}
        except (TypeError, ValueError):
            self._latest_voice_receipt = {}

    def _refresh_status_chips(self) -> None:
        if not hasattr(self, "_status_chip_vars"):
            return
        self._load_latest_voice_receipt()
        configured_mind = bool(
            runtime_setting("SARAH_MODEL_BACKEND_URL", root=self.db.root)
            and runtime_setting("SARAH_MODEL_BACKEND_TOKEN", root=self.db.root)
        )
        self._status_chip_vars["mind"].set(
            mind_status_text(self._last_mind_route, configured_mind)
        )
        self._status_chip_vars["voice"].set(
            voice_status_text(self._latest_voice_receipt, bool(self.voice.configured))
        )
        if not self._gmail_backend_ready():
            gmail = "Gmail: unavailable"
        elif self._gmail_connected:
            gmail = "Gmail: connected"
        else:
            gmail = "Gmail: not connected"
        self._status_chip_vars["gmail"].set(gmail)
        trusted = 0
        try:
            with self.db.connect() as database:
                trusted = int(
                    database.execute(
                        "SELECT COUNT(*) FROM trusted_devices WHERE revoked=0"
                    ).fetchone()[0]
                )
        except Exception:
            trusted = 0
        self._status_chip_vars["devices"].set(
            f"Devices: {trusted} trusted" if trusted else "Devices: not paired"
        )

    # ------------------------------------------------------------------
    # Exact portrait only: main panel, compact presence, notification icon
    # ------------------------------------------------------------------
    def _load_required_portrait(self) -> None:
        resolved = resolve_sarah_portrait()
        if resolved is None:
            raise RuntimeError(
                "The exact approved Sarah portrait is missing or changed. Sarah stopped instead of showing substitute artwork."
            )
        with Image.open(resolved) as image:
            image.load()
            base = image.convert("RGB").copy()
        self._portrait_load_attempted = True
        self._portrait_base_image = base
        self._portrait_asset_path = resolved
        self._portrait_renderer = PortraitFrameRenderer(base, SARAH_PORTRAIT_DISPLAY_SIZE)
        self._owner_portrait_renderer = PortraitFrameRenderer(base, OWNER_PORTRAIT_SIZE)

    def _draw_vector_avatar(self):
        raise RuntimeError("Vector avatar substitution is disabled in the Sarah 2.5 owner build")

    def _start_corner(self):
        self.corner = tk.Toplevel(self.root)
        self.corner.title("Sarah")
        self.corner.geometry("196x226+20+80")
        self.corner.attributes("-topmost", True)
        self.corner.configure(bg=PALETTE["panel"])
        self.corner.overrideredirect(True)
        self.canvas = tk.Canvas(
            self.corner,
            width=SARAH_PORTRAIT_DISPLAY_SIZE[0],
            height=SARAH_PORTRAIT_DISPLAY_SIZE[1],
            bg=PALETTE["panel"],
            highlightthickness=0,
        )
        self.canvas.pack(padx=8, pady=(8, 2))
        self.canvas.bind("<ButtonPress-1>", self._drag_start)
        self.canvas.bind("<B1-Motion>", self._drag_move)
        self._owner_button(self.corner, "Talk with Sarah", self.show).pack(fill="x", padx=8, pady=(2, 8))
        self._animate_avatar()

    def _update_power_saving_label(self) -> None:
        variable = getattr(self, "_power_saving_text", None)
        if variable is not None:
            variable.set(
                "⚡ Power saving: ON"
                if self._portrait_power_saving
                else "⚡ Power saving"
            )
        label = getattr(self, "_portrait_mode_label", None)
        if label is not None:
            label.configure(
                text=(
                    "Power saving • static portrait • text + voice"
                    if self._portrait_power_saving
                    else "Live portrait • lightweight CPU animation"
                )
            )

    def toggle_portrait_power_saving(self) -> None:
        self._portrait_power_saving = not self._portrait_power_saving
        self.db.set_setting(
            "owner_portrait_power_saving",
            "1" if self._portrait_power_saving else "0",
        )
        scheduled = self._avatar_after_id
        self._avatar_after_id = None
        if scheduled is not None:
            try:
                self.root.after_cancel(scheduled)
            except tk.TclError:
                pass
        self._update_power_saving_label()
        if self._portrait_power_saving:
            self._draw_static_approved_portrait()
            self.owner_notice.set(
                "Power saving is on. Portrait animation stopped; text and voice remain available."
            )
        else:
            self.owner_notice.set("Portrait animation resumed.")
            self._animate_avatar()

    def _draw_static_approved_portrait(self) -> None:
        pose = AvatarPose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "idle", "none")
        try:
            if hasattr(self, "portrait_canvas") and self._owner_portrait_renderer is not None:
                frame = self._owner_portrait_renderer.render(pose)
                self._owner_portrait_photo = ImageTk.PhotoImage(frame, master=self.portrait_canvas)
                self.portrait_canvas.delete("all")
                self.portrait_canvas.create_image(0, 0, anchor="nw", image=self._owner_portrait_photo)
            if self.corner and self._portrait_renderer is not None:
                frame = self._portrait_renderer.render(pose)
                self._corner_portrait_photo = ImageTk.PhotoImage(frame, master=self.canvas)
                self.canvas.delete("all")
                self.canvas.create_image(0, 0, anchor="nw", image=self._corner_portrait_photo)
        except (AttributeError, tk.TclError):
            pass

    def _animate_avatar(self):
        self._avatar_after_id = None
        if not self.corner:
            return
        if self._portrait_power_saving:
            self._draw_static_approved_portrait()
            return
        try:
            main_visible = bool(self.root.winfo_viewable())
            corner_visible = bool(self.corner.winfo_viewable())
        except (AttributeError, tk.TclError):
            main_visible = corner_visible = True
        if not main_visible and not corner_visible:
            self._avatar_after_id = self.root.after(
                HIDDEN_AVATAR_POLL_INTERVAL_MS, self._animate_avatar
            )
            return
        try:
            pose = self._ensure_avatar_motion().pose_at()
            if main_visible:
                main_frame = self._owner_portrait_renderer.render(pose)
                self._owner_portrait_photo = ImageTk.PhotoImage(
                    main_frame, master=self.portrait_canvas
                )
                self.portrait_canvas.delete("all")
                self.portrait_canvas.create_image(
                    0, 0, anchor="nw", image=self._owner_portrait_photo
                )
                self.portrait_canvas.create_rectangle(
                    2,
                    2,
                    OWNER_PORTRAIT_SIZE[0] - 3,
                    OWNER_PORTRAIT_SIZE[1] - 3,
                    outline=PALETTE["orange"] if self.speaking else PALETTE["cyan"],
                    width=2,
                )
            if corner_visible:
                corner_frame = self._portrait_renderer.render(pose)
                self._corner_portrait_photo = ImageTk.PhotoImage(
                    corner_frame, master=self.canvas
                )
                self.canvas.delete("all")
                self.canvas.create_image(
                    0, 0, anchor="nw", image=self._corner_portrait_photo
                )
                self.canvas.create_rectangle(
                    2,
                    2,
                    SARAH_PORTRAIT_DISPLAY_SIZE[0] - 3,
                    SARAH_PORTRAIT_DISPLAY_SIZE[1] - 3,
                    outline=PALETTE["orange"] if self.speaking else PALETTE["cyan"],
                    width=2,
                )
        except Exception:
            # Keep the last approved frame. Never replace it with shapes.
            if hasattr(self, "owner_notice"):
                self.owner_notice.set("Sarah's live portrait paused; no substitute image was shown.")
        self._avatar_after_id = self.root.after(
            LIVE_AVATAR_FRAME_INTERVAL_MS, self._animate_avatar
        )

    def _start_tray(self):
        if not pystray:
            return
        if self._portrait_base_image is None:
            raise RuntimeError("The approved Sarah portrait is unavailable for the tray icon")
        icon = self._portrait_base_image.copy().resize((64, 64), Image.Resampling.LANCZOS)
        self.tray = pystray.Icon(
            "SarahMorgan",
            icon,
            "Sarah Morgan",
            pystray.Menu(
                pystray.MenuItem("Show Sarah", lambda: self.root.after(0, self.show)),
                pystray.MenuItem("Quit", lambda: self.root.after(0, self.quit)),
            ),
        )
        threading.Thread(target=self.tray.run, daemon=True).start()

    def show(self):
        self.root.deiconify()
        self.root.lift()
        self.corner.deiconify()
        self._show_page("talk")

    def quit(self):
        if self._calendar_after_id is not None:
            try:
                self.root.after_cancel(self._calendar_after_id)
            except tk.TclError:
                pass
            self._calendar_after_id = None
        if self._gmail_monitor_after_id is not None:
            try:
                self.root.after_cancel(self._gmail_monitor_after_id)
            except tk.TclError:
                pass
            self._gmail_monitor_after_id = None
        if self._avatar_after_id is not None:
            try:
                self.root.after_cancel(self._avatar_after_id)
            except tk.TclError:
                pass
            self._avatar_after_id = None
        pairing = self._pairing_responder
        self._pairing_responder = None
        if pairing is not None:
            try:
                pairing.stop()
            except Exception:
                pass
        responder = self._discovery_responder
        self._discovery_responder = None
        if responder is not None:
            try:
                responder.stop()
            except Exception:
                pass
        super().quit()


def self_test() -> int:
    contract = owner_surface_contract()
    if contract["vector_portrait_fallback"] is not False:
        raise RuntimeError("Owner surface unexpectedly permits substitute portrait artwork")
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
    required_r3_backends = {
        "PairingInitiator": PairingInitiator,
        "PairingResponder": PairingResponder,
        "SarahDiscoveryResponder": SarahDiscoveryResponder,
        "SarahLocalDiscovery": SarahLocalDiscovery,
        "SarahPairingResponderServer": SarahPairingResponderServer,
        "PairingCredentialVault": PairingCredentialVault,
        "SarahSecureSyncService": SarahSecureSyncService,
        "import_reviewed_android_preview": import_reviewed_android_preview,
        "pull_android_preview": pull_android_preview,
        "SarahWallet": SarahWallet,
        "SarahCalendarStore": SarahCalendarStore,
    }
    if event_gmail_available():
        required_r3_backends.update({
            "GmailReadOnlyOAuth": GmailReadOnlyOAuth,
            "GmailTokenVault": GmailTokenVault,
            "inspect_desktop_oauth_client": inspect_desktop_oauth_client,
            "resolve_desktop_oauth_client_path": resolve_desktop_oauth_client_path,
        })
    missing_backends = sorted(
        name for name, value in required_r3_backends.items() if value is None
    )
    if missing_backends:
        raise RuntimeError(
            "Packaged Sarah is missing required R3 backends: " + ", ".join(missing_backends)
        )
    if event_gmail_available():
        try:
            import google_auth_oauthlib.flow  # noqa: F401
            import googleapiclient.discovery  # noqa: F401
        except ImportError as error:
            raise RuntimeError("Packaged Sarah is missing Google read-only OAuth support") from error
    bundled_path = bundled_event_config_path()
    if bundled_path.is_file():
        raw_bundled = json.loads(bundled_path.read_text(encoding="utf-8"))
        if not isinstance(raw_bundled, dict):
            raise RuntimeError("Bundled event configuration is not an object")
        forbidden = sorted(
            str(key) for key in raw_bundled
            if any(marker in str(key) for marker in ("TOKEN", "API_KEY", "PASSWORD", "SECRET"))
            and str(key) != "SARAH_MODEL_BACKEND_TOKEN"
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
        if not bundled.get("SARAH_MODEL_BACKEND_TOKEN"):
            raise RuntimeError("Bundled event app-to-Worker bearer is absent")
        if event_gmail_available():
            packaged_gmail_path = resolve_desktop_oauth_client_path()
            if packaged_gmail_path is None:
                raise RuntimeError("Event owner build is missing its public Desktop Gmail identity")
            inspect_desktop_oauth_client(packaged_gmail_path)
    with tempfile.TemporaryDirectory(prefix="sarah-event-ready-", ignore_cleanup_errors=True) as folder:
        self_test_root = Path(folder)
        database = SarahDatabase(self_test_root)
        owner_id = database.ensure_profile(
            "Packaged self-test owner", 30, memory_consent=True, age_known=True
        )
        database.set_setting("active_person_id", owner_id)
        wallet = SarahWallet(database)
        loyalty = wallet.add_loyalty(
            program_name="Packaged Self-Test Rewards",
            member_identifier="SELF-TEST-ONLY",
            official_url="https://example.org/rewards",
            person_id=owner_id,
        )
        wallet_rows = wallet.list_records(person_id=owner_id)
        if len(wallet_rows) != 1 or wallet_rows[0].get("record_id") != loyalty.get("record_id"):
            raise RuntimeError("Packaged encrypted wallet round-trip failed")

        gmail_client_path = self_test_root / "gmail-self-test-client.json"
        gmail_client_path.write_text(
            json.dumps({
                "installed": {
                    "client_id": "123.apps.googleusercontent.com",
                    "project_id": "sarah-packaged-self-test",
                    "auth_uri": "https://accounts.google.com/o/oauth2/auth",
                    "token_uri": "https://oauth2.googleapis.com/token",
                    "client_secret": "packaged-self-test-only",
                    "redirect_uris": ["http://127.0.0.1"],
                }
            }),
            encoding="utf-8",
        )
        gmail_client = inspect_desktop_oauth_client(gmail_client_path)
        gmail_vault = GmailTokenVault(self_test_root / "gmail-state")
        gmail_vault.save(
            gmail_client,
            "self-test@example.com",
            {
                "token": "self-test-access",
                "refresh_token": "self-test-refresh",
                "token_uri": "https://oauth2.googleapis.com/token",
                "client_id": gmail_client.client_id,
                "client_secret": "packaged-self-test-only",
                "scopes": [GMAIL_READONLY_SCOPE],
            },
        )
        if gmail_vault.load(gmail_client).get("account_email") != "self-test@example.com":
            raise RuntimeError("Packaged Gmail read-only vault round-trip failed")

        calendar = SarahCalendarStore(database)
        proposals = calendar.ingest_email_candidates(
            [{
                "message_id": "self-test-message",
                "thread_id": "self-test-thread",
                "internal_date_ms": "1",
                "from": "self-test@example.com",
                "subject": "Self-test train 2099-01-02 10:00",
                "date": "",
                "source": "gmail.readonly",
            }],
            account_email="self-test@example.com",
            person_id=owner_id,
        )
        event = calendar.decide(
            proposals[0]["proposal_id"],
            remember=True,
            owner_action="Packaged self-test owner confirmation",
            title="Packaged self-test train",
            start_local="2099-01-02 10:00",
            kind="train",
            person_id=owner_id,
        )
        reminder = calendar.add_reminder(
            event["event_id"],
            lead_minutes=60,
            owner_action="Packaged self-test reminder",
            person_id=owner_id,
        )
        if not calendar.due_reminders(
            at_ms=reminder["notify_at_ms"], person_id=owner_id
        ):
            raise RuntimeError("Packaged owner calendar reminder round-trip failed")

        fixed_now = int(time.time())
        initiator = PairingInitiator(
            instance_id="self-test-phone",
            device_name="Sarah self-test phone",
            device_type="android-phone",
            clock=lambda: fixed_now,
        )
        responder = PairingResponder(
            instance_id="self-test-windows",
            device_name="Sarah self-test Windows",
            device_type="windows",
            clock=lambda: fixed_now,
        )
        response, responder_session = responder.respond(initiator.offer_message())
        initiator_session = initiator.accept_response(response)
        initiator_confirmation = initiator_session.local_confirmation(
            owner_confirmed_matching_code=True
        )
        responder_confirmation = responder_session.local_confirmation(
            owner_confirmed_matching_code=True
        )
        initiator_session.accept_peer_confirmation(responder_confirmation)
        responder_session.accept_peer_confirmation(initiator_confirmation)
        initiator_credential = initiator_session.finalize()
        responder_credential = responder_session.finalize()
        if initiator_credential.token != responder_credential.token:
            raise RuntimeError("Packaged two-device pairing key agreement failed")
        credential_vault = PairingCredentialVault(self_test_root / "pairing-state")
        credential_vault.save(responder_credential)
        if credential_vault.token_for(responder_credential.peer_instance_id) != responder_credential.token:
            raise RuntimeError("Packaged pairing credential vault round-trip failed")
        SarahSecureSyncService(database, credential_vault)

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
        del (
            state, server, credential_vault, responder_credential,
            initiator_credential, responder_session, initiator_session,
            calendar, gmail_vault, gmail_client, wallet, database,
        )
        gc.collect()
        if sys.platform.startswith("win"):
            time.sleep(0.15)
    print("SARAH_EVENT_READY_SELF_TEST_OK")
    return 0


if __name__ == "__main__":
    if "--self-test" in sys.argv:
        raise SystemExit(self_test())
    SarahEventReadyApp().run()
