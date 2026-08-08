# Sarah Windows live-avatar checkpoint — 2026-08-08

Status: `IMPLEMENTED_AND_AUTOMATED_TESTED_PHYSICAL_OWNER_ACCEPTANCE_PENDING`

This checkpoint is append-only evidence for PR #21. It does not merge,
release, install, or claim physical owner acceptance of the Windows build.

## Owner decision and preserved source

Robert approved the appearance of the existing Sarah portrait and requested a
real continuously animated computer presence: mouth movement with speech,
natural blinks, and restrained eye/head movement—not a slideshow of generated
still images.

The approved files remain byte-for-byte unchanged:

- `windows-companion/assets/sarah_adult_portrait_r2.png`
  - 1,784,755 bytes
  - SHA-256 `a675dcadc4be7e1bc949b40ee19cf362df6ba38870fecd49977623bf5a746ebe`
- `windows-companion/assets/sarah_adult_portrait_r2_runtime_512.png`
  - 294,551 bytes
  - SHA-256 `3f5801ddcb99ba5e20a2f1a62d1bca8415210b1545c9b05ca1115b123a7b5b4f`

The code-drawn avatar remains the missing/corrupt/hash-drift rollback path.

## Exact prior gap

Before this change, `SarahApp._animate_avatar()` redrew the same immutable
`PhotoImage` every 450 ms and changed only the border color/width. It explicitly
stated that this was not lip sync or full character animation. There was no
audio decoding, eyelid state, gaze state, head state, mouth state, or stale
voice-generation binding.

## Implemented boundary

`windows-companion/sarah_live_avatar.py` adds a small offline CPU renderer:

- 20 FPS live frame generation from the exact approved portrait;
- deterministic, naturalized blink intervals with occasional bounded double
  blinks;
- sub-pixel-scale head sway and restrained gaze motion;
- layered eyelid and mouth rendering without altering either portrait file;
- exact local WAV PCM envelope extraction with the Python standard library;
- exact local MP3 envelope extraction with `miniaudio` 1.71;
- 40 ms RMS envelope samples with fast attack and bounded release;
- a ten-minute decode ceiling and fail-open voice behavior;
- generation-bound animation stop, so a newer turn or **Stop voice** cannot
  leave an obsolete mouth moving;
- a separately labeled `speaking_activity_fallback` when the active Windows
  System.Speech route exposes no PCM or compressed-audio decoding fails.

The voice receipt now records decoder route, decode reason, envelope-frame
count, bounded duration, analysis time, and pending physical visual acceptance.
An envelope failure never blocks text or audio playback and is never reported
as decoded-audio lip sync.

The renderer does not create a folder of frames or cycle through still-image
files. Each transient frame is rendered in memory from current time and exact
voice state. It has no GPU or cloud dependency. `miniaudio` is a small local
decoder dependency; Sarah's existing voice provider and playback route are
unchanged.

## Verification

Commands and outcomes:

```text
py -B -m pytest tests -q
69 passed in 6.24s

YAML parse: sarah-2.5-pr-validation.yml and
sarah-2.5-online-judge-build.yml
YAML_OK 2

git diff --check -- windows-companion <two active Sarah workflows>
PASS (line-ending notices only)
```

The focused tests prove:

- silence and real PCM energy produce different mouth envelopes;
- missing audio is not mislabeled as audio lip sync;
- motion and blink schedules are deterministic under an injected clock/seed;
- decoded-envelope mouth motion follows the matching time sample;
- stale generation stop is rejected and exact generation stop succeeds;
- System.Speech motion is labeled activity fallback;
- neutral and expressive frames differ while dimensions stay bounded;
- forty time steps produce continuous distinct in-memory frames; and
- the Windows playback integration binds/clears the exact generation and
  records the real decoder route;
- the corner integration creates a rendered image and reschedules at 50 ms;
  and
- the approved portrait SHA-256 remains unchanged.

Development-computer measurements (not the 8 GB laptop acceptance):

- 240 frames at 180 x 175: mean 4.877 ms, p95 5.325 ms, max 5.699 ms;
- frame budget: 50 ms (20 FPS);
- real local MP3 decoder check: 1.068 seconds of audio, 27 envelope frames,
  1.479 ms decode time, peak normalized envelope 0.946;
- decoder route: `miniaudio_pcm`.

The MP3 used only for that read-only decoder check was an existing 11,717-byte
Gradio package test asset. It was not copied into Sarah, played, or treated as
Sarah's voice.

## Bounded Windows package check

Two preflight failures were retained as implementation truth:

1. The first command stopped immediately because PyInstaller was not installed
   in the development Python environment (`No module named PyInstaller`).
2. After installing the repository-bounded `pyinstaller>=6,<8`, the first
   isolated command used a relative portrait path with a separate `--specpath`;
   PyInstaller correctly failed because that relative data path resolved under
   the isolated spec directory. No executable was emitted by either failure.

The corrected command used the same package inputs with an exact absolute
portrait source and isolated all products under
`build/live-avatar-pyinstaller/`. It passed in 15.2 seconds:

