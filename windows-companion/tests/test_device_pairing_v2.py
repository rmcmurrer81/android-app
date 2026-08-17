from __future__ import annotations

import json

import pytest

from sarah_device_pairing import (
    DeviceDiscoveryError,
    PairingError,
    PairingInitiator,
    PairingResponder,
    SarahLocalDiscovery,
)


class Clock:
    def __init__(self, value: int = 1_800_000_000):
        self.value = value

    def __call__(self) -> int:
        return self.value


def _sessions(clock: Clock):
    initiator = PairingInitiator(
        instance_id="phone-session",
        device_name="Robert's Galaxy A17",
        device_type="android-phone",
        clock=clock,
    )
    responder = PairingResponder(
        instance_id="windows-session",
        device_name="Sarah on Robert's laptop",
        device_type="windows",
        clock=clock,
    )
    response, windows_session = responder.respond(initiator.offer_message())
    phone_session = initiator.accept_response(response)
    return phone_session, windows_session


def test_discovery_is_short_lived_untrusted_notice_without_stable_identity_or_token() -> None:
    clock = Clock()
    discovery = SarahLocalDiscovery(
        device_name="Sarah on Windows",
        pairing_port=9443,
        clock=clock,
    )
    payload = discovery.announcement()
    text = payload.decode("utf-8")
    assert "token" not in text.lower()
    assert "owner" not in text.lower()
    assert "profile" not in text.lower()
    assert "device_id" not in text.lower()
    peer = discovery.parse_announcement(payload, "192.168.1.22", now=clock.value)
    assert peer.device_name == "Sarah on Windows"
    assert peer.pairing_port == 9443
    assert peer.expires_at == clock.value + 15


def test_discovery_rejects_public_source_and_missing_two_device_approval() -> None:
    clock = Clock()
    discovery = SarahLocalDiscovery(clock=clock)
    payload = discovery.announcement()
    with pytest.raises(DeviceDiscoveryError, match="local/private"):
        discovery.parse_announcement(payload, "8.8.8.8", now=clock.value)
    parsed = json.loads(payload)
    parsed["approval_required_on_both_devices"] = False
    with pytest.raises(DeviceDiscoveryError, match="two-device"):
        discovery.parse_announcement(
            json.dumps(parsed).encode("utf-8"), "192.168.1.22", now=clock.value
        )


def test_two_device_matching_code_and_confirmation_create_same_credential() -> None:
    clock = Clock()
    phone, windows = _sessions(clock)
    assert phone.sas_code == windows.sas_code
    assert len(phone.sas_code) == 6 and phone.sas_code.isdigit()

    phone_proof = phone.local_confirmation(owner_confirmed_matching_code=True)
    windows_proof = windows.local_confirmation(owner_confirmed_matching_code=True)
    phone.accept_peer_confirmation(windows_proof)
    windows.accept_peer_confirmation(phone_proof)

    phone_credential = phone.finalize()
    windows_credential = windows.finalize()
    assert phone_credential.token == windows_credential.token
    assert phone_credential.peer_device_name == "Sarah on Robert's laptop"
    assert windows_credential.peer_device_name == "Robert's Galaxy A17"
    assert "Robert" not in phone_credential.token


def test_no_credential_before_both_explicit_confirmations() -> None:
    phone, windows = _sessions(Clock())
    with pytest.raises(PairingError, match="Both devices"):
        phone.finalize()
    phone.local_confirmation(owner_confirmed_matching_code=True)
    with pytest.raises(PairingError, match="Both devices"):
        phone.finalize()
    with pytest.raises(PairingError, match="did not confirm"):
        windows.local_confirmation(owner_confirmed_matching_code=False)


def test_tampered_peer_confirmation_is_rejected() -> None:
    phone, windows = _sessions(Clock())
    proof = windows.local_confirmation(owner_confirmed_matching_code=True)
    proof["proof"] = ("A" if not proof["proof"].startswith("A") else "B") + proof["proof"][1:]
    with pytest.raises(PairingError, match="proof failed"):
        phone.accept_peer_confirmation(proof)


def test_pairing_expires_before_confirmation_or_finalization() -> None:
    clock = Clock()
    phone, windows = _sessions(clock)
    clock.value += 121
    with pytest.raises(PairingError, match="expired"):
        phone.local_confirmation(owner_confirmed_matching_code=True)
    with pytest.raises(PairingError, match="expired"):
        windows.finalize()


def test_response_for_another_request_is_rejected() -> None:
    clock = Clock()
    first = PairingInitiator(
        instance_id="first", device_name="First phone", device_type="android", clock=clock
    )
    second = PairingInitiator(
        instance_id="second", device_name="Second phone", device_type="android", clock=clock
    )
    responder = PairingResponder(
        instance_id="desktop", device_name="Sarah desktop", device_type="windows", clock=clock
    )
    response, _session = responder.respond(first.offer_message())
    with pytest.raises(PairingError, match="different request"):
        second.accept_response(response)


def test_pairing_offer_cannot_opt_out_of_two_device_approval() -> None:
    clock = Clock()
    initiator = PairingInitiator(
        instance_id="phone", device_name="Phone", device_type="android", clock=clock
    )
    offer = initiator.offer_message()
    offer["approval_required_on_both_devices"] = False
    responder = PairingResponder(
        instance_id="desktop", device_name="Desktop", device_type="windows", clock=clock
    )
    with pytest.raises(PairingError, match="approval on both"):
        responder.respond(offer)
