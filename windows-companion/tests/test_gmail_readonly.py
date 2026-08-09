from __future__ import annotations

import json
from pathlib import Path

import pytest

from sarah_gmail import (
    GMAIL_READONLY_SCOPE,
    GmailAuthorizationError,
    GmailConfigurationError,
    GmailReadOnlyOAuth,
    GmailTokenVault,
    inspect_desktop_oauth_client,
    list_travel_message_candidates,
    resolve_desktop_oauth_client_path,
    revoke_google_authorization,
)


def _client(path: Path, *, redirect: str = "http://localhost") -> Path:
    path.write_text(
        json.dumps(
            {
                "installed": {
                    "client_id": "123.apps.googleusercontent.com",
                    "project_id": "sarah-owner-test",
                    "auth_uri": "https://accounts.google.com/o/oauth2/auth",
                    "token_uri": "https://oauth2.googleapis.com/token",
                    "client_secret": "owner-selected-client-secret",
                    "redirect_uris": [redirect],
                }
            }
        ),
        encoding="utf-8",
    )
    return path


def _authorized_info(*, scopes=None, refresh_token="refresh-private"):
    return {
        "token": "access-private",
        "refresh_token": refresh_token,
        "token_uri": "https://oauth2.googleapis.com/token",
        "client_id": "123.apps.googleusercontent.com",
        "client_secret": "owner-selected-client-secret",
        "scopes": scopes or [GMAIL_READONLY_SCOPE],
    }


def test_desktop_client_is_exact_hash_bound_and_loopback_only(tmp_path: Path) -> None:
    path = _client(tmp_path / "client.json")
    client = inspect_desktop_oauth_client(path)
    assert client.path == path.resolve()
    assert len(client.sha256) == 64
    assert len(client.file_sha256) == 64
    assert client.client_id == "123.apps.googleusercontent.com"

    with pytest.raises(GmailConfigurationError, match="loopback"):
        inspect_desktop_oauth_client(
            _client(tmp_path / "remote.json", redirect="https://example.com/oauth")
        )


def test_web_oauth_client_is_rejected(tmp_path: Path) -> None:
    path = tmp_path / "web.json"
    path.write_text(
        json.dumps(
            {
                "web": {
                    "client_id": "123.apps.googleusercontent.com",
                    "client_secret": "not-copied",
                }
            }
        ),
        encoding="utf-8",
    )
    with pytest.raises(GmailConfigurationError, match="Desktop"):
        inspect_desktop_oauth_client(path)


