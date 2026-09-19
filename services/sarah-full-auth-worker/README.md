# Sarah full-version device authentication Worker — Phase 1 source

Status: **LOCAL IMPLEMENTATION ONLY — NOT DEPLOYED — NOT OWNER-ACCEPTED**

This is a new, isolated full-version Worker foundation. It does not modify,
extend, relabel, deploy, or retire the separate 72-hour event Worker, its
workflow, its application ID, or either event artifact. No client currently
points at this service.

## Security boundary

The full client contains no backend bearer. Each installation creates its own
ECDSA P-256 key and sends only the public JWK. The server returns no renewable
bearer during enrollment. A device proves its private key against a one-use,
two-minute D1 challenge and receives a ten-minute HS256 access JWT. That JWT is
intended to remain in client memory only. A fresh key proof renews a rolling
90-day inactivity lease.

Every protected request validates, in this order:

1. full API origin and current/previous JWT signing-key configuration;
2. access-JWT signature, issuer, audience, time, JTI, and scope;
3. current device status, lease, owner, key version, and `auth_epoch` from a
   D1 `first-primary` session;
4. the per-device route rate limit; and only then
5. the configured Workers AI/OpenAI, Tavily, or ElevenLabs provider.

Missing D1 or signing configuration therefore fails before a provider call.
Device revocation increments `auth_epoch`, so an otherwise unexpired JWT is
rejected on its next request.

## Canonical signatures

Public keys use exactly `kty`, `crv`, `x`, and `y`, with `kty=EC` and
`crv=P-256`. Signatures are 64-byte IEEE-P1363 `r || s`, base64url without
padding. Payloads are exact UTF-8 text with line-feed separators and no final
line feed.

Enrollment completion:

```text
SARAH-ENROLLMENT-V1
enrollment_id
challenge
api_origin
key_thumbprint
```

Session renewal:

```text
SARAH-AUTH-V1
device_id
challenge_id
nonce
api_origin
key_version
```

Self-revocation:

```text
SARAH-DEVICE-REVOKE-V1
device_id
challenge_id
nonce
api_origin
key_version
reason_sha256
```

Key rotation (signed by both old and new keys):

```text
SARAH-KEY-ROTATION-V1
device_id
rotation_id
challenge_id
nonce
api_origin
current_key_version
old_thumbprint
new_thumbprint
```

The old key remains installed until the server confirms rotation. A repeated
rotation ID can recover a lost response while the prior ten-minute JWT remains
valid; the current new key must still prove the exact request. Any different
stale rotation fails closed.

## State and routes

Migration `migrations/0001_device_auth.sql` defines `owners`, `enrollments`,
`devices`, `auth_challenges`, and bounded `audit_events`. Codes, nonces, and
Cloudflare Access subjects are stored only as SHA-256 hashes. Unique and
conditional updates protect single-use enrollment, challenges, active key
thumbprints, and rotation IDs.

Implemented routes:

- public `GET /health`;
- `POST /v1/enrollments` and
  `POST /v1/enrollments/{id}/complete`;
- `POST /v1/auth/challenges` and `POST /v1/auth/token`;
- protected `/v1/capabilities`, `/v1/chat`, `/v1/search`, `/v1/voice`,
  `/v1/devices/me`, self-revoke, and dual-proof key rotation;
- Cloudflare Access-validated owner enrollment inventory/approval/denial and
  exact user-code lookup, plus device inventory/revocation, with exact-origin
  double-submit CSRF; and
- scheduled cleanup of expired requests/challenges and aged bounded audit
  metadata.

Cloudflare Access assertions are cryptographically checked with the issuer's
JWKS, exact issuer, application audience, time, and an active pre-provisioned
owner subject hash. Merely presenting the header is not trusted.

## Configuration (values are not in source)

The example Wrangler file is intentionally non-deployable until the owner
chooses stable hosts and creates the isolated D1 and rate-limit namespaces.
Server-only secrets are:

- `JWT_SIGNING_KEY_CURRENT` and `JWT_SIGNING_KID_CURRENT`;
- optional previous JWT signing key/kid during a bounded rotation overlap;
- `RATE_LIMIT_BUCKET_SECRET`;
- provider/search/voice credentials.

Required non-secret identifiers include the API/service/model selection,
Access issuer/audience, exact owner portal origin, and enrollment verification
URL. The current 72-hour token or its derivation key must never be copied here.

## Local verification

From this directory:

```powershell
npm.cmd test
npm.cmd run check
```

The tests use an in-memory D1 contract double and real Web Crypto keys. They
cover strict P-256 JWK handling, owner-approved/denied/expired enrollment,
concurrent single consumption, challenge expiry/replay, JWT signature/issuer/
audience/time gates, D1 and signing-key outage, CSRF and forged Access
assertions, per-device rate-limit isolation, self/owner revocation, stale
epochs, dual-proof idempotent rotation, and provider calls only after current
device authorization.

These source tests are not deployment, physical-device, 73-hour renewal,
Cloudflare D1 concurrency, Galaxy A17, 8 GB Windows, voice-hearing, or owner
acceptance evidence. The implementation remains un-deployed and unaccepted.

## Rollback

Because this phase is isolated and un-deployed, rollback is removal of only
`services/sarah-full-auth-worker`. A later staging rollback must undeploy only
the full staging Worker and remove only its isolated D1 binding. It must not
change the event Worker or either event artifact.