- output: `build/live-avatar-pyinstaller/dist/SarahLiveAvatar-BuildCheck.exe`
- 38,180,225 bytes
- SHA-256 `9a30dddb019de655e6ad4c742cccda499ff4df0199b4f92647bf98a98c2242b2`
- PyInstaller analysis includes `sarah_live_avatar.py`, `miniaudio.py`, and
  `_miniaudio.pyd`.

The isolated executable was then invoked once with `--self-test`. It returned
exit code 0, including the packaged portrait integrity gate and bounded local
self-test path, and left no `SarahLiveAvatar-BuildCheck` process running. The
windowed executable intentionally emitted no console text. This did not launch
Sarah's normal GUI, start onboarding, speak, use a provider, or constitute the
still-pending owner-visible animation test.

This isolated executable is build evidence only. It was not installed,
launched, published, committed as a release artifact, or substituted for an
existing Sarah executable. The local build lacked optional `pystray`,
`playsound3`, and Google client packages; the active CI workflow installs the
complete pinned requirements before its authoritative application and
installer builds.

After the credential-boundary repair, the packaged `--self-test` was extended
to instantiate the exact approved portrait, construct the live renderer,
render different idle and decoded-audio speaking states, prove that their
pixels differ, and stop the exact matching speech generation. A fresh isolated
one-file build passed that stronger self-test with exit code 0 and no process
left behind:

- output: `build/live-avatar-secure-pyinstaller/dist/SarahLiveAvatar-SecureBuildCheck.exe`
- 37,757,080 bytes
- SHA-256 `b7a6895a76da58fb56845272e63c1d5f9e8a6fcbedb34ab9b7180df375c7d311`
- pre-test process count: 0; post-test process count: 0.

This remains isolated build evidence, not an installed release or a physical
owner visual/hearing acceptance.

The final source-set check also stops portrait rendering while the corner
window is hidden and polls every 250 ms for a fast return when Robert shows
Sarah again. This prevents the 20 FPS renderer from consuming CPU for invisible
frames on the 8 GB laptop. A fresh isolated build after that change passed the
same packaged animation self-test and left no process behind:

- output: `build/live-avatar-final-pyinstaller/dist/SarahLiveAvatar-FinalBuildCheck.exe`
- 37,757,725 bytes
- SHA-256 `14a8d54bccd02e5b0e8cdc9c9aa563ab85f4c59fc5115c703330493b7ecb567f`
- self-test exit code: 0; pre-test process count: 0; post-test process count: 0.

## Current source hashes

- `windows-companion/sarah_live_avatar.py`
  `d853ef13dd2db25c53efff8d50df1fcbab0de9ddd2fd63e77c17e128405aebb4`
- `windows-companion/sarah_windows.py`
  `a6893d02f0d94d53f9a89a2f8ad5ae59419f85848e54a5d142419c7ebb44edf1`
- `windows-companion/sarah_event_ready.py`
  `4f121f9d45e0cf3c9b17023a0831f4e52d8353006162641a0d23ba4b9f3e01e0`
- `windows-companion/tests/test_live_avatar.py`
  `f37755ecb31ea33349ee13e2f1b1bbaafb0dcee088c5cf465c75afc2428ead44`
- `windows-companion/requirements.txt`
  `4fbd4e671c5b5ded62fb0eec7ca5f200f1d5bc5ae2dc43f95f502874cdf75ab9`
- `windows-companion/BUILD_WINDOWS.ps1`
  `6f4df401a4629eae1c5cc94cf4e4bd8f6b88a0bd32d3adf0acd8cbd3b0b490a8`

The two active workflow files also contain concurrent Android/booking repairs;
their whole-file hashes are not represented as hashes of this live-avatar work
alone.

## Truth and remaining acceptance

Implemented and test-backed does not mean physically accepted. Still required
on Robert's 8 GB no-GPU laptop:

- launch the newly built candidate through the normal Sarah entry;
- watch several minutes of idle motion for natural blink/head/gaze appearance;
- hear a real ElevenLabs response while checking mouth/audio alignment;
- exercise **Stop voice** and a superseding turn while watching the mouth;
- check the explicit Windows System.Speech fallback label and motion;
- measure idle/speaking CPU and RAM, UI responsiveness, and thermal behavior;
- let Robert decide whether eyelid placement, mouth size, movement strength,
  and 20 FPS feel natural.

Current mouth animation follows audio amplitude; it is not phoneme/viseme-level
facial capture. MCI process start is not proof of the first audible sample, so
physical synchronization may need a small measured offset. This is a bounded
2D portrait, not a 3D body, full facial-performance rig, or proof of biological
emotion/consciousness.

## Rollback

Rollback does not require changing Sarah's portrait, identity, memories,
voice, or travel data:

1. restore `windows-companion/sarah_windows.py`, requirements, build command,
   and the two active workflow command lines from the pre-live-avatar revision;
2. omit `sarah_live_avatar.py` and `tests/test_live_avatar.py` from that rollback
   build; and
3. keep both approved portrait files unchanged.

Even without a source rollback, a missing/corrupt/hash-drift runtime portrait
continues to fail closed to the preserved code-drawn avatar.
