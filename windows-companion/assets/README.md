# Sarah Windows portrait assets

`sarah_adult_portrait_r2.png` is Robert's approved visual source for the Sarah
Morgan Windows companion. It remains replaceable without changing Sarah's
identity, age record, memories, personality, voice, or travel data. The
existing code-drawn avatar remains the missing/corrupt/drift rollback path.

## Candidate evidence

- File: `sarah_adult_portrait_r2.png`
- Bytes: `1784755`
- SHA-256: `a675dcadc4be7e1bc949b40ee19cf362df6ba38870fecd49977623bf5a746ebe`
- Generator path: Codex built-in image generation
- Status: `OWNER_APPROVED_VISUAL_SOURCE_2026-08-08`

The bounded runtime derivative is
`sarah_adult_portrait_r2_runtime_512.png` (`294551` bytes, SHA-256
`3f5801ddcb99ba5e20a2f1a62d1bca8415210b1545c9b05ca1115b123a7b5b4f`).
It is a 512-by-512 Lanczos downscale of the master, saved as an optimized RGB
PNG. The master remains unchanged for review and later derivatives.

The R2 Windows source resolves and validates only this bounded derivative,
loads it once into a 20 FPS CPU-only layered renderer for the 180-by-175 corner
presence, and retains the original code-drawn avatar as the
missing/corrupt/drift fallback. The renderer adds bounded eyelid, gaze, head,
and mouth layers without overwriting either PNG. Mouth opening follows the
locally decoded exact ElevenLabs audio envelope; Windows System.Speech and
decoder failures are labeled as speaking-activity animation, not lip sync.
The master is not bundled. Physical motion, synchronization, and resource-use
acceptance on Robert's 8 GB no-GPU laptop remain pending.

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
- distinguish the implemented bounded live 2D portrait from a 3D body or full
  facial-performance rig;
- keep animation cosmetic and separate from Sarah's conversation and state;
- pass the Windows installer self-test on the 8 GB, no-GPU target laptop; and
- remain prerelease until Robert visually accepts it.
