# Sarah Windows secure-pairing responder - 2026-08-08

Status: implementation and local loopback interoperability/security tests passed. Physical Android-to-Windows LAN acceptance remains required.

## Implemented contract

- Windows starts a private-LAN TCP listener on an OS-selected nonzero port.
- The exact port is advertised by the existing short-lived UDP discovery notice.
- Discovery starts while the first-run local-name card is still open. It advertises only a rotating instance ID plus a human device label/type and never needs the owner's name.
- A discovered/incoming device first asks whether the owner wants a secure connection. Declining leaves the local first-run form available and creates no trust or profile mutation.
- Pairing frames are four-byte big-endian length-prefixed JSON with an 8,192-byte maximum.
- The accepted order is offer, response, matching-code approval on both devices, initiator confirmation, responder confirmation, then credential finalization.
- X25519, HKDF, transcript binding, HMAC confirmation proofs, expiry, and the six-digit SAS use the shared Android/Python protocol.
- Only one connection may be in the approval phase at once; accept, initial frame, approval, and confirmation are bounded.
- Connections from non-private/non-loopback addresses fail closed.
- Pre-approval offer and confirmation schemas require their exact fields. Profile data and Gmail, model, provider, voice, or backend credentials cannot be added to those frames.
- Windows stores only the finalized per-device token hash after both cryptographic confirmations. The raw token is not displayed or logged.
- Rejection, timeout, malformed JSON, oversized frames, extra fields, wrong proofs, changed transcripts, and early disconnects create no trusted-device record.

## Current boundary

This establishes a device trust credential; it does not itself authorize or implement profile/media/email synchronization. A later data transport must authenticate with the finalized credential, encrypt its payload independently, enforce per-record permissions, and receive separate owner acceptance.

## Physical gate

Run the exact owner-test APK and EXE on the same private LAN, allow the EXE's private-network listener through Windows Firewall if prompted, verify discovery advertises a nonzero port, verify the same code is visible on both devices, approve on both, and prove a trusted-device row appears only after the final confirmation. Also test mismatch, reject, expiry, app close, Wi-Fi loss, and firewall refusal. No physical pass is claimed by the loopback tests.
