# Sarah post-trust continuity sync — 2026-08-08

## Owner outcome

The first-run **sync me** path is no longer only a pairing claim. After the
Windows and Android devices complete the X25519 matching-code flow and both
owners approve the same code, Android can request a separately encrypted
continuity preview from that exact Windows Sarah instance. Android shows the
profile name and category counts and imports only after a second explicit
owner decision.

This is a private-LAN transfer. It is not cloud sync and it does not silently
run merely because two Sarah installations are on the same Wi-Fi.

### Both install directions

The bounded implementation now supports both owner setup directions:

1. A new Android phone pairs with established Windows, previews Windows
   continuity, then explicitly imports it on Android. The authenticated receipt
   is `WINDOWS_TO_ANDROID_PULL_ONLY`.
2. A new Windows installation discovers an established Android Sarah while the
   Android **Devices** screen is open, initiates the same X25519/SAS proof,
   previews Android continuity, then explicitly imports it on Windows before
   the owner enters a new local name. The authenticated receipt is
   `ANDROID_TO_WINDOWS_PULL_ONLY`.

“Pull only” describes each authenticated request, not a missing install
direction: the receiving new device requests and reviews data from the
established device. Unsolicited push operations remain rejected.

## Included

- the exact active confirmed-owner profile fields: name, confirmed age state,
  hometown, interests and memory-consent state;
- conversation continuity;
- approved memory rows;
- trips;
- travel wishes.

The Windows export is scoped to the active owner. Android requires the exact
confirmed owner identity before importing owner-bound rows. Existing local
profile facts are retained; the existing importer fills missing profile facts
and appends unseen continuity rows.

## Excluded by construction

- Gmail OAuth access or refresh tokens;
- model, provider, research, map or voice credentials;
- ElevenLabs credentials;
- raw or derivative private photos;
- private-mind records;
- proactive-discovery rows;
- another person's profile or data.

The Android client rejects a preview if photos, private-mind events or
discoveries appear despite the Windows boundary.

## Protocol and storage

- Protocol: `sarah-secure-sync-v2` over the already advertised pairing TCP
  listener, using bounded four-byte big-endian JSON frames.
- Authentication/encryption: the finalized two-device credential is PBKDF2
  derived for AES-GCM payload encryption and separately HMAC-SHA256 signed.
- The credential is never sent in a header or payload.
- Windows stores the raw finalized credential only in a user-bound protected
  vault (`secure_pairing_credentials.json`; Windows DPAPI in release use).
- Android stores it through the existing Android Keystore-backed vault.
- The public request device ID selects the protected credential, while the
  encrypted inner device ID and request ID must exactly match the outer
  envelope.
- Revoked or absent Windows trust records fail closed. Replayed request IDs are
  rejected during the process lifetime.

The retired plaintext HTTP bearer-token sync remains disabled.

## Owner review and conflict truth

Android holds the decrypted package in memory as a preview. The owner may
choose **Import reviewed data** or **Not now**. Nothing imports on preview,
decline or dialog dismissal.

On approval Android appends an `OWNER_APPROVED_SECURE_SYNC_IMPORT` receipt
before mutation. After a successful merge it appends a
`SECURE_SYNC_IMPORT_COMPLETED` receipt with the package digest, offered counts,
new-row count and the count that was already present or rejected. Receipts are
stored in `secure_sync_import_history.jsonl`; they contain no conversation or
memory content.

## Verification

- Windows companion suite: **112 passed** on 2026-08-08.
- End-to-end loopback interop proves X25519 pairing, equal finalized
  credentials, authenticated encrypted preview, exact counts, and exclusion of
  photos/private mind/service secrets.
- Negative tests prove unpaired devices and replayed requests fail closed.
- Windows-initiator tests prove the new installation and established responder
  derive the same credential only after both confirmations.
- Reverse preview tests prove Windows fetches without importing, then imports
  only through the explicit reviewed-import call and writes two append-only
  receipts without conversation content.
- Android package validation: `STATIC_PACKAGE_VALIDATION_PASS_R3`.
- Targeted Android secure-sync contracts: **5 passed**.

## Remaining physical acceptance gate

Build and install the new APK and Windows EXE, place both owner devices on the
same private LAN, permit Sarah's owner-approved private-network listener in
Windows Firewall, then verify:

1. Both devices display and approve the identical short-lived code.
2. Android receives a continuity preview after trust.
3. Declining imports zero rows.
4. Approving imports the displayed categories and writes both append-only
   receipts.
5. Gmail remains connected only on the device where its OAuth grant exists;
   no Gmail token appears on the receiving device.
6. Revoking the Windows trust makes the next preview fail.
7. Repeat in reverse: leave Android's Devices screen open, choose **Find my
   other Sarah device** on a new Windows profile, approve the same code on both,
   decline the first preview and verify the Windows profile is still unnamed,
   then request again, approve the import, and verify Windows adopts the exact
   confirmed owner continuity before asking for a local name.

The Android responder is intentionally owner-visible and temporary: it runs
only while the Devices activity is open. Background always-on LAN serving is
not claimed.

No physical APK-to-EXE acceptance is claimed until that evidence exists.

## Rollback

Restore the prior versions of `sarah_device_pairing.py`,
`sarah_event_ready.py`, `TrustedDeviceStore.java` and
`TrustedSyncActivity.java`; remove `sarah_secure_sync.py`,
`SecureSyncPreviewClient.java` and `SyncImportProvenance.java`; then restore the
prior package-validator assertions. Existing pairing records remain usable for
pairing only. Do not delete owner data or the append-only import receipts during
rollback.
