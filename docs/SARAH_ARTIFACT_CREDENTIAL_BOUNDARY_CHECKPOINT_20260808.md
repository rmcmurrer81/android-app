# Sarah artifact credential boundary checkpoint — 2026-08-08

Status: **SOURCE AND STATIC/LOCAL TEST PASS; NEW APK/EXE AND PHYSICAL ACTIVATION
ACCEPTANCE PENDING**

## Corrected failure

The prior active build paths could place a revocable Worker bearer in Android
`BuildConfig` and in the Windows PyInstaller data bundle. It was not a provider
account key, but it was still a reusable credential recoverable from either
client artifact. Several older direct-provider, voice, travel-commerce, and
concierge build fields could also inherit CI secrets.

## Current boundary

- Every credential-compatible Android `BuildConfig` field is a literal empty
  string. Build-runner environment values cannot populate it.
- Provider keys remain server-side. Non-secret Worker URL, deployment/model
  identity, approved voice ID/model ID, and public affiliate identifiers may be
  present in an artifact.
- Android exposes one ordinary **Activate online mind** action only to the exact
  active confirmed owner. It accepts one HTTPS Sarah backend address and one
  32–256 character URL-safe revocable Sarah access code. It explicitly rejects
  provider-key setup.
- Android encrypts both runtime values with AES-GCM under Android Keystore,
  excludes all shared preferences from backup/device transfer, invalidates the
  derived capability cache, and requires a fresh authenticated capability
  check. Disconnect removes only those encrypted route values.
- Sarah text, protected current-source lookup, and protected ElevenLabs voice
  share that one owner-activated backend root and access code. Direct Android
  OpenAI and direct ElevenLabs credentials remain disabled.
- All 13 active workflow files are covered by the artifact-credential scan.
  Current artifact build steps receive no reusable credential. All six active
  legacy 2.2 workflows have no job-level credential environment; only their
  exact live ElevenLabs/Tavily validation steps retain narrow step-scoped
  secrets. The online-judge workflow may still use deployment/provider secrets
  only inside bounded server-side deployment and smoke-test steps.
- The final-release Android filename and metadata gate are now source-derived
  from `app/build.gradle` and fail closed unless the source is exactly version
  code 26 / `2.5-r2-owner-repair`; the built variant must exactly match it.
- The Windows event bundle contains URL/model/voice identity only. Its self-test
  fails if a token, API-key, password, or secret field is added.
- Windows **Connection** performs post-install owner activation. Runtime secret
  values are protected with current-Windows-account DPAPI; an older plaintext
  runtime value is migrated on first successful read. A non-Windows source-test
  fallback uses a per-root AES-GCM key and is not the Windows release path.
- Untrusted Android screenshot/photo input is copied through one bounded
  provider-stream read into an fsynced, read-only, app-private snapshot.
  Pixel decoding uses only that exact snapshot; resolver-approved JPEG/PNG/WebP
  MIME must equal `BitmapFactory`'s decoded MIME, source/decode pixel limits are
  checked before full decode, and cleanup failures surface the exact residual.
- Owner-global/unscoped agentic writes (wish, fare watch, destination focus,
  flexible dates, journey plan, and mobility watch) require the exact active
  confirmed-owner lease before any action branch. A guest gets a truthful
  no-write failed receipt and cannot schedule those jobs. Fare/mobility watch
  receipts remain provisional until a final exact-owner recheck and periodic
  scheduler acceptance; revocation or rejection is recorded as pending.
- Event-monitor scheduler control is independently lease-bound: a guest may
  change only a profile-scoped row, while global cancel/ensure/run operations,
  existing-monitor rescheduling, and booking-import refreshes require the exact
  active confirmed owner at the scheduler boundary.
- Main-screen startup/connectivity scheduling, Settings saves, and Travel
  Notebook wish/trip writes now recheck the exact active confirmed-owner lease
  at each global write and scheduler boundary. A profile switch during an open
  dialog fails closed or preserves only the already-completed write with a
  truthful partial-result message.
- Event and proactive Android jobs reject missing/legacy zero schedule tokens,
  permit only one exact active generation, match cancellation by job ID plus
  nonzero token, and carry a confirmed-owner lease through network, receipt,
  store, and notification boundaries. Legacy tokenless periodic jobs are
  replaced rather than silently accepted.
- Android offline reply callbacks use a monotonic utterance sequence instead of
  wall-clock milliseconds, so two rapid `QUEUE_FLUSH` calls cannot share a
  callback key.

No existing artifact was deleted or overwritten. Previously built binaries are
preserved evidence and must not be described as repaired. Only a fresh build
from this source can satisfy the new artifact boundary.

## Automated evidence

- `test_artifact_credential_boundary.py`: 9/9 pass.
- `validate_package.py`: `STATIC_PACKAGE_VALIDATION_PASS_R2`.
- `test_runtime_privacy_contracts.py`: 20/20 pass.
- `test_event_trip_store_contract.py`: 8/8 pass.
- Pure Java `AgenticTravelPlannerTest`, including the six-action owner-global
  classification and truthful rejection contract: pass on JDK 17.
- Windows companion suite, including real DPAPI round-trip and legacy migration
  on this Windows host: 69/69 pass after the final suite rerun; packaged live-
  avatar self-test also reports `SARAH_EVENT_READY_SELF_TEST_OK`.
- All 13 active workflow YAML files parse; `git diff --check` reports no errors
  (only existing Windows line-ending conversion warnings).

These tests prove source/build isolation and local crypto behavior. They do not
prove that the deployed Worker accepts an owner-entered code, that an APK
upgrade succeeds on the Galaxy A17, or that the Windows owner can complete the
activation. Those remain explicit physical acceptance gates.

## Required fresh-build acceptance

1. Build APK and EXE through an active workflow and inspect generated Android
   `BuildConfig`, decompressed APK/dex, Windows bundled JSON, and EXE strings.
2. Confirm no repository app token or provider credential is present.
3. Install without an access code and prove online text/search/voice fail closed
   while offline Sarah remains available.
4. As the exact confirmed owner, enter the Worker URL and access code; prove the
   authenticated `/capabilities`, chat, search, and approved voice routes.
5. Enter a wrong code and prove it is rejected without creating a ready cache.
6. Disconnect and prove only the installed activation is removed.

## Rollback

Rollback must revert only the credential-boundary change as one reviewed commit;
do not reset the shared dirty worktree or overwrite the concurrent R2 repairs.
The safe operational rollback before code reversion is to disconnect the
post-install route, which leaves Sarah honestly offline. Restoring artifact-
embedded credentials is not an approved rollback because it recreates the
disclosed failure class.
