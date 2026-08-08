# Sarah Windows portrait assets

`sarah_adult_portrait_r2.png` is an inactive, replaceable owner-review
candidate for the Sarah Morgan Windows companion. It does not define Sarah's
identity, age record, memories, personality, voice, or travel data. The
existing code-drawn avatar remains the rollback path until the asset is
connected, packaged, tested, and accepted by Robert.

## Candidate evidence

- File: `sarah_adult_portrait_r2.png`
- Bytes: `1784755`
- SHA-256: `a675dcadc4be7e1bc949b40ee19cf362df6ba38870fecd49977623bf5a746ebe`
- Generator path: Codex built-in image generation
- Status: `INACTIVE_OWNER_REVIEW_CANDIDATE_NOT_APPROVED`

The bounded runtime derivative is
`sarah_adult_portrait_r2_runtime_512.png` (`294551` bytes, SHA-256
`3f5801ddcb99ba5e20a2f1a62d1bca8415210b1545c9b05ca1115b123a7b5b4f`).
It is a 512-by-512 Lanczos downscale of the master, saved as an optimized RGB
PNG. The master remains unchanged for review and later derivatives.

The R2 Windows source now resolves and validates only this bounded derivative,
loads it once into the 180-by-175 corner presence, and retains the original
code-drawn avatar as the missing/corrupt/drift fallback. Its outline pulse is
cosmetic only; it is not lip-sync or full character animation. The master is
not bundled. A new Windows build and Robert's visual acceptance remain pending.

## Generation brief

Create a polished, clearly adult woman character portrait suitable for a
friendly animated desktop travel companion. Sarah appears about 30 years old,
warm, intelligent, calm, capable, and approachable. Use natural adult facial
proportions, hazel-brown eyes, medium warm skin, shoulder-length softly wavy
dark-brown hair, a teal travel blazer, a cream top, and a navy-to-teal app
backdrop. Keep the front-facing eyes and mouth unobstructed for later bounded
UI animation. Avoid text, logos, watermarks, childlike features, exaggerated
anime proportions, a real-person likeness, or additional people.

## Integration boundary

Any later integration must:

- load this asset through a packaged-resource resolver, not a developer-only
  absolute path;
- retain a clean fallback when the asset cannot be loaded;
- avoid claiming that a static portrait is a fully animated character;
- keep animation cosmetic and separate from Sarah's conversation and state;
- pass the Windows installer self-test on the 8 GB, no-GPU target laptop; and
- remain prerelease until Robert visually accepts it.
