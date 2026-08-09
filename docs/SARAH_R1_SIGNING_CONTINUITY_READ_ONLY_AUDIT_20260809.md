# Sarah R1 signing-continuity read-only audit

Audit time: `2026-08-09T07:32:23.4122550Z`

Status: `EXACT_R1_PRIVATE_SIGNING_KEY_NOT_FOUND_IN_INSPECTED_LOCATIONS`

This audit is read-only with respect to signing identity. It did not generate,
replace, import, export, or use a private signing key. The populated R1 app must
not be uninstalled to work around this result.

## Exact public R1 evidence preserved

- GitHub Actions run: `31243145369`
- Job: `93067275085`
- Commit: `f52978be5138bbc4c25c623a8b56d13c8ecd9bfe`
- APK artifact ID: `9017645871`
- Artifact ZIP SHA-256:
  `4a35dc19e318354ec62d9ca838f92e3d7d537cc032e0b43359e4f2e4191fcc9a`
- Extracted APK SHA-256:
  `be67ceb0adf6d920532bb46a8b79a2be4b6c98dca20a5765f33a70489204b314`
- Recorded installed-R1 signer-certificate SHA-256:
  `d49b6dea8f8ddb332c170abd2d79240de011d302bdbec8a732f783910134c63c`

The surviving APK and certificate digest preserve public verification evidence.
They do not contain the private signing key and cannot be used to reconstruct
it.

## GitHub Actions cache result

The workflow attempted to preserve:

```text
path: ~/.android/debug.keystore
key: sarah-morgan-debug-signing-v1
```

The exact R1 job reported both:

```text
Cache not found for input keys: sarah-morgan-debug-signing-v1
Path Validation Error: Path(s) specified in the action for caching do(es) not exist, hence no cache is being saved.
```

This was not a later branch-scope regression. The first inspected successful
main-branch build after the cache step was added (run `31047418699`, job
`92446253742`) and the latest inspected successful main-branch build (run
`31127231282`, job `92702977226`) reported the same miss followed by the same
missing-path warning. The cache step therefore never captured the signer used
by these APK builds.

The repository's exact GitHub Actions cache-key query on 2026-08-09 returned:

```json
{"total_count": 0, "entries": []}
```

The repository default branch is `main`; no exact cache entry exists on any
currently returned ref.

## Other inspected locations

- `C:\Users\robmc\.android\debug.keystore` is absent, and the local
  `C:\Users\robmc\.android` directory is absent.
- The complete reachable Git object inventory contains no path or object named
  as a `.keystore`, `.jks`, `.p12`, or private signing-key file.
- The current source tree has no Android signing configuration pointing at a
  separately preserved keystore.
- A targeted read-only inventory of Sarah archives in Robert's Downloads
  folder found no `.keystore`, `.jks`, `.p12`, signing-key, or APK entry.
- Preserved GitHub APK artifacts contain the signed APK, not a private
  keystore. The R1 artifact remains available and its hashes match the public
  checkpoint above.

No broad full-disk scan, cache deletion, device mutation, or key generation was
performed.

## Conclusion and boundary

The exact R1 signing identity appears not to have been preserved in the
repository, its current Actions caches, the inspected workflow artifacts, the
inspected Git history, the expected local Android path, or the targeted Sarah
archives. It can still be restored only if an uninspected owner backup or other
external copy contains the original private keystore whose certificate matches
the recorded R1 digest.

Until that exact key is found and independently verified, Android R3 remains
`MIGRATION_BLOCKED_SIGNING_IDENTITY_UNAVAILABLE`. CI must continue to fail
closed before APK/EXE release. Do not create or upload a differently signed
replacement and do not uninstall the populated R1 app.

If the original key cannot be recovered, a separately reviewed, owner-approved
export/import migration bridge and a deliberate new signing identity would be
required. Neither is authorized or implemented by this audit.

## Rollback

This is documentation-only. Removing this audit file reverts the repository
change; no runtime, app, workflow, model, secret, device, or signing state was
changed.
