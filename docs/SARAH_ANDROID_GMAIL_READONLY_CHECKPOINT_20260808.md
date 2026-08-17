# Sarah Android Gmail read-only checkpoint — 2026-08-08

## Outcome

The Android source has an optional, owner-controlled Gmail read-only connector. It is offered only after same-Wi-Fi Sarah discovery and local profile setup, and it is available later from Settings and Connections. Background travel-message checking is a second opt-in and defaults off.

The connector is **not physically accepted**. No Google account was authorized and no mailbox was accessed during source development. A signed APK still needs the Google Cloud package/SHA-1 registration and the supervised physical-phone gate in `GOOGLE_GMAIL_SETUP.md`.

## Implemented source boundary

- Official Google Play services `AuthorizationClient`; exact `gmail.readonly` scope.
- No Gmail password, embedded OAuth secret, desktop/web client JSON, custom WebView, device refresh token, or custom redirect.
- One-use local authorization-attempt expiry rejects stale activity results; Google's authorization broker owns its protocol redirect and authorization integrity.
- Short-lived access token encrypted with an Android Keystore AES-GCM key separate from Sarah's other stores.
- GET-only Gmail profile/list/get client; fixed one-year travel query; spam/trash excluded; ten candidates maximum; 512 KiB response ceiling.
- `messages.get` uses `format=metadata` and only `Subject`, `From`, and `Date`. Message body/snippet retrieval is absent.
- Source/time receipts bind the account, opaque message/thread ID, fetch time, header date, query, endpoint and explicit no-body/no-modification facts.
- Separate, default-off WorkManager monitoring; network and battery constraints; bounded retry; immediate cancellation on disable/disconnect.
- Disconnect removes local tokens and receipts before requesting Google revocation.

## Implementation truth

| Gate | Status |
|---|---|
| Pure scope, endpoint and cache policy | Implemented; automated test required |
| Android authorization UI and token vault | Implemented; Android compile required |
| Settings/Connections hooks | Implemented; device UI review required |
| Discovery before name; email offer after profile | Implemented; clean-install device review required |
| Background monitoring default off | Implemented; WorkManager device inspection required |
| Exact signed APK OAuth registration | Not configured/proven here |
| Real Google consent | Not run |
| Known-message metadata read | Not run |
| Message unchanged proof | Not run |
| Remote revocation proof | Not run |

## Rollback

Remove the manifest entry for `GmailAuthorizationActivity`, remove the Google auth and WorkManager dependencies, and restore the prior Settings/Connections Gmail text. Delete only the new Gmail Java classes and test after verifying no other source references them. Do not remove owner-selected booking text, screenshot, PDF, or link imports. If a physical account was ever connected, disable monitoring and revoke through the in-app control or Google Account third-party connections before rolling back the UI.

## New-file SHA-256 at this checkpoint

- `GmailReadOnlyPolicy.java`: `f8f8f43bd09440431af846a81964e53ac33fbbae451baf8d480e9080a0454e43`
- `GmailTokenVault.java`: `aebb8ad9ffcdd647807ebab8dfa8ac72f3e42227895a0ffcec4268f95bea2e93`
- `GmailReadOnlyClient.java`: `f72a6a6e36e39c806215b3191a2eb25accfb9c0d4392633e2b0b5fbc1bfd8e39`
- `GmailAuthorizationActivity.java`: `77570dfd1b2ce6b68cf5c6a62b0cac8ade13aec7d550b08e98dfdfccaecc7422`
- `GmailMonitorScheduler.java`: `b056c0755cf3d431012747b848a003a0ce1a8caf1525eb1e2275bda674412504`
- `GmailTravelMonitorWorker.java`: `b7079fc87235800ea8a7c696e8775944b874fcbfc009fb5bf6f8342c641821e3`
- `GmailReadOnlyPolicyTest.java`: `de0ae7e0769a09cbcc310575f57a803d7de925cac86852b42afc549b97b0f7cc`

## Primary sources

- https://developer.android.com/identity/authorization
- https://developers.google.com/workspace/guides/create-credentials
- https://developers.google.com/workspace/gmail/api/auth/scopes
- https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/list
- https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/get
- https://developers.google.com/workspace/gmail/api/reference/rest/v1/users/getProfile
- https://developers.google.com/identity/protocols/oauth2/policies
