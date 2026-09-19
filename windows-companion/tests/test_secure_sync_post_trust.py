import json
import queue
import socket
import sqlite3
import threading
import time

import pytest

from sarah_core import sync_decrypt, sync_encrypt, sync_signature
from sarah_device_pairing import (
    PairingCredential,
    DiscoveredSarahDevice,
    PairingError,
    PairingInitiator,
    SarahLocalDiscovery,
    SarahPairingResponderServer,
    read_json_frame,
    read_pairing_frame,
    write_json_frame,
    write_pairing_frame,
    begin_pairing_initiator,
)
from sarah_secure_sync import (
    PairingCredentialVault,
    SECURE_SYNC_SCHEMA,
    SarahSecureSyncService,
    import_reviewed_android_preview,
    pull_android_preview,
)


class FakeDatabase:
    def __init__(self, root):
        self.root = root
        self.path = root / "trust.db"
        with self.connect() as db:
            db.execute("CREATE TABLE trusted_devices(device_id TEXT PRIMARY KEY,revoked INTEGER NOT NULL)")

    def connect(self):
        return sqlite3.connect(self.path)

    def export_sync(self, include_photos=True):
        assert include_photos is False
        return {
            "schema": "sarah-sync-v1",
            "device_id": "windows-device",
            "created_at": 123,
            "profile": {"name": "Robert", "interests": "travel", "oauth_token": "NO"},
            "messages": [{"event_id": "m1", "role": "user", "content": "New Zealand"}],
            "memories": [{"memory_id": "p1", "category": "preference", "summary": "Power Rangers"}],
            "trips": [{"trip_id": "t1", "title": "New Zealand"}],
            "wishes": [],
            "mind_events": [{"private_mind": "must not cross"}],
            "discoveries": [{"url": "https://example.test"}],
            "photos": [{"jpeg_base64": "must not cross"}],
            "gmail_refresh_token": "must not cross",
        }


def _pair(server, pending, completed):
    phone = PairingInitiator(
        instance_id="android-owner-device",
        device_name="Samsung test phone",
        device_type="android-phone",
    )
    connection = socket.create_connection(("127.0.0.1", server.port), timeout=2)
    try:
        write_pairing_frame(connection, phone.offer_message())
        phone_session = phone.accept_response(read_pairing_frame(connection))
        windows_pending = pending.get(timeout=2)
        windows_pending.approve(expected_sas_code=phone_session.sas_code)
        write_pairing_frame(connection, phone_session.local_confirmation(owner_confirmed_matching_code=True))
        phone_session.accept_peer_confirmation(read_pairing_frame(connection))
        phone_credential = phone_session.finalize()
        windows_credential = completed.get(timeout=2)
        assert phone_credential.token == windows_credential.token
        return phone_credential, windows_credential
    finally:
        connection.close()


def test_finalized_pairing_enables_encrypted_preview_but_excludes_secrets_and_photos(tmp_path):
    db = FakeDatabase(tmp_path)
    vault = PairingCredentialVault(tmp_path)
    pending, completed, errors = queue.Queue(), queue.Queue(), queue.Queue()
    discovery = SarahLocalDiscovery(device_name="Sarah Windows")
    service = SarahSecureSyncService(db, vault)
    server = SarahPairingResponderServer(
        discovery, bind_host="127.0.0.1", on_pending=pending.put,
        on_complete=completed.put, on_secure_sync=service, on_error=errors.put,
    )
    server.start()
    try:
        phone_credential, windows_credential = _pair(server, pending, completed)
        vault.save(windows_credential)
        with db.connect() as connection_db:
            connection_db.execute(
                "INSERT INTO trusted_devices VALUES(?,0)", (windows_credential.peer_instance_id,)
            )
        request_id = "owner-reviewed-request-1"
        inside = json.dumps({
            "kind": "preview_request", "device_id": "android-owner-device",
            "request_id": request_id,
        })
        encrypted = sync_encrypt(phone_credential.token, inside)
        request = {
            "schema": SECURE_SYNC_SCHEMA, "kind": "preview_request",
            "device_id": "android-owner-device", "request_id": request_id,
            "payload": encrypted,
            "signature": sync_signature(phone_credential.token, encrypted),
        }
        connection = socket.create_connection(("127.0.0.1", server.port), timeout=2)
        try:
            write_json_frame(connection, request)
            response = read_json_frame(connection)
        finally:
            connection.close()
        assert response["kind"] == "preview_response"
        assert response["request_id"] == request_id
        assert response["signature"] == sync_signature(phone_credential.token, response["payload"])
        plain = json.loads(sync_decrypt(phone_credential.token, response["payload"]))
        assert plain["owner_import_required"] is True
        assert plain["transfer_direction"] == "WINDOWS_TO_ANDROID_PULL_ONLY"
        assert plain["counts"] == {"messages": 1, "memories": 1, "trips": 1, "wishes": 0}
        payload = plain["payload"]
        assert payload["profile"] == {"name": "Robert", "interests": "travel"}
        assert payload["photos"] == []
        assert payload["mind_events"] == []
        assert payload["discoveries"] == []
        serialized = json.dumps(payload)
        assert '"oauth_token": "NO"' not in serialized
        assert '"gmail_refresh_token"' not in serialized
        assert "must not cross" not in serialized
        assert errors.empty()
    finally:
        server.stop()


