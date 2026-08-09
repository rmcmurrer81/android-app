# Sarah Android durable-auth client layer

Status: `STAGED_NOT_CONNECTED`

Date: 2026-08-09

This is the second isolated Android foundation for Sarah's future durable
full-version authentication. It does not change the event candidate, normal
Android UI/runtime, `ModelClient`, request JSON, Gradle configuration,
workflows, APK, or any deployed Worker route. It is not owner-accepted and is
not proof of a physical-device enrollment.

## Added boundary

- `durableauth/DurableDeviceAuthClientCore.java` is a host-testable state machine and
  injectable HTTP orchestration boundary.
- `durableauth/AndroidDurableDeviceCredential.java` adapts the earlier versioned,
  non-exportable AndroidKeyStore P-256 credential manager. Fresh key creation
  is an explicit factory operation. Inspecting a bound device never creates a
  replacement key.
- `durableauth/AndroidDurableDeviceAuthHttpsTransport.java` is an HTTPS-only transport
  using Android's platform trust store. It disables redirects and has no
  logging or persistence interface.

The core matches the staged full-auth Worker contract:

1. `POST /v1/enrollments`
2. `POST /v1/enrollments/{id}/complete`
3. `POST /v1/auth/challenges` with purpose `session`
4. `POST /v1/auth/token`
5. one explicitly supplied protected `/v1/...` request with the current
   memory-only access token

Enrollment keeps its device code, challenge, and cached enrollment proof only
in private erasable memory. The owner-facing view exposes only the user code,
verification URL, expiry, and polling interval. The proof may be reused while
the same ten-minute enrollment remains `pending_owner`, because the Worker
intentionally returns HTTP 202 until the owner approves it. All pending secret
material is erased on success, expiry, denial, re-enrollment, or close.

Each session uses a fresh two-minute, one-use challenge. Before signing, the
client verifies the exact device ID, purpose, API origin, key version, expiry,
HTTP server date, maximum clock skew, and minimum remaining lifetime. A bounded
memory-only replay set rejects a repeated challenge ID and nonce before a
second signature or token exchange.

The ten-minute access bearer is stored only as a private `char[]`. There is no
public getter, serialization method, file/database/preferences write, log
statement, `BuildConfig` secret, or event bearer reference. It is erased on
expiry, HTTP 401/403, close, credential loss, rotation, revocation, or required
re-enrollment. A protected request that receives 401 or 403 is never silently
replayed, preventing duplicate travel/provider actions.

## Fail-closed states

- `UNENROLLED`
- `ENROLLMENT_PENDING_OWNER`
- `ENROLLED_NEEDS_SESSION`
- `SESSION_READY`
- `SESSION_RENEWAL_REQUIRED`
- `KEY_MISSING_REENROLL_REQUIRED`
- `REENROLLMENT_REQUIRED`
- `ROTATION_REQUIRED`
- `REVOKED`
- `CLOSED`

A missing AndroidKeyStore alias for a durable device enters
`KEY_MISSING_REENROLL_REQUIRED`; the client cannot regenerate it. HTTP 401
during an ordinary protected call clears the bearer and requires an explicit
renewal. HTTP 401 during device-proof renewal requires re-enrollment. HTTP 403
clears the bearer and moves to revoked, rotation-required, or explicit
re-enrollment according to the exact server code. Lifecycle hooks exist for
revocation, rotation, key loss, and re-enrollment, but actual owner UI and
dual-signature rotation remain deliberately unconnected.

## Verification

`DurableDeviceAuthClientCoreTest.java` runs under a host JDK with an injected
clock, transport, and credential. It covers exact enrollment/session requests,
owner-safe enrollment views, one-use challenge replay rejection, expired and
overlong challenges, HTTP-date clock skew, ten-minute token bounds, memory-only
bearer use and erasure, no automatic 401 replay, 403 revocation, missing-key
state, and rotation/re-enrollment hooks.

`test_durable_device_auth_client_layer.py` proves the staged classes are not
referenced by any existing production Java source and statically rejects
persistence, event credentials, `BuildConfig` secrets, logging, custom TLS
trust, hostname bypasses, and redirect following.

Physical AndroidKeyStore, TLS, enrollment, renewal, revocation, process-death,
clock-change, and reinstall acceptance remain pending. Connecting this layer
later requires a separate reviewed change, encrypted non-secret device-binding
storage, owner enrollment UI, deployed stable full API origin, Worker
acceptance, APK inspection, and physical-device tests.
