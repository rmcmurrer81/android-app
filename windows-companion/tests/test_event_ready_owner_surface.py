from __future__ import annotations

import inspect
from urllib.parse import parse_qs, urlsplit

import pytest

from sarah_event_ready import (
    OWNER_PAGES,
    SARAH_WINDOWS_OWNER_VERSION,
    SarahEventReadyApp,
    mind_status_text,
    openstreetmap_handoff_url,
    owner_surface_contract,
    voice_status_text,
    wikimedia_media_handoff_url,
)
from sarah_windows import SarahApp, portrait_packaged_self_test


def test_owner_surface_is_one_current_conversation_primary_shell():
    contract = owner_surface_contract()

    assert issubclass(SarahEventReadyApp, SarahApp)
    assert contract["version"] == SARAH_WINDOWS_OWNER_VERSION
    assert contract["conversation_primary"] is True
    assert contract["technical_tabs_hidden"] is True
    assert contract["pages"] == OWNER_PAGES
    assert OWNER_PAGES[0] == "Talk with Sarah"
    assert "Travel Workbench" in OWNER_PAGES
    assert set(("Map & Discover", "Trips", "Photos", "Connections", "Activity")) <= set(OWNER_PAGES)
    assert contract["owner_wallet"] == "profile_isolated_dpapi_wrapped_encrypted_records"
    assert contract["ticket_images"] == "sanitized_png_decrypted_in_memory_only"


def test_owner_surface_requires_exact_portrait_and_never_calls_vector_fallback():
    contract = owner_surface_contract()
    report = portrait_packaged_self_test()
    animation_source = inspect.getsource(SarahEventReadyApp._animate_avatar)
    corner_source = inspect.getsource(SarahEventReadyApp._start_corner)

    assert report["valid"] is True
    assert contract["portrait_required"] is True
    assert contract["vector_portrait_fallback"] is False
    assert contract["portrait_motion"] == (
        "blink",
        "head",
        "eyes",
        "audio_bound_mouth",
    )
    assert "_draw_vector_avatar" not in animation_source
    assert "_draw_vector_avatar" not in corner_source
    assert "PortraitFrameRenderer" in inspect.getsource(SarahEventReadyApp._load_required_portrait)


def test_power_saving_stops_animation_loop_but_keeps_text_and_voice_routes():
    contract = owner_surface_contract()
    toggle = inspect.getsource(SarahEventReadyApp.toggle_portrait_power_saving)
    animate = inspect.getsource(SarahEventReadyApp._animate_avatar)
    static = inspect.getsource(SarahEventReadyApp._draw_static_approved_portrait)

    assert contract["power_saving"] == "stops_portrait_render_loop_text_and_voice_remain"
    assert "after_cancel" in toggle
    assert "text and voice remain available" in toggle
    assert "if self._portrait_power_saving" in animate
    assert "return" in animate.split("if self._portrait_power_saving", 1)[1]
    assert "AvatarPose" in static
    assert "self.voice" not in toggle
    assert "self.model" not in toggle


def test_map_handoff_retains_exact_owner_places_without_api_credentials():
    url = openstreetmap_handoff_url("Newark, NJ", "Wellington, New Zealand")
    parsed = urlsplit(url)
    query = parse_qs(parsed.query)

    assert parsed.scheme == "https"
    assert parsed.netloc == "www.openstreetmap.org"
    assert parsed.path == "/directions"
    assert query["from"] == ["Newark, NJ"]
    assert query["to"] == ["Wellington, New Zealand"]
    assert "key" not in query and "token" not in query

    search = urlsplit(openstreetmap_handoff_url("", "Auckland Museum"))
    assert search.path == "/search"
    assert parse_qs(search.query)["query"] == ["Auckland Museum"]

    with pytest.raises(ValueError):
        openstreetmap_handoff_url("", "")


def test_public_media_handoff_is_source_and_license_bearing_commons_search():
    parsed = urlsplit(wikimedia_media_handoff_url("Auckland Power Rangers filming"))
    query = parse_qs(parsed.query)

    assert parsed.scheme == "https"
    assert parsed.netloc == "commons.wikimedia.org"
    assert query["title"] == ["Special:MediaSearch"]
    assert query["type"] == ["image"]
    assert query["search"] == ["Auckland Power Rangers filming"]
    with pytest.raises(ValueError):
        wikimedia_media_handoff_url("  ")


def test_a_durable_mentioned_trip_can_surface_in_the_media_workbench():
    submit_source = inspect.getsource(SarahEventReadyApp._submit_text)
    surface_source = inspect.getsource(SarahEventReadyApp._surface_latest_trip_in_workbench)

    assert "super()._submit_text(text)" in submit_source
    assert "_surface_latest_trip_in_workbench" in submit_source
    assert 'list_rows("trips", limit=1)' in surface_source
    assert "destination" in surface_source


def test_windows_workbench_connects_the_encrypted_owner_wallet_without_a_buy_action():
    workbench = inspect.getsource(SarahEventReadyApp._build_workbench)
    loyalty = inspect.getsource(SarahEventReadyApp._add_loyalty_wallet_record)
    ticket = inspect.getsource(SarahEventReadyApp._add_ticket_wallet_record)
    review = inspect.getsource(SarahEventReadyApp._review_owner_wallet)

    assert "Loyalty cards, tickets & passes" in workbench
    assert "Add loyalty card" in workbench
    assert "Add ticket / QR pass" in workbench
    assert "self.wallet.add_loyalty" in loyalty
    assert "self.wallet.add_ticket_pass" in ticket
    assert "self.wallet.get_image_bytes" in review
    assert "io.BytesIO" in review
    assert "self.wallet.remove_record" in review
    assert "Buy" not in workbench + loyalty + ticket + review


