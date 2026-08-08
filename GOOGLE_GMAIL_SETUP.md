# Gmail booking-import setup (not connected in this build)

Sarah's current Android build is intentionally fail-closed: it does not contain a Gmail OAuth client, does not claim a connected account, and cannot read or monitor a mailbox. The **Connections** screen still supports owner-selected booking text, links, screenshots, and PDFs. Every such import is saved as **pending review** and changes no trip automatically.

## Human maintainer setup

1. Create or select a Google Cloud project owned by the Sarah team. Configure its OAuth consent screen with accurate app identity and support information.
2. Enable the Gmail API for that project.
3. Create an Android OAuth client for package `com.kiraworld.sarahtravel`. Bind it to the SHA-1 fingerprint of the exact APK signing certificate. Debug and release certificates require separate clients.
4. Implement Google account selection and request only the read-only Gmail scope `https://www.googleapis.com/auth/gmail.readonly`. Sarah must never ask for, receive, or store a Gmail password.
5. Keep travel-mail discovery narrow and reviewable: query likely booking/itinerary messages, show the exact selected account and source message, and require owner review before saving a booking or changing a trip. Background monitoring must remain a separate owner opt-in and default off.
6. Add the official Google Identity/Gmail client dependencies and the missing token lifecycle, API request, pagination, retry, revocation, and local receipt code. Do not mark the feature connected merely because an account selector opened.
7. Run a supervised read-only test with a test account: select the account, grant only `gmail.readonly`, read one known travel message, prove no send/delete/modify scope exists, save it as pending review, disconnect, revoke access, and confirm later reads fail closed.
8. Only after that supervised test passes may `GmailTravelConnection.implementationAvailable()` return true or the UI say Gmail is connected.

## Required owner controls

- **Connect Gmail** must show the selected Google account and exact requested scope before consent.
- **Background travel-email monitoring** must be independently opt-in, visibly on/off, and show the last successful sync time.
- **Disconnect Gmail** must stop monitoring, discard local access tokens, and explain how to revoke the app in the Google account if desired.
- **Clear imported booking data** must remove Sarah's app-local imported links, text, screenshots, PDFs, and database rows without deleting or changing Gmail messages.
- Sharing a link, message text, screenshot, or PDF must continue to work without Gmail OAuth and must remain pending review.

No OAuth client ID, access token, refresh token, signing secret, mailbox content, or password belongs in this repository.
