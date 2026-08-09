# Sarah Android secure device pairing checkpoint — 2026-08-08

Status: **SOURCE/DETERMINISTIC-INTEROPERABILITY PASS; PHYSICAL TWO-DEVICE ACCEPTANCE PENDING**

This change replaces Android's visible but disabled legacy pairing controls
with the Android half of Sarah's explicit two-device identity proof. It does
not enable the retired plaintext-HTTP token-return sync protocol and does not
transfer any profile, memory, trip, photo, owner, Gmail, model, voice, or
provider credential.

## Implemented contract

- UDP discovery uses `SARAH_DISCOVER_V2` on port `8771` and accepts only
  `sarah-device-discovery-v2` notices from literal loopback/private/link-local
  addresses.
- Discovery notices expose only a rotating process instance, display name,
  device type, short expiry, pairing protocol, and advertised ephemeral
  pairing port. Discovery is never trust.
- The owner is asked **“Is this your device?”** before a handshake begins.
- Pairing uses X25519, SHA-256, HKDF-SHA256, and transcript-bound HMAC proofs
  matching `windows-companion/sarah_device_pairing.py`.
- Both devices derive and display the same six-digit SAS. Both must explicitly
  confirm it before either side can finalize the per-device credential.
- Expired messages, changed request IDs/lifetimes/peer identity, malformed or
  oversized frames, public-network addresses, all-zero X25519 secrets,
  altered proofs, duplicate confirmations, and replayed responses fail closed.
- Only a finalized credential can enter `TrustedDeviceStore`; its secret is
  encrypted by `SecureProfileVault` with Android Keystore AES-GCM. Discovery,
  offers, and partial sessions are never persisted.
- Legacy plaintext tokens are not promoted into the new trust store.

## TCP framing contract for the matching Windows responder

The discovered private address and advertised ephemeral port are pinned for
the connection. Each message is a four-byte unsigned big-endian length followed
by exactly that many UTF-8 JSON bytes; the cap is 8,192 bytes.

1. Android sends `offer`.
2. Windows sends `response`.
3. Both display the derived SAS and wait for local owner approval.
4. Android sends its transcript-bound `confirmation` after approval.
5. Windows sends its transcript-bound `confirmation` after approval.
6. Each side verifies the peer confirmation and derives the same credential.
7. The connection closes. No sync/data frame exists in this contract.

Connect timeout is 5 seconds; initial response timeout is 10 seconds; the
confirmation wait is bounded by the protocol's 120-second expiry.

## Verification completed

- `SarahPairingProtocolTest.java`: pass under Temurin JDK 17.
- Fixed Android/Java vector matches Python `cryptography` output for both
  X25519 public keys, canonical transcript, SAS `488550`, both confirmation
  proofs, and the final 256-bit credential.
- The Java test also proves two explicit approvals, expiry, tamper, duplicate
  response/confirmation, premature finalization, and all-zero-secret rejection.
- `Sarah_Morgan_Android_Phone_First_v3/tests/validate_package.py`: pass.

## Still pending (must not be described as accepted)

- The Windows responder/listener and owner confirmation UI must implement the
  exact framing sequence above and advertise a nonzero ephemeral pairing port.
- A freshly built APK must compile on CI and install on the Samsung Galaxy A17.
- Physical phone/Windows testing must prove discovery, matching codes, two
  explicit approvals, credential equality/storage, cancel, timeout, tamper,
  replay rejection, revocation, and no unintended data/credential transfer.
- Profile/travel-data synchronization remains disabled. Pairing acceptance does
  not authorize or imply sync acceptance.

## Rollback scope

Revert only these Android files if required:

- `SarahPairingProtocol.java`
- `SarahPairingTransport.java`
- `SarahDeviceDiscovery.java`
- `SarahAutoPairCoordinator.java`
- `TrustedDeviceStore.java`
- `TrustedSyncActivity.java`
- `tests/SarahPairingProtocolTest.java`
- the pairing assertions in `tests/validate_package.py`

Do not restore or enable the retired plaintext HTTP pairing/token path.
