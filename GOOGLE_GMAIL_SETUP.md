# Gmail booking-import setup

Sarah's Android source now contains an optional Gmail read-only connector, but no physical Google account acceptance has passed. Until the exact signed APK completes the supervised gate below, the truthful state is **connector implemented; Gmail not connected**. Owner-selected booking text, links, screenshots, and PDFs continue to work without Gmail. They remain pending review and cannot change a trip automatically.

The connector uses Google Play services `AuthorizationClient` and requests exactly:

`https://www.googleapis.com/auth/gmail.readonly`

Sarah never asks for a Gmail password. The APK contains no Google client secret, downloaded client JSON, refresh token, or custom embedded-browser sign-in. The Android OAuth client is selected by Google from the exact package name and APK signing SHA-1. The app does not expose a custom redirect or an app-managed PKCE verifier/state; that browser-protocol machinery remains inside Google's authorization broker. Sarah adds a one-use, ten-minute local authorization-attempt binding so a stale result is rejected.

Google's `gmail.readonly` scope authorizes reading Gmail content. Sarah adds a narrower application boundary, not a narrower Google scope: this implementation calls only profile, message-list, and message-get endpoints. Android's message-get uses `format=full` with a partial-response field mask limited to IDs, timestamps, `Subject`/`From`/`Date` headers, and Gmail's bounded short preview. It does not request MIME body parts, attachments, or raw message source. Windows remains metadata-only.

## Human maintainer setup

1. Create or select a Google Cloud project owned by the Sarah team. Configure its OAuth consent screen with accurate app identity and support information.
2. Enable the Gmail API for that project.
3. Create an **Android** OAuth client for package `com.kiraworld.sarahtravel`. Bind it to the SHA-1 fingerprint of the exact APK signing certificate. Debug, locally signed release, and Google Play app-signing certificates require the corresponding separate client registrations.
4. While the consent screen remains in testing, explicitly add the physical-test Google account as a test user. Do not publish or broaden access merely to make a test pass.
5. Verify the dependency versions and signed build: `play-services-auth:21.6.0` and `androidx.work:work-runtime:2.11.2`. Do not put a client secret or OAuth desktop/web client JSON in the APK.
6. Run the physical acceptance below. Opening the account chooser or granting a scope alone is not acceptance.

`gmail.readonly` is a Google restricted scope. Wider distribution can require Google's OAuth verification and, when restricted-scope data is stored or transmitted through a server, an applicable security assessment. This implementation keeps authorization and mailbox reads local to the phone; a future server relay is a new privacy and verification review, not an automatic extension.

## Android behavior and hard boundaries

- First-run same-Wi-Fi Sarah discovery is decided before Sarah asks the person's name. Failure, timeout, cancellation, or a **No** answer continues local profile setup.
- After profile setup, Sarah offers Gmail as a clear **Yes / Not now** option. The same control remains in Settings and Connections.
- Connecting does **not** enable monitoring. The owner must enable background travel-message checks separately; the default is off.
- A check uses a fixed one-year travel/event query, excludes spam and trash, lists at most ten candidates, and retrieves opaque Gmail message/thread IDs, `Subject`/`From`/`Date` metadata, and one bounded Gmail-generated short preview. It does not request MIME body parts, attachments, or raw message source.
- Every candidate receipt records the exact account, message and thread IDs, header date, fetch time, query, endpoint, access mode, `body_read=false`, `bounded_snippet_read=true`, and `message_modified=false`.
- When the confirmed owner returns to the foreground conversation, Sarah may surface one exact pending candidate as silent chat text. The prompt is deduplicated in the encrypted proposal state, does not compete with an existing profile/consent question, and does not start background voice. Only a conservative explicit yes/no bound to that opaque message ID can accept or reject it; **Not now** defers it. The binding lasts for the immediate next owner turn only: unrelated text disarms it and leaves the item pending for later Calendar review. Accepting does not schedule a reminder. The exact subject appears only in the foreground bubble; ordinary chat history stores a source-redacted version of the question.
- Network calls are GET-only and bounded to the Gmail profile/messages endpoints. Send, modify, delete, trash, untrash, mark-read, draft, label, and settings actions are absent and blocked by policy tests.
- Android access tokens are short-lived and encrypted under an Android Keystore AES-GCM key. No device refresh token is requested or stored. When a token cannot be refreshed silently by Google Play services, the UI requires reconnection.
- Optional monitoring uses one WorkManager job about every six hours, requires a connected network, avoids low-battery execution, retries at most twice for transient failures, and stops immediately when disabled or disconnected.
- Disconnect first cancels monitoring and removes the local encrypted token and Gmail receipts, then asks Google to revoke the exact grant. If remote revocation cannot be confirmed, the UI directs the owner to Google Account third-party connections.

## Physical Android supervised read-only test

Use a dedicated Google test account and one known recent travel message.

