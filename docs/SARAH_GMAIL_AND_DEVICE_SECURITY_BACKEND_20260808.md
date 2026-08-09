# Sarah Gmail and trusted-device backend checkpoint — 2026-08-08

## Owner observation and audit truth

The Windows program Robert opened did not provide a Gmail connection or usable
device continuity. The current source audit confirms these were not hidden
working features:

| Capability | Before this checkpoint | Truth |
|---|---|---|
| Android Gmail | Disabled placeholder | A separate local `AuthorizationClient`/`gmail.readonly` connector is now implemented, metadata-only, owner-controlled, and default-off for monitoring. No physical Google authorization or mailbox read has passed. |
| Windows Gmail | Absent | Google Drive `appDataFolder` OAuth existed only for encrypted backup transfer, not Gmail. |
| Android discovery UI | Preserved prototype | First-run now performs one bounded same-Wi-Fi discovery decision before name/profile questions. Discovery transfers nothing; pairing still requires the separate matching-code and both-device confirmation gate. |
| Windows discovery | Rejected prototype retained loopback-only | No LAN listener is started or advertised. |
| Pairing | Rejected plain-HTTP prototype | It would have returned the bearer/encryption token over HTTP, so it cannot be enabled. |

## New isolated backend components

`windows-companion/sarah_gmail.py` now provides:

- bounded validation of an owner-selected Google Desktop OAuth client JSON;
- exact client-file SHA-256 binding;
- the system browser, an ephemeral `127.0.0.1` callback, and PKCE;
- exactly `https://www.googleapis.com/auth/gmail.readonly` and no broader scope;
- an offline refresh-token requirement;
- current-user protected storage of the account address, access token, refresh
  token, installed-app client secret, scope, and lifecycle metadata;
- fail-closed load/refresh behavior if the scope, client hash, token endpoint,
  refresh token, account identity, or protected envelope is invalid;
- local disconnect and an injectable remote-revocation step;
- bounded `messages.list` plus metadata-only `messages.get` candidate reads with
  exact Gmail message/thread/time/source binding and no send, modify, or delete
  operation.

`windows-companion/sarah_device_pairing.py` now provides:

- short-lived UDP discovery notices containing no profile, owner identity,
  stable device ID, bearer token, or sync data;
- local/private-source validation;
- a process-rotated discovery instance ID;
- X25519 ephemeral key agreement, HKDF separation, and a six-digit short
  authentication string derived from the exact transcript;
- an explicit owner confirmation on **both** devices;
- role-bound HMAC confirmation proofs;
- a shared sync credential only after both matching-code confirmations;
- 120-second expiry, replay/request binding, transcript binding, tamper checks,
  and no credential transmitted in a pairing message.

## Verification performed

- `py -m pytest -q` from `windows-companion`: **86 passed**.
- The new focused files contribute 17 tests covering exact scope, PKCE,
  loopback redirects, encrypted token-at-rest behavior, client-hash binding,
  disconnect/revocation, metadata-only reads, discovery data minimization,
  private-source restrictions, matching SAS values, both-device approval,
  tamper rejection, request binding, and expiry.

These are source and automated domain tests. They are not a Gmail connection,
mailbox read, LAN pairing, Android interoperability, or owner acceptance.

The later Android connector checkpoint also passed `validate_package.py` and
the repository Python contract suite (55 tests). Its Java/Android build and
physical OAuth tests remain pending; see
`SARAH_ANDROID_GMAIL_READONLY_CHECKPOINT_20260808.md` and
`../GOOGLE_GMAIL_SETUP.md`.

## Remaining integration and supervised gates

1. Connect the Gmail backend to one owner-facing Windows action without
   displaying tokens or raw OAuth state.
2. Configure a real Google Cloud OAuth consent screen and Desktop client. The
   `gmail.readonly` restricted scope requires the applicable Google verification
   before broader distribution; private test users do not prove distribution
   approval.
3. Run a supervised account selection, exact-scope consent, known-message read,
   candidate review, disconnect, remote revocation, and post-revocation failure.
4. Port the pairing protocol to Android using a reviewed X25519 implementation.
5. Add a small authenticated pairing transport for the offer, response, and
   confirmation messages. Discovery alone must never carry sync data.
6. Store the derived credential through each platform's protected credential
   store, then reuse the existing encrypted/signed sync payload format.
7. Run a physical two-device test on private Wi-Fi: discover, ask whether the
   device belongs to Robert, show the same code on both devices, require a yes
   on each, perform one bounded sync, revoke either side, and prove later sync
   fails.
8. Keep the earlier plain-HTTP `/pair` token-return path disabled. Do not switch
   `TrustedSyncClient.isTransportAccepted()` to true until the new protocol and
   transport pass interoperability and security review.

Until those gates pass, owner-facing status remains **Gmail not connected** and
**device sync setup required**.
