# Sarah durable-auth frozen checkpoint — 2026-08-09

Status: `FROZEN_ENGINEERING_EVIDENCE_NOT_CONNECTED_NOT_DEPLOYED`

The owner has stopped all Sarah Morgan development and asked that her current
files be preserved on GitHub. This checkpoint freezes the exact in-progress
durable-auth source without representing it as a working full release.

## Event version preserved separately

- Preferred complete event pair: Actions run `31311731985`, source
  `04806eeb13c8c467456a54e8e1a741b49f366176`.
- Its Android and Windows artifact hashes and exact 72-hour Worker expiry are
  recorded in `README_EVENT_CANDIDATE.md`.
- Source-budget commit `bf2aae9844276c54fffbfe1a184a4be18d4d0dff`
  keeps ordinary turns at `15000/11500 ms` and explicit current-source turns
  at `25000/18000 ms`.
- Protected ElevenLabs voice is currently blocked by account quota exhaustion:
  allowance `10000`, `0` credits remaining, `2` required by the diagnostic
  request. Do not rerun, bypass the voice gate, or claim premium voice is
  currently available until usable credits renew or are added.
- Event/workflow freeze head before this snapshot is
  `a0e6c05c0dd4b47587573be5f2df34a96c836e81`. It was pushed with a
  skip-CI correction and did not create another Worker.

## Durable-auth source truth at freeze

The checked-in durable-auth directories are isolated source foundations only:

- `services/sarah-full-auth-foundation/`
- `services/sarah-full-auth-worker/`
- Android durable protocol, credential, transport, client-core, docs, and tests
- Windows CNG credential foundation, docs, and tests

They are not connected to Sarah's normal Android or Windows startup, chat,
search, voice, UI, or event routes. They have not been deployed to Cloudflare,
built into an accepted APK/EXE, physically tested, owner-enrolled, subjected to
a 73-hour renewal test, or owner-accepted.

Exact known test truth after the owner pause and preservation rerun:

- standalone foundation Node tests: `18/18` passed;
- shared Python and Java P-256 protocol vectors: passed;
- Windows CNG source/static tests remained present, but the bounded current
  Windows run passed `4/5`; live creation of its unique test key failed closed
  with `CNG 0x80070002`. This snapshot does not claim a current live CNG pass;
- full-auth Worker check: `5/20` passed and `15/20` failed after the interrupted
  contract-integration edit. Most enrollment-dependent flows returned HTTP
  503 instead of 201, and one signing-error vocabulary expectation differed;
- Android durable foundation/client static boundaries: `11/11` passed. Earlier
  host Java protocol/client compilation and execution also passed before the
  pause, but Android SDK/physical-device compilation remains unproven.

Additional unresolved design conflicts remain between the standalone
foundation and Worker/new-Android wire formats, D1 migrations, error names,
rotation replay history, owner bootstrap/portal flow, verification-origin
binding, and client persistence/lifecycle integration. See the Kira/System
handoff for the full audit. No credential value or deployable owner bootstrap
is included here.

## Resume boundary

If the owner later reopens Sarah work, start from a fresh branch and first
make one canonical protocol/schema/migration pass. Require Worker `20/20`,
Android durable tests `11/11`, cross-language vectors, artifact secret scans,
isolated staging, and physical Android/Windows acceptance before describing a
durable full version as usable. Do not change or retire either preserved event
Worker merely because this draft exists.