1. Install the exact signed APK whose package/SHA-1 pair is registered in Google Cloud.
2. On a clean profile, complete or decline same-Wi-Fi Sarah discovery first, then finish the local profile.
3. Choose **Yes** on the optional Gmail offer. Confirm Google's screen names the intended account and requests only Gmail read-only access.
4. Confirm Sarah's first supervised profile read succeeds and the UI shows the exact selected account.
5. Run **Check recent travel email now**. Confirm the known message appears only as a source/time receipt with the bounded preview; inspect Google Gmail and prove it remains unread/unchanged.
6. Confirm monitoring is still off. Enable it explicitly, inspect the WorkManager job and a bounded run, then disable it and prove the job is cancelled.
7. Disconnect. Confirm local token and Gmail receipts disappear immediately, revocation succeeds or is truthfully unconfirmed, and a later check fails closed until Google consent is performed again.
8. Capture the APK SHA-256, version, signing-certificate SHA-1, account alias (not the address in public evidence), requested/granted scopes, request endpoints/methods, timestamps, candidate count, and revocation result.

Do not describe the Android Gmail feature as connected or accepted until every gate passes on the physical phone.

## Required owner controls

- **Connect Gmail** delegates account selection and the exact scope display to Google's consent surface, then shows the selected account after a successful supervised read.
- **Background travel-email monitoring** must be independently opt-in, visibly on/off, and show the last successful sync time.
- **Disconnect Gmail** must stop monitoring, discard local access tokens, and explain how to revoke the app in the Google account if desired.
- **Clear imported booking data** must remove Sarah's app-local imported links, text, screenshots, PDFs, and database rows without deleting or changing Gmail messages.
- Sharing a link, message text, screenshot, or PDF must continue to work without Gmail OAuth and must remain pending review.

No OAuth client secret, access token, refresh token, signing secret, mailbox content, or password belongs in this repository. An Android OAuth registration exists in Google Cloud, not as a bundled secret.

## Windows desktop owner flow (2026-08-08)

The Windows source connects the Gmail read-only backend to the dark owner
interface. A configured owner build opens Google's normal system-browser
consent directly; Robert is not asked to locate or edit a developer JSON. The
flow uses an ephemeral `127.0.0.1` loopback callback with PKCE, requests exactly
`gmail.readonly`, binds the encrypted authorization to the SHA-256 of the
build-bound Google **Desktop app** identity, and stores account/token state in
Sarah's current-Windows-user protected secret envelope.

Google still requires a platform-specific OAuth client identity. For the gated
Windows owner artifact, the maintainer must set the repository variables
`SARAH_GMAIL_DESKTOP_CLIENT_ID` and `SARAH_GMAIL_DESKTOP_CLIENT_SECRET` from one
Google Desktop-app registration. The build creates
`sarah-gmail-oauth-client.json`, packages it as Sarah's public native-client
identity, and deletes the temporary build copy. Do not put an access token,
refresh token, Gmail password, model/backend access code, or provider API key
in either variable. A Desktop/native client cannot keep its packaged identity
confidential; mailbox authority begins only after Robert approves Google's
browser consent and is stored separately under current-user protection.

If those build variables are absent, Sarah fails closed with a plain
"Gmail setup is not in this build" message. She does not open a browser or read
mail, and the normal owner surface never falls back to a file picker. Source
developers can use the external `SARAH_GMAIL_DESKTOP_CLIENT_PATH` environment
setting without copying that file into runtime state.

The same Windows flow turns bounded read-only results into **pending email
suggestions**, never calendar facts. Robert must confirm an exact suggestion,
title, and start time before it becomes a private calendar item. A reminder is
a second opt-in decision and is delivered locally only while Sarah is running.
Saving an item does not claim that Robert attended an event or completed a
journey. Full Google-account authorization, known-message reading, and physical
notification acceptance remain pending on the exact built artifact.

`gmail.readonly` is a Google restricted scope. A private test configuration can
use explicitly listed test users, but broader distribution requires the Google
OAuth consent/verification work applicable to restricted scopes. Do not work
around that process, request a broader scope, bundle mailbox tokens, or replace
the system-browser/loopback flow with an embedded web view.

Primary setup references:

- Android Google-data authorization: https://developer.android.com/identity/authorization
- Google Workspace OAuth credentials: https://developers.google.com/workspace/guides/create-credentials
- Google OAuth for desktop apps: https://developers.google.com/identity/protocols/oauth2/native-app
- Gmail API scopes: https://developers.google.com/workspace/gmail/api/auth/scopes
- Gmail `messages.list` query contract: https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/list
- Gmail `messages.get` metadata contract: https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/get
- Gmail profile contract: https://developers.google.com/workspace/gmail/api/reference/rest/v1/users/getProfile
- OAuth native-app security guidance (PKCE and loopback IP redirects): https://www.rfc-editor.org/rfc/rfc8252