def test_status_chips_distinguish_setup_from_observed_routes():
    assert mind_status_text("", True) == "Mind: online set up"
    assert mind_status_text("ONLINE_WORKERS_AI", False) == "Mind: online used"
    assert mind_status_text("ONLINE_FAILED_FELL_BACK_OFFLINE", True) == "Mind: offline used"
    assert mind_status_text("OFFLINE_LOCAL", False) == "Mind: offline used"

    assert voice_status_text({}, True) == "Voice: ElevenLabs set up"
    assert voice_status_text({"actual_route": "ELEVENLABS"}, False) == "Voice: ElevenLabs used"
    assert voice_status_text({"actual_route": "WINDOWS_SYSTEM_SPEECH"}, True) == "Voice: offline used"
    assert voice_status_text({"actual_route": "TEXT_ONLY"}, True) == "Voice: text only"


def test_elevenlabs_owner_test_has_no_substitute_voice_path():
    source = inspect.getsource(SarahEventReadyApp._elevenlabs_test_worker)

    assert "self.voice.synthesize" in source
    assert 'actual = "ELEVENLABS"' in source
    assert "_speak_windows_fallback" not in source
    assert "WINDOWS_SYSTEM_SPEECH" not in source


def test_first_run_is_one_in_shell_card_and_does_not_assume_owner_identity():
    source = inspect.getsource(SarahEventReadyApp._maybe_onboard)
    finish = inspect.getsource(SarahEventReadyApp._finish_onboarding)

    assert "simpledialog" not in source
    assert "place(" in source
    assert "Only your name is required" in source
    assert "Robert" not in source + finish
    assert "age_known=age is not None" in finish
    assert "_offer_gmail_after_profile" in finish


def test_gmail_monitoring_is_optional_bounded_and_readonly():
    offer = inspect.getsource(SarahEventReadyApp._offer_gmail_after_profile)
    change = inspect.getsource(SarahEventReadyApp._owner_changed_gmail_monitor)
    tick = inspect.getsource(SarahEventReadyApp._gmail_monitor_tick)

    assert "read-only access" in offer
    assert "cannot send, delete, mark read, or change" in offer
    assert '_gmail_setting_key("gmail_monitor_enabled")' in change
    assert "gmail_monitor_backoff_seconds" in tick
    assert "6 * 60 * 60" in tick
    assert "_start_gmail_review(automatic=True, person_id=person_id)" in tick


def test_gmail_connect_and_async_results_are_profile_bound_and_single_flight():
    connect = inspect.getsource(SarahEventReadyApp.connect_gmail)
    review = inspect.getsource(SarahEventReadyApp._gmail_review_worker)
    tasks = inspect.getsource(SarahEventReadyApp._poll_tasks)

    assert "_gmail_connect_in_flight" in connect
    assert "person_id = self._gmail_person_id()" in connect
    assert '"person_id": person_id' in review
    assert '"account_email": receipt.account_email' in review
    assert "person_id != self._gmail_person_id()" in tasks
    assert "account_email != safe_text(self._gmail_account).lower()" in tasks


def test_first_run_offers_device_discovery_before_name_and_decline_keeps_local_setup():
    onboarding = inspect.getsource(SarahEventReadyApp._maybe_onboard)
    init = inspect.getsource(SarahEventReadyApp.__init__)
    tasks = inspect.getsource(SarahEventReadyApp._poll_tasks)

    assert "Find my other Sarah device" in onboarding
    assert "before you enter a name" in onboarding
    assert "SarahPairingResponderServer" in init
    assert "pairing_pending" in tasks
    assert "Do you want to prepare a secure device connection?" in tasks
    assert "pending.reject()" in tasks
    assert "continue this computer's local setup" in tasks
    assert "No profile, Gmail, model, provider, or travel data has been shared" in tasks


def test_normal_connection_surfaces_use_owner_language_and_no_provider_key_prompt():
    connect = inspect.getsource(SarahEventReadyApp.connect_private_access)
    gmail = inspect.getsource(SarahEventReadyApp.connect_gmail)
    devices = inspect.getsource(SarahEventReadyApp._offer_device_verification)

    assert "private access code" in connect
    assert "provider key" in connect
    assert "askstring" in connect
    assert "backend address" not in connect.lower()
    assert "project's Google sign-in identity" in gmail
    assert "askopenfilename" not in gmail
    assert "No browser opened and no mail was accessed" in gmail
    assert "Is this your device?" in devices
    assert "same six-digit code" in devices
    assert "both devices" in devices


def test_returning_owner_can_import_android_before_windows_asks_for_new_name():
    onboard = inspect.getsource(SarahEventReadyApp._maybe_onboard)
    reveal = inspect.getsource(SarahEventReadyApp._show_fresh_profile_form)
    tasks = inspect.getsource(SarahEventReadyApp._poll_tasks)
    assert '"Find my other Sarah device"' in onboard
    assert '"This is my first Sarah device"' in onboard
    assert 'form.pack(fill="x")' not in onboard
    assert "form.pack(**options)" in reveal
    assert 'kind == "reverse_sync_imported"' in tasks
    reverse_section = tasks.split('kind == "reverse_sync_imported"', 1)[1]
    assert "self._dismiss_onboarding()" in reverse_section


def test_owner_surface_remains_lightweight_and_hides_route_jargon_from_chat():
    contract = owner_surface_contract()
    append_source = inspect.getsource(SarahEventReadyApp._append)
    ui_source = inspect.getsource(SarahEventReadyApp._build_ui)

    assert contract["gpu_required"] is False
    assert contract["provider_secrets_bundled"] is False
    assert "route_label" not in append_source
    assert "Source:" not in append_source
    assert 'style.layout("Owner.TNotebook.Tab", [])' in ui_source