def test_normal_client_resolution_uses_build_bound_file_without_owner_picker(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.delenv("SARAH_GMAIL_DESKTOP_CLIENT_PATH", raising=False)
    bundled = _client(tmp_path / "sarah-gmail-oauth-client.json")
    assert resolve_desktop_oauth_client_path(bundle_root=tmp_path) == bundled.resolve()
    assert resolve_desktop_oauth_client_path(bundle_root=tmp_path / "absent") is None


def test_source_developer_client_path_is_external_and_not_copied(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    external = _client(tmp_path / "external.json")
    monkeypatch.setenv("SARAH_GMAIL_DESKTOP_CLIENT_PATH", str(external))
    resolved = resolve_desktop_oauth_client_path(bundle_root=tmp_path / "ignored")
    assert resolved == external.resolve()
    assert list(tmp_path.glob("sarah-gmail-oauth-client.json")) == []


def test_packaged_resolution_ignores_environment_override(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    external = _client(tmp_path / "external.json")
    bundle = tmp_path / "bundle"
    bundle.mkdir()
    packaged = _client(bundle / "sarah-gmail-oauth-client.json")
    monkeypatch.setenv("SARAH_GMAIL_DESKTOP_CLIENT_PATH", str(external))
    monkeypatch.setattr("sarah_gmail.sys.frozen", True, raising=False)
    assert resolve_desktop_oauth_client_path(bundle_root=bundle) == packaged.resolve()


def test_token_vault_encrypts_account_and_tokens_and_round_trips(tmp_path: Path) -> None:
    client = inspect_desktop_oauth_client(_client(tmp_path / "client.json"))
    vault = GmailTokenVault(tmp_path / "state")
    receipt = vault.save(client, "owner@example.com", _authorized_info())
    assert receipt.connected is True
    assert receipt.account_email == "owner@example.com"
    on_disk = vault.path.read_text(encoding="utf-8")
    for secret in (
        "owner@example.com",
        "access-private",
        "refresh-private",
        "owner-selected-client-secret",
        client.client_id,
    ):
        assert secret not in on_disk
    loaded = vault.load(client)
    assert loaded["account_email"] == "owner@example.com"
    assert loaded["authorized_user_info"]["refresh_token"] == "refresh-private"
    assert loaded["scopes"] == [GMAIL_READONLY_SCOPE]


def test_token_vault_is_profile_isolated(tmp_path: Path) -> None:
    client = inspect_desktop_oauth_client(_client(tmp_path / "client.json"))
    first = GmailTokenVault(tmp_path / "state", "person-one")
    second = GmailTokenVault(tmp_path / "state", "person-two")
    first.save(client, "owner@example.com", _authorized_info())
    assert first.path != second.path
    assert not second.path.exists()


def test_token_vault_rejects_broader_scope_or_missing_refresh_token(tmp_path: Path) -> None:
    client = inspect_desktop_oauth_client(_client(tmp_path / "client.json"))
    vault = GmailTokenVault(tmp_path / "state")
    with pytest.raises(GmailAuthorizationError, match="exactly gmail.readonly"):
        vault.save(
            client,
            "owner@example.com",
            _authorized_info(scopes=[GMAIL_READONLY_SCOPE, "https://mail.google.com/"]),
        )
    with pytest.raises(GmailAuthorizationError, match="refresh token"):
        vault.save(
            client,
            "owner@example.com",
            _authorized_info(refresh_token=""),
        )


def test_token_binding_survives_harmless_json_rebuild_but_rejects_other_client(tmp_path: Path) -> None:
    first_path = _client(tmp_path / "first.json")
    first = inspect_desktop_oauth_client(first_path)
    vault = GmailTokenVault(tmp_path / "state")
    vault.save(first, "owner@example.com", _authorized_info())

    raw = json.loads(first_path.read_text(encoding="utf-8"))
    raw["installed"]["project_id"] = "different-non-authority-label"
    second_path = tmp_path / "second.json"
    second_path.write_text(json.dumps(raw, indent=4), encoding="utf-8")
    second = inspect_desktop_oauth_client(second_path)
    assert first.sha256 == second.sha256
    assert first.file_sha256 != second.file_sha256
    assert vault.load(second)["account_email"] == "owner@example.com"

    raw["installed"]["client_id"] = "other.apps.googleusercontent.com"
    third_path = tmp_path / "third.json"
    third_path.write_text(json.dumps(raw), encoding="utf-8")
    third = inspect_desktop_oauth_client(third_path)
    with pytest.raises(GmailAuthorizationError, match="selected OAuth client file"):
        vault.load(third)


def test_disconnect_removes_only_local_token_and_can_revoke_access(tmp_path: Path) -> None:
    client = inspect_desktop_oauth_client(_client(tmp_path / "client.json"))
    vault = GmailTokenVault(tmp_path / "state")
    vault.save(client, "owner@example.com", _authorized_info())
    seen = []
    result = vault.disconnect(client, revoke=lambda token: seen.append(token) or True)
    assert seen == ["refresh-private"]
    assert result.local_token_removed is True
    assert result.remote_revocation_attempted is True
    assert result.remote_revocation_succeeded is True
    assert not vault.path.exists()
    assert (tmp_path / "client.json").exists()


def test_google_revocation_is_fixed_https_bounded_and_fails_closed() -> None:
    calls = []

    class Response:
        status_code = 200

    def post(url, **kwargs):
        calls.append((url, kwargs))
        return Response()

    assert revoke_google_authorization("refresh-private", post=post) is True
    assert calls[0][0] == "https://oauth2.googleapis.com/revoke"
    assert calls[0][1]["data"] == {"token": "refresh-private"}
    assert calls[0][1]["allow_redirects"] is False
    assert calls[0][1]["timeout"] == 10
    assert revoke_google_authorization("", post=post) is False
    assert revoke_google_authorization("x" * 16_385, post=post) is False


def test_interactive_connect_requests_only_readonly_and_saves_profile(tmp_path: Path) -> None:
    client_path = _client(tmp_path / "client.json")
    vault = GmailTokenVault(tmp_path / "state")
    captured = {}

    class Credentials:
        scopes = [GMAIL_READONLY_SCOPE]
        granted_scopes = [GMAIL_READONLY_SCOPE]

        def to_json(self):
            return json.dumps(_authorized_info())

    class Flow:
        def run_local_server(self, **kwargs):
            captured.update(kwargs)
            return Credentials()

    def flow_factory(path, scopes, **kwargs):
        captured["client_path"] = path
        captured["requested_scopes"] = scopes
        captured.update(kwargs)
        return Flow()

    receipt = GmailReadOnlyOAuth(vault).connect(
        client_path,
        flow_factory=flow_factory,
        profile_reader=lambda _credentials: {"emailAddress": "owner@example.com"},
    )
    assert receipt.connected
    assert captured["requested_scopes"] == [GMAIL_READONLY_SCOPE]
    assert captured["host"] == "127.0.0.1"
    assert captured["port"] == 0
    assert captured["access_type"] == "offline"
    assert captured["prompt"] == "consent"
    assert captured["autogenerate_code_verifier"] is True
    assert captured["timeout_seconds"] == 180


def test_interactive_connect_fails_if_google_reports_broader_scope(tmp_path: Path) -> None:
    client_path = _client(tmp_path / "client.json")

    class Credentials:
        scopes = [GMAIL_READONLY_SCOPE, "https://mail.google.com/"]
        granted_scopes = scopes

        def to_json(self):
            return json.dumps(_authorized_info(scopes=self.scopes))

    class Flow:
        def run_local_server(self, **_kwargs):
            return Credentials()

    with pytest.raises(GmailAuthorizationError, match="broader"):
        GmailReadOnlyOAuth(GmailTokenVault(tmp_path / "state")).connect(
            client_path,
            flow_factory=lambda _path, _scopes, **_kwargs: Flow(),
            profile_reader=lambda _credentials: {"emailAddress": "owner@example.com"},
        )


def test_message_candidates_use_metadata_reads_and_keep_source_binding() -> None:
    calls = []

    class Request:
        def __init__(self, value):
            self.value = value

        def execute(self):
            return self.value

    class Messages:
        def list(self, **kwargs):
            calls.append(("list", kwargs))
            return Request({"messages": [{"id": "m1"}]})

        def get(self, **kwargs):
            calls.append(("get", kwargs))
            return Request(
                {
                    "id": "m1",
                    "threadId": "t1",
                    "internalDate": "1234",
                    "payload": {
                        "headers": [
                            {"name": "From", "value": "airline@example.com"},
                            {"name": "Subject", "value": "Trip confirmation"},
                            {"name": "Date", "value": "Fri, 8 Aug 2026 10:00:00 -0400"},
                        ]
                    },
                }
            )

    class Users:
        def messages(self):
            return Messages()

    class Service:
        def users(self):
            return Users()

    rows = list_travel_message_candidates(Service(), max_results=5)
    assert rows == [
        {
            "message_id": "m1",
            "thread_id": "t1",
            "internal_date_ms": "1234",
            "from": "airline@example.com",
            "subject": "Trip confirmation",
            "date": "Fri, 8 Aug 2026 10:00:00 -0400",
            "source": "gmail.readonly",
        }
    ]
    assert [name for name, _kwargs in calls] == ["list", "get"]
    assert calls[1][1]["format"] == "metadata"
    assert set(calls[1][1]["metadataHeaders"]) == {"From", "Subject", "Date"}
    assert calls[0][1]["fields"] == "messages(id,threadId)"
    assert calls[1][1]["fields"] == "id,threadId,internalDate,payload(headers)"
