from __future__ import annotations

import hashlib
import json
import os
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parseaddr
from pathlib import Path
import secrets
import sys
from typing import Any, Callable, Mapping
from urllib.parse import urlsplit

from sarah_core import _protect_runtime_secret, _unprotect_runtime_secret


GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
GMAIL_TOKEN_SCHEMA = "sarah-gmail-oauth-v1"
GMAIL_TOKEN_SECRET_NAME = "SARAH_GMAIL_OAUTH_TOKEN"
MAX_OAUTH_CLIENT_BYTES = 64_000
MAX_TOKEN_FILE_BYTES = 256_000
MAX_MESSAGE_CANDIDATES = 50
GOOGLE_TOKEN_REVOCATION_URL = "https://oauth2.googleapis.com/revoke"
GMAIL_BUNDLED_CLIENT_NAME = "sarah-gmail-oauth-client.json"
GMAIL_DEVELOPMENT_CLIENT_ENV = "SARAH_GMAIL_DESKTOP_CLIENT_PATH"


class GmailConfigurationError(ValueError):
    """The selected OAuth client cannot satisfy Sarah's read-only boundary."""


class GmailAuthorizationError(RuntimeError):
    """A Gmail authorization state was incomplete or exceeded its scope."""


@dataclass(frozen=True)
class GmailOAuthClient:
    path: Path
    sha256: str
    file_sha256: str
    client_id: str
    project_id: str
    auth_uri: str
    token_uri: str


@dataclass(frozen=True)
class GmailConnectionReceipt:
    connected: bool
    account_email: str
    client_sha256: str
    scopes: tuple[str, ...]
    refreshed_at: str