def test_unpaired_or_revoked_device_cannot_request_preview(tmp_path):
    db = FakeDatabase(tmp_path)
    vault = PairingCredentialVault(tmp_path)
    service = SarahSecureSyncService(db, vault)
    request = {
        "schema": SECURE_SYNC_SCHEMA, "kind": "preview_request",
        "device_id": "not-paired", "request_id": "request",
        "payload": "bad", "signature": "bad",
    }
    class Sink:
        def sendall(self, _value):
            raise AssertionError("no response must be sent")
    with pytest.raises(PairingError, match="no finalized"):
        service(Sink(), request, "127.0.0.1")


def test_reverse_or_push_operation_fails_closed_in_this_bounded_release(tmp_path):
    db = FakeDatabase(tmp_path)
    vault = PairingCredentialVault(tmp_path)
    service = SarahSecureSyncService(db, vault)
    request = {
        "schema": SECURE_SYNC_SCHEMA, "kind": "push_request", "device_id": "phone",
        "request_id": "reverse", "payload": "never-read", "signature": "never-read",
    }
    class Sink:
        def sendall(self, _value):
            raise AssertionError("reverse transfer must not answer")
    with pytest.raises(PairingError, match="wrong protocol or operation"):
        service(Sink(), request, "127.0.0.1")


def test_secure_sync_request_replay_is_rejected(tmp_path):
    db = FakeDatabase(tmp_path)
    vault = PairingCredentialVault(tmp_path)
    token = "A" * 43
    vault.save(PairingCredential(
        request_id="pair", peer_instance_id="phone", peer_device_name="Phone",
        peer_device_type="android-phone", token=token, established_at=1,
    ))
    with db.connect() as connection_db:
        connection_db.execute("INSERT INTO trusted_devices VALUES('phone',0)")
    inside = json.dumps({"kind": "preview_request", "device_id": "phone", "request_id": "same"})
    encrypted = sync_encrypt(token, inside)
    request = {
        "schema": SECURE_SYNC_SCHEMA, "kind": "preview_request", "device_id": "phone",
        "request_id": "same", "payload": encrypted, "signature": sync_signature(token, encrypted),
    }
    class Sink:
        def sendall(self, _value):
            pass
    service = SarahSecureSyncService(db, vault)
    service(Sink(), request, "127.0.0.1")
    with pytest.raises(PairingError, match="replay"):
        service(Sink(), request, "127.0.0.1")


def test_windows_initiator_pairs_with_established_responder_before_any_sync():
    pending_rows, completed, errors = queue.Queue(), queue.Queue(), queue.Queue()
    discovery = SarahLocalDiscovery(device_name="Established Sarah responder", pairing_port=0)
    server = SarahPairingResponderServer(
        discovery, bind_host="127.0.0.1", approval_timeout_seconds=2,
        on_pending=pending_rows.put, on_complete=completed.put, on_error=errors.put,
    )
    server.start()
    peer = DiscoveredSarahDevice(
        host="127.0.0.1", instance_id=discovery.instance_id,
        device_name=discovery.device_name, device_type=discovery.device_type,
        pairing_port=server.port, expires_at=int(time.time()) + 15,
    )
    try:
        windows_pending = begin_pairing_initiator(
            peer, local_instance_id="stable-windows-device",
            local_device_name="Sarah on Windows",
        )
        android_pending = pending_rows.get(timeout=2)
        assert android_pending.sas_code == windows_pending.sas_code
        assert completed.empty()
        android_pending.approve(expected_sas_code=windows_pending.sas_code)
        windows_credential = windows_pending.complete(owner_confirmed_matching_code=True)
        android_credential = completed.get(timeout=2)
        assert windows_credential.token == android_credential.token
        assert windows_credential.peer_device_type == discovery.device_type
        assert android_credential.peer_instance_id == "stable-windows-device"
        assert errors.empty()
    finally:
        server.stop()


