import queue
import socket
import struct

import pytest

from sarah_device_pairing import (
    MAX_PAIRING_BYTES,
    PairingError,
    PairingInitiator,
    SarahLocalDiscovery,
    SarahPairingResponderServer,
    read_pairing_frame,
    write_pairing_frame,
)


def _server(*, approval_timeout=2.0):
    pending = queue.Queue()
    completed = queue.Queue()
    errors = queue.Queue()
    discovery = SarahLocalDiscovery(device_name="Sarah on Windows", pairing_port=0)
    server = SarahPairingResponderServer(
        discovery,
        bind_host="127.0.0.1",
        approval_timeout_seconds=approval_timeout,
        on_pending=pending.put,
        on_complete=completed.put,
        on_error=errors.put,
    )
    server.start()
    return server, discovery, pending, completed, errors


def test_android_wire_sequence_requires_both_sas_approvals_before_credential():
    server, discovery, pending_rows, completed, errors = _server()
    connection = socket.create_connection(("127.0.0.1", server.port), timeout=2)
    try:
        assert server.port > 0
        assert discovery.pairing_port == server.port
        phone = PairingInitiator(
            instance_id="android-test-instance",
            device_name="Samsung test phone",
            device_type="android-phone",
        )
        write_pairing_frame(connection, phone.offer_message())
        response = read_pairing_frame(connection)
        phone_session = phone.accept_response(response)
        windows_pending = pending_rows.get(timeout=2)

        assert windows_pending.sas_code == phone_session.sas_code
        assert completed.empty()
        assert "token" not in response
        assert "profile" not in response
        assert "gmail" not in response
        assert "model" not in response
        windows_pending.approve(expected_sas_code=phone_session.sas_code)

        write_pairing_frame(
            connection,
            phone_session.local_confirmation(owner_confirmed_matching_code=True),
        )
        responder_confirmation = read_pairing_frame(connection)
        assert responder_confirmation["role"] == "responder"
        phone_session.accept_peer_confirmation(responder_confirmation)
        phone_credential = phone_session.finalize()
        windows_credential = completed.get(timeout=2)

        assert phone_credential.token == windows_credential.token
        assert phone_credential.request_id == windows_credential.request_id
        assert errors.empty()
    finally:
        connection.close()
        server.stop()
    assert discovery.pairing_port == 0


def test_preapproval_offer_rejects_profile_or_service_credential_fields():
    server, _discovery, pending_rows, completed, errors = _server()
    connection = socket.create_connection(("127.0.0.1", server.port), timeout=2)
    try:
        phone = PairingInitiator(
            instance_id="android-test-instance",
            device_name="Samsung test phone",
            device_type="android-phone",
        )
        malicious = phone.offer_message()
        malicious["SARAH_MODEL_BACKEND_TOKEN"] = "must-never-cross-before-approval"
        write_pairing_frame(connection, malicious)
        connection.settimeout(1)
        with pytest.raises((PairingError, ConnectionError, OSError)):
            read_pairing_frame(connection)
        error = errors.get(timeout=2)
        assert "unexpected or missing fields" in str(error)
        assert pending_rows.empty()
        assert completed.empty()
    finally:
        connection.close()
        server.stop()


def test_oversized_length_is_rejected_before_payload_allocation():
    server, _discovery, pending_rows, completed, errors = _server()
    connection = socket.create_connection(("127.0.0.1", server.port), timeout=2)
    try:
        connection.sendall(struct.pack(">I", MAX_PAIRING_BYTES + 1))
        error = errors.get(timeout=2)
        assert "oversized" in str(error)
        assert pending_rows.empty()
        assert completed.empty()
    finally:
        connection.close()
        server.stop()


def test_windows_timeout_or_rejection_never_sends_responder_confirmation():
    server, _discovery, pending_rows, completed, errors = _server(approval_timeout=0.1)
    connection = socket.create_connection(("127.0.0.1", server.port), timeout=2)
    try:
        phone = PairingInitiator(
            instance_id="android-test-instance",
            device_name="Samsung test phone",
            device_type="android-phone",
        )
        write_pairing_frame(connection, phone.offer_message())
        response = read_pairing_frame(connection)
        phone_session = phone.accept_response(response)
        pending_rows.get(timeout=2)
        connection.settimeout(1)
        write_pairing_frame(
            connection,
            phone_session.local_confirmation(owner_confirmed_matching_code=True),
        )
        with pytest.raises((PairingError, ConnectionError, OSError)):
            read_pairing_frame(connection)
        assert "timed out" in str(errors.get(timeout=2)).lower()
        assert completed.empty()
    finally:
        connection.close()
        server.stop()
