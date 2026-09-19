# Sarah Windows artifact lineage — 2026-08-08

Status: `OWNER_REJECTED_LEGACY_ARTIFACT_PRESERVED_DO_NOT_INSTALL`

## What Robert opened

GitHub Actions run `31280919776` ran the historical workflow
`.github/workflows/sarah-2-2-ci.yml`. Its Windows job created the artifact then
named `Sarah-Morgan-Windows-2.2`.

That workflow built `windows-companion/sarah_windows.py` directly. It did not
build through `windows-companion/sarah_event_ready.py`, did not pass the
approved portrait to PyInstaller with `--add-data`, and did not bundle the
gated event configuration used by the current online-owner candidate.

The direct program resolves the approved portrait at runtime and deliberately
falls back to `_draw_vector_avatar()` when the asset is absent. Because the
legacy PyInstaller command omitted the portrait, the polygon avatar Robert saw
was the expected result of that package, not evidence that Robert rejected the
approved portrait.

The same legacy package did not carry the current protected online/ElevenLabs
route identity. Robert's screenshot records that its Windows offline speech
route was unavailable. That proves the opened package was silent; it does not
constitute an ElevenLabs hearing test of the current owner candidate.

## Durable artifact boundary

- Sarah 2.2 remains reproducible historical evidence. Its workflow is manual
  only and every future artifact and executable name contains
  `LEGACY-EVIDENCE-DO-NOT-INSTALL`.
- Automatic 2.5 pull-request validation remains credential-free engineering
  evidence. Its Windows artifact contains
  `CURRENT-ENGINEERING-EVIDENCE-DO-NOT-INSTALL`; it is not offered as the live
  voice owner test.
- The one current Windows R3 owner-test artifact is produced only by the gated
  `.github/workflows/sarah-2.5-online-judge-build.yml` route after its protected
  backend smoke test:

  `Sarah-2.5-R3-CURRENT-OWNER-TEST-Windows-ElevenLabs-Candidate`

  Inside it, the installer is:

  `SarahMorganTravelOS-2.5-R3-CURRENT-OWNER-TEST-Setup.exe`

  Keep its adjacent SHA-256 and manifest with the installer. The manifest must
  still report pending physical owner acceptance. Bundled public route/model/
  voice identity and a passed backend smoke test do not replace Robert's
  post-install activation, real speaker test, or final approval.

## Preservation and rollback

No prior workflow run or artifact is deleted or overwritten. Run
`31280919776` remains historical evidence. To roll back only this naming and
surfacing boundary, restore the three workflow files and two documentation
references from the preceding revision; do not change Sarah's portrait,
voice, memories, owner data, or application source.