def _android_preview_server(token, payload, ready, result):
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.bind(("127.0.0.1", 0)); listener.listen(1)
    result["port"] = listener.getsockname()[1]; ready.set()
    try:
        connection, _address = listener.accept()
        with connection:
            request = read_json_frame(connection)
            assert request["signature"] == sync_signature(token, request["payload"])
            inside = json.loads(sync_decrypt(token, request["payload"]))
            assert inside["kind"] == "preview_request"
            plain = {
                "kind": "preview_response", "request_id": request["request_id"],
                "transfer_id": "android-transfer-1", "source_name": "Robert",
                "counts": {"messages": 1, "memories": 1, "trips": 1, "wishes": 0},
                "payload": payload, "owner_import_required": True,
                "merge_policy": "APPEND_NEW_KEEP_EXISTING_RECORD_CONFLICTS",
                "transfer_direction": "ANDROID_TO_WINDOWS_PULL_ONLY",
            }
            encrypted = sync_encrypt(token, json.dumps(plain, separators=(",", ":")))
            write_json_frame(connection, {
                "schema": SECURE_SYNC_SCHEMA, "kind": "preview_response",
                "device_id": request["device_id"], "request_id": request["request_id"],
                "payload": encrypted, "signature": sync_signature(token, encrypted),
            })
    except Exception as error:
        result["error"] = error
    finally:
        listener.close()


def test_new_windows_previews_then_explicitly_imports_established_android(tmp_path):
    token = "B" * 43
    payload = {
        "schema": "sarah-sync-v1", "device_id": "android-established", "created_at": 1,
        "profile": {"name": "Robert", "interests": "Power Rangers"},
        "messages": [{"event_id": "m1", "content": "New Zealand"}],
        "memories": [{"memory_id": "p1", "summary": "Power Rangers"}],
        "trips": [{"trip_id": "t1", "title": "New Zealand"}], "wishes": [],
        "photos": [], "mind_events": [], "discoveries": [],
        "transfer_boundary": {"included": ["profile", "messages", "memories", "trips", "wishes"],
                              "excluded": ["gmail_oauth_tokens", "provider_tokens", "model_tokens", "voice_tokens", "raw_private_photos", "private_mind", "discoveries", "other_people"]},
    }
    ready, result = threading.Event(), {}
    thread = threading.Thread(target=_android_preview_server, args=(token, payload, ready, result))
    thread.start(); assert ready.wait(2)
    preview = pull_android_preview(
        host="127.0.0.1", port=result["port"], device_id="stable-windows", token=token
    )
    thread.join(timeout=2); assert "error" not in result
    assert preview.source_name == "Robert"
    assert preview.counts["messages"] == 1

    class ImportDatabase:
        def __init__(self, root): self.root=root; self.calls=[]
        def import_sync(self, incoming, *, confirm_owner_change=False):
            self.calls.append((incoming, confirm_owner_change))
            return {"messages": 1, "memories": 1, "trips": 1, "wishes": 0,
                    "mind_events": 0, "discoveries": 0, "photos": 0}
    database = ImportDatabase(tmp_path)
    assert database.calls == []  # Fetching/showing a preview imports nothing.
    counts = import_reviewed_android_preview(database, preview)
    assert counts["messages"] == 1
    assert database.calls[0][1] is True
    receipts = [json.loads(line) for line in (tmp_path / "secure_sync_import_history.jsonl").read_text().splitlines()]
    assert [row["event"] for row in receipts] == [
        "OWNER_APPROVED_SECURE_SYNC_IMPORT", "SECURE_SYNC_IMPORT_COMPLETED"
    ]
    assert all("New Zealand" not in json.dumps(row) for row in receipts)
