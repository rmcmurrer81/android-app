# Sarah Morgan development pause checkpoint — 2026-08-09

Status: `PAUSED_BY_OWNER_SNAPSHOT_ONLY_NOT_A_RELEASE`

Robert directed that all Sarah Morgan development stop and that the current
files be preserved on GitHub while work moves to Kira, Blender, and Avatar
Builder. This checkpoint records the exact boundary. It authorizes no Sarah
deployment, workflow rerun, Worker creation, provider call, credential change,
feature continuation, or artifact replacement.

## Preserved usable event artifacts

The preferred complete event candidate remains commit
`04806eeb13c8c467456a54e8e1a741b49f366176`, Actions run
`31311731985`:

- Android artifact ID `9037594346`; archive SHA-256
  `177d9216f9a7190e865249c349abeefe643fbe3860333feeefbd751a8b591ef7`;
  extracted APK SHA-256
  `7056313eaab115e91a28f6bed07911caf1161d3e4c945bf44b684435e212d442`.
- Windows artifact ID `9037617491`; archive SHA-256
  `c8460c8c6a11267162fb86f83bfa57544d77e408997ab0c0b7537736c6a4d98a`;
  extracted installer SHA-256
  `1a18e25af652275c516daba1966a2733693dc1c6b656cc28875f214c4846b41e`.

The exact event Worker is `sarah-r3-31311731985-1`; its protected access
expires `2026-08-12T11:49:22.000Z`, or earlier if that exact Worker is
retired. No bearer or provider secret is recorded here.

The later source-budget commit
`bf2aae9844276c54fffbfe1a184a4be18d4d0dff` passed its local static tests but
its two workflow attempts stopped before artifact creation. The exact upstream
cause was ElevenLabs HTTP 401 with zero account credits remaining. Do not call
that an online-text, model, Worker-authentication, or source-search failure.
Do not rerun or bypass the protected voice gate while development is paused.

## Paused durable-auth source

The following newly preserved source is engineering material only:

- `services/sarah-full-auth-foundation/`;
- `services/sarah-full-auth-worker/`;
- Android protocol, AndroidKeyStore credential, and staged client-layer files
  under `Sarah_Morgan_Android_Phone_First_v3/`;
- Windows CNG credential foundation files under `windows-companion/`.

These files are **not deployed, not connected to the event artifacts, not
connected to normal Android or Windows runtime UI, not production-ready, not
physically accepted, and not owner-accepted**. They must not be confused with
the embedded 72-hour event capability.

## Exact preservation verification

Checks run immediately after the owner pause:

- canonical foundation: `18/18` Node tests passed;
- Android staged foundation/client static boundaries: `11/11` Python tests
  passed; their earlier host Java `-Xlint:all -Werror` protocol/client tests
  also passed before the pause;
- durable Worker: `5/20` Node tests passed and `15/20` failed after an
  interrupted contract-integration edit; most enrollment flows returned HTTP
  503 instead of 201 and the error vocabulary was partially changed;
- combined Android/Windows Python gate: `15/16` passed; the live Windows CNG
  creation test failed closed with `CNG 0x80070002` while creating its unique
  test key. No temporary test key was intentionally retained.

The failing Worker and Windows results are preserved honestly. Do not deploy
or connect this durable-auth snapshot. If Robert later reopens Sarah work,
resume by reconciling one canonical schema/route/error contract, restoring the
Worker suite, diagnosing the CNG provider error, re-running secret scans, and
then obtaining independent review before any deployment.

## Rollback

The pause commit is an additive source checkpoint. Reverting it must remove
only the newly added durable-auth engineering files, this checkpoint, and the
current-event README clarification. It must not change the earlier successful
artifacts, their Workers, the event workflow history, or any rejected evidence.
