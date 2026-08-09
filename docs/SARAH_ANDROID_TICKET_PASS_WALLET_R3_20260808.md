# Sarah Android R3 ticket/pass wallet and official event links

Date: 2026-08-08
Status: implemented in source; local policy/static acceptance passed; physical APK acceptance pending

## Owner-facing result

Sarah's full Travel Workbench still contains the original loyalty wallet. A separate **Tickets and passes** card now opens a dedicated wallet for an owner-selected ticket, admission pass, event badge, or QR-code image.

Each record is bound to the exact active profile and contains only:

- a sanitized JPEG derivative of the image the owner explicitly selected;
- a human-readable event/pass title;
- an optional date or date range;
- an optional exact HTTPS official source;
- explicit provenance distinguishing a verified event-record source from a manually owner-provided source;
- a local added-at timestamp.

There are deliberately no password, payment-card, CVV, account-recovery, purchase-confirmation, validity-confirmation, or admission-use fields. Saving a pass does not claim that it was purchased, paid, valid, used, or accepted at a venue.

## Storage and privacy boundary

- `TicketPassVaultStore` uses the existing `SecureProfileVault`, which encrypts the per-profile JSON record with Android Keystore AES-GCM.
- Images are read only through Android's owner-driven document picker.
- `PrivateContentSnapshot` creates one bounded, fsynced private snapshot.
- `ImageSanitizer` decodes and re-encodes pixels, strips EXIF/location metadata, and bounds dimensions and allocation.
- The encrypted image derivative is limited to 2,500,000 bytes and each profile is limited to 12 passes.
- Profile correction calls `TicketPassVaultStore.moveProfile`; one person's wallet is not displayed in another person's active profile.
- No broad storage permission and no persistable document-provider grant is requested.
- Ticket/pass images are device-local and are not included in Sarah's current trusted-device sync payload. A future cross-device pass transfer needs its own explicit owner review because a QR code can function as an admission credential.

Viewing decrypts the selected record only in app memory. Sharing is an explicit owner action. It writes one random-name JPEG into the app's scoped cache, exposes only that exact canonical file through an unexported read-only content provider with a temporary URI permission, and expires the cache file after 30 minutes or on a later cleanup pass. This temporary decrypted share copy is the narrow exception required to hand the selected image to another Android app.

## Event links

The Event Trip Center now displays the exact stored official source, offers **Open verified official website / tickets**, and can prefill a new wallet record from that event. The verified label is retained only if the saved URL is byte-for-byte the same HTTPS URL carried from the event record. If the owner edits it, the wallet labels it owner-provided rather than silently calling it verified.

The official-event conversational response now includes the exact URL and states that opening it does not purchase a ticket. No ticket site was contacted and no purchase was attempted as part of this implementation.

## Acceptance executed

- `TicketPassPolicyTest`: bounded counts/bytes, exact HTTPS query preservation, rejection of HTTP and user-info URLs, and provenance separation.
- `test_ticket_pass_wallet_contract.py`: encrypted/profile-isolated schema, owner-selected sanitized import, read-only scoped sharing, Workbench/event-center discoverability, exact source surfacing, and profile migration binding.
- Android manifest XML parsing.
- changed-file whitespace verification.

## Windows boundary

The Windows companion does not yet have ticket/pass-wallet parity in this bounded change. Its existing owner store was not modified because the Windows owner UI, Gmail and power-saving work was concurrently active. The Windows UI must not claim pass storage until it has equivalent profile isolation, DPAPI encryption, owner-selected import, bounded QR/image handling, explicit-share scoping, and tests.

## Physical acceptance still required

On the Samsung Galaxy A17, install the newly built R3 APK and verify:

1. Workbench -> Tickets and passes is visible while Loyalty wallet remains visible.
2. Import one non-sensitive test QR/pass image for the active profile.
3. Reopen the app and prove it remains visible only for that profile.
4. View it at readable scale, open the exact source, and share it to a benign local app.
5. Remove it and confirm removal does not cancel or alter any external ticket/account.

Do not use a real payment card, password, recovery code, or irreplaceable only-copy ticket for first acceptance.
