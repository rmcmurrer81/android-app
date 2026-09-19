# Durable Android device-auth foundation

Status: **STAGED / NOT CONNECTED / NOT DEPLOYED / NOT OWNER-ACCEPTED**

This source-only foundation prepares the canonical future Sarah Android client
for renewable per-device access without embedding an indefinite bearer. It does
not alter the current canonical app, the 72-hour event-candidate package, its
Worker, any UI, or any runtime route.

Implemented in this staging layer:

- versioned AndroidKeyStore `secp256r1` signing-key creation;
- a runtime check that rejects an unexpectedly exportable private key;
- public-only P-256 JWK and RFC 7638 thumbprint derivation;
- exact enrollment and session proof payload construction;
- strict DER ECDSA to 64-byte IEEE-P1363 conversion;
- base64url-without-padding challenge signatures; and
- explicit `KEY_MISSING_REENROLL_REQUIRED` state when a bound device loses its
  key, with no silent replacement under the old device identity.

Not implemented or claimed here:

- device-state persistence;
- enrollment UI or owner approval;
- Worker connection, D1, challenge retrieval, token issuance, or renewal;
- access-token memory management;
- rotation, revocation, recovery, or offline retry orchestration;
- Android package integration or physical Galaxy A17 acceptance.

The two staging classes are intentionally unreferenced by application runtime
code. They may be connected only after the separate full Worker contract and
durable client state machine pass their interoperability and artifact-boundary
gates. No access JWT, backend bearer, endpoint, provider credential, device ID,
or private key is compiled or persisted by this foundation.