@dataclass(frozen=True)
class GmailDisconnectReceipt:
    local_token_removed: bool
    remote_revocation_attempted: bool
    remote_revocation_succeeded: bool


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _read_bounded_json(path: Path, limit: int) -> dict[str, Any]:
    candidate = Path(path).expanduser().resolve()
    if not candidate.is_file():
        raise FileNotFoundError(f"OAuth file does not exist: {candidate}")
    size = candidate.stat().st_size
    if size <= 0 or size > limit:
        raise GmailConfigurationError("OAuth file is empty or exceeds the bounded size limit")
    try:
        parsed = json.loads(candidate.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise GmailConfigurationError("OAuth file is not valid UTF-8 JSON") from exc
    if not isinstance(parsed, dict):
        raise GmailConfigurationError("OAuth file must contain one JSON object")
    return parsed


def _require_google_https(uri: str, allowed_hosts: set[str], field: str) -> str:
    parsed = urlsplit(str(uri).strip())
    if parsed.scheme != "https" or (parsed.hostname or "").lower() not in allowed_hosts:
        raise GmailConfigurationError(f"OAuth {field} must use the expected Google HTTPS host")
    if parsed.username or parsed.password or parsed.fragment:
        raise GmailConfigurationError(f"OAuth {field} contains unsupported URL components")
    return str(uri).strip()


def _validate_loopback_redirect(uri: str) -> None:
    parsed = urlsplit(str(uri).strip())
    host = (parsed.hostname or "").lower()
    if parsed.scheme != "http" or host not in {"127.0.0.1", "localhost", "::1"}:
        raise GmailConfigurationError(
            "Desktop OAuth redirects must remain on an HTTP loopback address"
        )
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise GmailConfigurationError("Desktop OAuth redirect contains unsupported URL components")


def inspect_desktop_oauth_client(path: Path) -> GmailOAuthClient:
    """Validate one Google installed-app client without copying or modifying it."""

    candidate = Path(path).expanduser().resolve()
    parsed = _read_bounded_json(candidate, MAX_OAUTH_CLIENT_BYTES)
    raw = candidate.read_bytes()
    installed = parsed.get("installed")
    if not isinstance(installed, dict) or "web" in parsed:
        raise GmailConfigurationError("Select a Google OAuth Desktop app client JSON")
    client_id = str(installed.get("client_id", "")).strip()
    client_secret = str(installed.get("client_secret", "")).strip()
    project_id = str(installed.get("project_id", "")).strip()
    if not client_id or len(client_id) > 1024 or not client_secret or len(client_secret) > 4096:
        raise GmailConfigurationError("Desktop OAuth client ID or client secret is missing or invalid")
    if not client_id.endswith(".apps.googleusercontent.com"):
        raise GmailConfigurationError("OAuth client ID is not a Google installed-app client")
    auth_uri = _require_google_https(
        str(installed.get("auth_uri", "")),
        {"accounts.google.com"},
        "authorization endpoint",
    )
    token_uri = _require_google_https(
        str(installed.get("token_uri", "")),
        {"oauth2.googleapis.com", "accounts.google.com"},
        "token endpoint",
    )
    redirects = installed.get("redirect_uris")
    if not isinstance(redirects, list) or not redirects:
        raise GmailConfigurationError("Desktop OAuth client has no loopback redirect URI")
    for redirect in redirects:
        _validate_loopback_redirect(str(redirect))
    semantic_identity = {
        "client_id": client_id,
        "auth_uri": auth_uri,
        "token_uri": token_uri,
        "redirect_uris": sorted(str(item).strip() for item in redirects),
    }
    return GmailOAuthClient(
        path=candidate,
        sha256=hashlib.sha256(
            json.dumps(semantic_identity, sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest(),
        file_sha256=hashlib.sha256(raw).hexdigest(),
        client_id=client_id,
        project_id=project_id,
        auth_uri=auth_uri,
        token_uri=token_uri,
    )


def resolve_desktop_oauth_client_path(
    *,
    bundle_root: Path | None = None,
    development_path: Path | None = None,
) -> Path | None:
    """Resolve Sarah's build-bound Desktop OAuth identity.

    The normal owner surface never asks for a developer JSON. Packaged builds
    receive one fixed installed-app client at build time. Source developers may
    point at an external file through ``SARAH_GMAIL_DESKTOP_CLIENT_PATH``; the
    path and file are never copied into Sarah's runtime state.
    """

    if development_path is not None:
        candidate = Path(development_path).expanduser().resolve()
        return candidate if candidate.is_file() else None
    configured = str(os.environ.get(GMAIL_DEVELOPMENT_CLIENT_ENV, "")).strip()
    if configured and not bool(getattr(sys, "frozen", False)):
        candidate = Path(configured).expanduser().resolve()
        return candidate if candidate.is_file() else None
    root = Path(
        bundle_root
        if bundle_root is not None
        else getattr(sys, "_MEIPASS", Path(__file__).resolve().parent)
    ).expanduser().resolve()
    candidate = root / GMAIL_BUNDLED_CLIENT_NAME
    return candidate if candidate.is_file() else None


def _normalized_scopes(value: Any) -> tuple[str, ...]:
    if isinstance(value, str):
        items = value.split()
    elif isinstance(value, (list, tuple, set)):
        items = [str(item) for item in value]
    else:
        items = []
    return tuple(sorted({item.strip() for item in items if item.strip()}))


def _validate_authorized_user_info(
    info: Mapping[str, Any],
    client: GmailOAuthClient,
) -> dict[str, Any]:
    normalized = {str(key): value for key, value in dict(info).items()}
    if str(normalized.get("client_id", "")).strip() != client.client_id:
        raise GmailAuthorizationError("Gmail token belongs to a different OAuth client")
    if not str(normalized.get("client_secret", "")).strip():
        raise GmailAuthorizationError("Gmail authorized-user state has no installed-app client secret")
    if not str(normalized.get("refresh_token", "")).strip():
        raise GmailAuthorizationError("Gmail did not return an offline refresh token")
    token_uri = str(normalized.get("token_uri", "")).strip()
    _require_google_https(
        token_uri,
        {"oauth2.googleapis.com", "accounts.google.com"},
        "token endpoint",
    )
    scopes = _normalized_scopes(normalized.get("scopes"))
    if scopes != (GMAIL_READONLY_SCOPE,):
        raise GmailAuthorizationError(
            "Gmail authorization must grant exactly gmail.readonly and no broader scope"
        )
    normalized["scopes"] = list(scopes)
    return normalized


class GmailTokenVault:
    """Account-bound encrypted Gmail state stored outside the executable.

    On Windows, the existing Sarah secret envelope uses current-user DPAPI. The
    source-test fallback uses the same AES-GCM local-secret boundary as Sarah's
    other protected runtime settings. No token or account address is plaintext.
    """

    def __init__(self, root: Path, person_id: str = "device"):
        self.root = Path(root).expanduser().resolve()
        identity = str(person_id or "").strip()
        if not identity or len(identity) > 256:
            raise GmailAuthorizationError("Gmail authorization has no bounded profile identity")
        profile_key = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:32]
        self.path = self.root / "gmail" / profile_key / "gmail-oauth-token.json"

    def save(
        self,
        client: GmailOAuthClient,
        account_email: str,
        authorized_user_info: Mapping[str, Any],
        *,
        last_sync_at: str = "",
    ) -> GmailConnectionReceipt:
        email = str(account_email).strip().lower()
        parsed_email = parseaddr(email)[1].lower()
        if not email or parsed_email != email or "@" not in email:
            raise GmailAuthorizationError("Gmail profile did not return one valid account address")
        info = _validate_authorized_user_info(authorized_user_info, client)
        payload = {
            "schema": GMAIL_TOKEN_SCHEMA,
            "client_sha256": client.sha256,
            "client_id": client.client_id,
            "account_email": email,
            "scopes": [GMAIL_READONLY_SCOPE],
            "authorized_user_info": info,
            "last_sync_at": str(last_sync_at).strip(),
            "updated_at": _utc_now(),
        }
        protected = _protect_runtime_secret(
            GMAIL_TOKEN_SECRET_NAME,
            json.dumps(payload, separators=(",", ":"), ensure_ascii=False),
            self.root,
        )
        envelope = {
            "schema": GMAIL_TOKEN_SCHEMA,
            "protected": protected,
        }
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_name(f".{self.path.name}.{secrets.token_hex(6)}.tmp")
        try:
            temporary.write_text(json.dumps(envelope, indent=2), encoding="utf-8")
            try:
                temporary.chmod(0o600)
            except OSError:
                pass
            os.replace(temporary, self.path)
        finally:
            if temporary.exists():
                temporary.unlink()
        return self.receipt(client)

    def load(self, client: GmailOAuthClient) -> dict[str, Any]:
        envelope = _read_bounded_json(self.path, MAX_TOKEN_FILE_BYTES)
        if envelope.get("schema") != GMAIL_TOKEN_SCHEMA:
            raise GmailAuthorizationError("Gmail token file has an unsupported schema")
        protected = str(envelope.get("protected", ""))
        if not protected:
            raise GmailAuthorizationError("Gmail token file contains no protected state")
        try:
            plaintext, legacy = _unprotect_runtime_secret(
                GMAIL_TOKEN_SECRET_NAME, protected, self.root
            )
            if legacy:
                raise GmailAuthorizationError("Plaintext Gmail OAuth state is not accepted")
            payload = json.loads(plaintext)
        except GmailAuthorizationError:
            raise
        except Exception as exc:
            raise GmailAuthorizationError(
                "Gmail token could not be opened by this operating-system account"
            ) from exc
        if not isinstance(payload, dict) or payload.get("schema") != GMAIL_TOKEN_SCHEMA:
            raise GmailAuthorizationError("Protected Gmail token payload is invalid")
        if payload.get("client_sha256") != client.sha256 or payload.get("client_id") != client.client_id:
            raise GmailAuthorizationError("Gmail token is not bound to the selected OAuth client file")
        if _normalized_scopes(payload.get("scopes")) != (GMAIL_READONLY_SCOPE,):
            raise GmailAuthorizationError("Stored Gmail scope is not exactly read-only")
        payload["authorized_user_info"] = _validate_authorized_user_info(
            payload.get("authorized_user_info", {}), client
        )
        email = str(payload.get("account_email", "")).strip().lower()
        if parseaddr(email)[1].lower() != email or "@" not in email:
            raise GmailAuthorizationError("Stored Gmail account identity is invalid")
        payload["account_email"] = email
        return payload

    def receipt(self, client: GmailOAuthClient) -> GmailConnectionReceipt:
        payload = self.load(client)
        return GmailConnectionReceipt(
            connected=True,
            account_email=str(payload["account_email"]),
            client_sha256=str(payload["client_sha256"]),
            scopes=(GMAIL_READONLY_SCOPE,),
            refreshed_at=str(payload.get("updated_at", "")),
        )

    def disconnect(
        self,
        client: GmailOAuthClient,
        *,
        revoke: Callable[[str], bool] | None = None,
    ) -> GmailDisconnectReceipt:
        token = ""
        if self.path.is_file():
            try:
                payload = self.load(client)
                token = str(
                    payload["authorized_user_info"].get("refresh_token", "")
                    or payload["authorized_user_info"].get("token", "")
                ).strip()
            except Exception:
                # An unreadable local token is still removed from Sarah. It is
                # never sent to a callback when its integrity cannot be proven.
                token = ""
        attempted = bool(revoke is not None and token)
        succeeded = False
        if attempted:
            try:
                succeeded = bool(revoke(token))
            except Exception:
                succeeded = False
        existed = self.path.is_file()
        if existed:
            self.path.unlink()
        return GmailDisconnectReceipt(
            local_token_removed=existed and not self.path.exists(),
            remote_revocation_attempted=attempted,
            remote_revocation_succeeded=succeeded,
        )

    def remove_local(self) -> bool:
        """Remove only this profile's local grant when client identity is unavailable."""

        existed = self.path.is_file()
        if existed:
            self.path.unlink()
        return existed and not self.path.exists()


class GmailReadOnlyOAuth:
    """Interactive desktop authorization and refresh with injectable I/O."""

    def __init__(self, vault: GmailTokenVault):
        self.vault = vault

    def connect(
        self,
        client_path: Path,
        *,
        flow_factory: Callable[..., Any] | None = None,
        profile_reader: Callable[[Any], Mapping[str, Any]] | None = None,
    ) -> GmailConnectionReceipt:
        client = inspect_desktop_oauth_client(client_path)
        if flow_factory is None:
            try:
                from google_auth_oauthlib.flow import InstalledAppFlow
            except ImportError as exc:
                raise GmailAuthorizationError("Google OAuth packages are not installed") from exc
            flow_factory = InstalledAppFlow.from_client_secrets_file
        flow = flow_factory(
            str(client.path),
            [GMAIL_READONLY_SCOPE],
            autogenerate_code_verifier=True,
        )
        credentials = flow.run_local_server(
            host="127.0.0.1",
            port=0,
            open_browser=True,
            authorization_prompt_message="Open the browser to approve Sarah's read-only Gmail access: {url}",
            success_message="Sarah received read-only Gmail authorization. You may close this tab.",
            access_type="offline",
            prompt="consent",
            timeout_seconds=180,
        )
        reported_scopes = _normalized_scopes(
            getattr(credentials, "granted_scopes", None)
            or getattr(credentials, "scopes", None)
            or [GMAIL_READONLY_SCOPE]
        )
        if reported_scopes != (GMAIL_READONLY_SCOPE,):
            raise GmailAuthorizationError("Google returned a scope broader than gmail.readonly")
        try:
            authorized_info = json.loads(credentials.to_json())
        except Exception as exc:
            raise GmailAuthorizationError("Google did not return valid authorized-user state") from exc
        authorized_info["scopes"] = [GMAIL_READONLY_SCOPE]
        if profile_reader is None:
            try:
                from googleapiclient.discovery import build
            except ImportError as exc:
                raise GmailAuthorizationError("Google Gmail packages are not installed") from exc
            service = build("gmail", "v1", credentials=credentials, cache_discovery=False)
            profile = service.users().getProfile(userId="me").execute()
        else:
            profile = profile_reader(credentials)
        account_email = str(dict(profile).get("emailAddress", "")).strip()
        return self.vault.save(client, account_email, authorized_info)

    def credentials(
        self,
        client_path: Path,
        *,
        request_factory: Callable[[], Any] | None = None,
    ) -> Any:
        client = inspect_desktop_oauth_client(client_path)
        payload = self.vault.load(client)
        try:
            from google.oauth2.credentials import Credentials
            from google.auth.transport.requests import Request
        except ImportError as exc:
            raise GmailAuthorizationError("Google OAuth packages are not installed") from exc
        credentials = Credentials.from_authorized_user_info(
            payload["authorized_user_info"], [GMAIL_READONLY_SCOPE]
        )
        if not credentials.valid:
            if not credentials.refresh_token:
                raise GmailAuthorizationError("Gmail authorization expired without a refresh token")
            credentials.refresh((request_factory or Request)())
            info = json.loads(credentials.to_json())
            info["scopes"] = [GMAIL_READONLY_SCOPE]
            self.vault.save(
                client,
                str(payload["account_email"]),
                info,
                last_sync_at=str(payload.get("last_sync_at", "")),
            )
        return credentials


def revoke_google_authorization(
    token: str,
    *,
    post: Callable[..., Any] | None = None,
) -> bool:
    """Revoke the exact Google token over the fixed Google HTTPS endpoint."""

    value = str(token or "").strip()
    if not value or len(value) > 16_384:
        return False
    if post is None:
        try:
            import requests
        except ImportError:
            return False
        post = requests.post
    try:
        response = post(
            GOOGLE_TOKEN_REVOCATION_URL,
            data={"token": value},
            headers={"Content-Type": "application/x-www-form-urlencoded"},
            timeout=10,
            allow_redirects=False,
        )
    except Exception:
        return False
    return int(getattr(response, "status_code", 0)) == 200


def list_travel_message_candidates(
    service: Any,
    *,
    query: str = "newer_than:180d (subject:(travel OR itinerary OR reservation OR booking OR flight OR hotel OR ticket) OR label:travel)",
    max_results: int = 25,
) -> list[dict[str, str]]:
    """Read source-bound Gmail candidates; never modify, send, or delete mail."""

    limit = int(max_results)
    if limit < 1 or limit > MAX_MESSAGE_CANDIDATES:
        raise ValueError(f"max_results must be between 1 and {MAX_MESSAGE_CANDIDATES}")
    listing = service.users().messages().list(
        userId="me",
        q=str(query)[:500],
        maxResults=limit,
        fields="messages(id,threadId)",
    ).execute()
    rows: list[dict[str, str]] = []
    for item in list(listing.get("messages", []))[:limit]:
        message_id = str(item.get("id", "")).strip()
        if not message_id:
            continue
        message = service.users().messages().get(
            userId="me",
            id=message_id,
            format="metadata",
            metadataHeaders=["From", "Subject", "Date"],
            fields="id,threadId,internalDate,payload(headers)",
        ).execute()
        headers = {
            str(header.get("name", "")).lower(): str(header.get("value", ""))
            for header in message.get("payload", {}).get("headers", [])
            if isinstance(header, dict)
        }
        rows.append(
            {
                "message_id": message_id,
                "thread_id": str(message.get("threadId", "")),
                "internal_date_ms": str(message.get("internalDate", "")),
                "from": headers.get("from", ""),
                "subject": headers.get("subject", ""),
                "date": headers.get("date", ""),
                "source": "gmail.readonly",
            }
        )
    return rows
