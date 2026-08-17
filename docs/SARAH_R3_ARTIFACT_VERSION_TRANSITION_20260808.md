# Sarah R3 artifact and version transition — 2026-08-08

Status: `R3_SOURCE_PREPARED_CURRENT_OWNER_TEST_NOT_YET_BUILT_OR_ACCEPTED`

Robert physically rejected the prior R2 Android and Windows artifacts. Those
files and their evidence remain historical; nothing in this transition turns
an R2 binary into a current candidate.

## Exact R3 source identity

- Android application ID remains `com.kiraworld.sarahtravel`.
- Android `versionCode` is `27`.
- Android `versionName` is `2.5-r3-owner-repair` (the debug build adds its
  normal variant suffix).
- `BUILD_VERSION.txt` identifies the same R3 source target.
- The online-judge signing gate still requires the exact preserved R1 signing
  certificate before it will upload an APK. R1 must not be uninstalled merely
  to make a repair install pass.
- The Windows internal payload is `SarahTravelOS-R3-Candidate.exe`; the
  owner-facing online-judge installer has a separate unmistakable name.

## Only current owner-test artifacts

Only `.github/workflows/sarah-2.5-online-judge-build.yml` may upload these:

- `Sarah-2.5-R3-CURRENT-OWNER-TEST-Android-APK`
  - `Sarah-Morgan-2.5-R3-CURRENT-OWNER-TEST.apk`
  - adjacent SHA-256 and manifest
- `Sarah-2.5-R3-CURRENT-OWNER-TEST-Windows-ElevenLabs-Candidate`
  - `SarahMorganTravelOS-2.5-R3-CURRENT-OWNER-TEST-Setup.exe`
  - adjacent SHA-256 and manifest

Both manifests report `repair_revision: R3_OWNER_REPAIR` and
`artifact_status: CURRENT_OWNER_TEST_PENDING_PHYSICAL_ACCEPTANCE`. Passing CI
does not mean the phone or laptop experience passed Robert's acceptance.

## Non-owner artifacts

Pull-request and generic build paths use `ENGINEERING-EVIDENCE-DO-NOT-INSTALL`.
Historical paths use `LEGACY-EVIDENCE-DO-NOT-INSTALL`. They are useful for
compilation and regression evidence only. They must not be installed for the
next owner test or represented as online/ElevenLabs acceptance candidates.

## Rollback

To roll back this source-only transition, restore `BUILD_VERSION.txt`, the
Android Gradle version pair, the R3 workflow identity/name changes, and the R3
Windows installer constants from the preceding revision. Do not change the
application ID, delete the R1/R2 binaries, remove prior manifests, rotate or
expose provider credentials, erase owner data, or uninstall the preserved app.
