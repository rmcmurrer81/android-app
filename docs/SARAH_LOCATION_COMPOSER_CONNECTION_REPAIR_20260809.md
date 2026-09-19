# Sarah location, composer, and event-connection repair checkpoint — 2026-08-09

Status: `SOURCE_REPAIR_VERIFIED_STATICALLY_PHYSICAL_ACCEPTANCE_PENDING`

Robert explicitly reopened a bounded Sarah repair after rejecting the physical
Galaxy A17 and Windows experience. This checkpoint is newer than, but does not
overwrite, `SARAH_DEVELOPMENT_PAUSED_20260809.md`. The preserved APK, EXE,
Worker, event bearer, durable-auth engineering snapshot, profiles, and owner
data were not changed.

## Reproduced source causes

1. Android used a full-height vertical layout with a fixed 218dp animated
   portrait plus owner tools above the conversation. The IME inset lifted the
   bottom controls, but the fixed upper surface could consume the resized
   Galaxy A17 viewport and leave the composer/Send control behind the Samsung
   keyboard.
2. Android could accept a current-source question while the authenticated
   `/capabilities` startup probe was still running. The route decision read an
   unverified cache at that instant and selected the offline mind even if the
   exact packaged connection proved ready moments later.
3. Android and Windows recognized only a narrow set of current-area wording.
   Natural requests such as “close to me,” “in my area,” and “around me” were
   not consistently bound to an approximate current area.
4. Windows has no phone GPS and normal chat did not ask for a bounded current
   city/ZIP before submitting a nearby request.
5. The Windows R3 window forced 1240x810 with a 960x640 minimum. On a laptop
   using a smaller logical desktop because of Windows scaling/taskbar space,
   the window could extend below the usable screen and hide the composer.
6. The normal Windows **Connect Sarah** action always opened a secret-entry
   prompt even when the 72-hour event artifact already carried its authorized
   per-run capability.
7. The first local-expiry implementation treated a missing or malformed event
   expiry as not expired. That could admit the packaged bearer without proving
   a valid future UTC boundary.
8. Independent endpoint and token lookups could combine different sources. In
   particular, a stale per-user pair saved by the rejected Windows build could
   shadow a valid event pair, or one half of an environment override could be
   combined with another source.

## Implemented bounded correction

- Android hides only the nonessential portrait/workbench/tool group while the
  IME is visible. Conversation history, the exact draft, and Send stay outside
  that collapsible group.
- A current-source draft submitted during the exact capability probe is held
  unchanged on screen and admitted after that shared probe completes. If the
  owner edits the draft or switches profile, it is not auto-sent. A genuine
  failed probe still follows the existing truthful offline fallback.
- Both platforms share expanded, explicit current-area intent phrases without
  treating “local events in Brazil” as phone-location intent. This includes
  Robert's exact wording, “near my location,” and “near my current location.”
- Windows asks for a current city/state/ZIP only when an explicit nearby
  request lacks a fresh area. Cancel keeps the draft. Supplying an area neither
  changes hometown nor enables background nearby monitoring.
- Windows derives its initial size from the logical screen, keeps the window
  within bounded margins, reduces only the approved portrait render size on a
  compact display, and keeps the conversation composer pinned below the
  expanding chat history.
- A 72-hour event build automatically uses its packaged per-run capability.
  Its expiry timestamp is now bundled as non-secret metadata. The packaged
  bearer fails closed unless that timestamp is exact millisecond UTC and is
  strictly in the future; missing, malformed, naive, and boundary-equal values
  are withheld locally. The owner sees an expired/invalid state and a simple
  instruction to install a current authorized event build.
- One atomic resolver now selects the protected endpoint and bearer together:
  a complete explicit process-environment pair, then a valid active packaged
  event pair, then a complete per-user encrypted pair. Half-pairs are never
  mixed. A stale saved code cannot shadow a currently valid event artifact,
  while an invalid/expired event artifact does not erase a complete per-user
  encrypted recovery or future durable credential.
- Model conversation, current-source search, protected voice, connection
  status, and status chips consume that same resolved pair.
- Normal connection status never asks Robert to invent or paste a code. Manual
  code entry remains only behind **Advanced developer recovery** for a
  specifically supplied, revocable recovery credential.

## Credential boundary retained

The event candidate may contain only its already authorized, extractable,
short-lived per-run Worker capability. It must not contain Cloudflare, Tavily,
ElevenLabs, Gmail, or other provider credentials. A future durable build must
not embed a permanent replayable bearer and must not ask Robert to invent a
secret. It must use the existing staged device-bound enrollment design: one
confirmation on an already trusted device followed by automatic reconnect and
renewal. That durable path remains engineering material and is not connected
by this repair.

## Verification performed without external state

- Windows suite excluding the already-known live CNG host gate: `180 passed,
  1 deselected`.
- Android owner-surface/credential/distribution suite: `24 passed`.
- Android R3 static package validator: `STATIC_PACKAGE_VALIDATION_PASS_R3`.
- Android pure-Java `SarahR2PolicyTest`: `SARAH_R2_POLICY_TEST_PASS`.
- The complete Windows test directory otherwise passed, with the pre-existing,
  environment-specific live Microsoft CNG key-creation test still failing
  closed at `CNG 0x80070002`. This repair did not modify durable CNG code.

No APK or EXE was built. No workflow was dispatched. No branch was pushed. No
Worker, Gmail, Tavily, ElevenLabs, location, email, or provider API was called.
No provider/API/access credential was opened, printed, changed, or spent.

## Changed-file SHA-256 inventory

| Project-relative path | SHA-256 |
|---|---|
| `.github/workflows/sarah-2.5-online-judge-build.yml` | `0c7332b9bf000bcfda15d48b3204ed1e7f488cb3e4aa37c2e781aaddfa4caf8c` |
| `README.md` | `6e0173cee9d92e78ee39bfec2edf828c41dccea85609c7a4dba3f398c39448c3` |
| `Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel/CurrentLocationPolicy.java` | `1f63e574174351a0ed00d8be5b51d762d04aea199ca7035a4b37a2a0cfb5d642` |
| `Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel/MainActivity.java` | `f15137cfdd685a19a32d276a8445d1b52f1508e1c5e66c461bbe7a43b8ba86bf` |
| `Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/res/layout/activity_main.xml` | `0b251e484a53fe3040eec786b12870f14fae82b633e675dc7bb99ba4b536f6bc` |
| `Sarah_Morgan_Android_Phone_First_v3/tests/SarahR2PolicyTest.java` | `3168a2cacbb3ba5a3880e0833215218b4c15ba6920131a949e3e986fcee0c4da` |
| `Sarah_Morgan_Android_Phone_First_v3/tests/test_android_presence_surface.py` | `f303e286dd2ff835121a16759ddc5c668682143bc87c069f21164700e21dec50` |
| `windows-companion/sarah_core.py` | `c2c41f880ea300c5cef2960fb840f42b7660fc4c195ef6957697467404884405` |
| `windows-companion/sarah_event_ready.py` | `8d9eb8e74f8bae6eb63b0f5051ec90884e41b8e40272be35672e7cf3b7177bf1` |
| `windows-companion/sarah_windows.py` | `a130a5c6adfae304b6b60d4dd88657eca79b838ff054f5c494192f771cf097c9` |
| `windows-companion/tests/test_event_ready_owner_surface.py` | `acbd8e6eb76ca1ae68b1697c42d3b07eb89da76149deb40e0ed9633f51c10417` |
| `windows-companion/tests/test_online_owner_activation.py` | `d666e8617fec2e2dbe582c8ac893cf1b3315ab2c50f2fae787f96d2f55d9b429` |
| `windows-companion/tests/test_sarah_core.py` | `6db68c348ea2b2b10705ac4588b45552aa05cd4000d63e44d0a8baf8f7ff3faf` |

The checkpoint's own hash is recorded externally after final write because a
file cannot contain its own stable digest.

## Required physical acceptance

Android still needs a newly authorized artifact and Galaxy A17 checks for:

- ten consecutive messages with Samsung keyboard open, including a long place
  name, with draft/composer/Send always visible;
- one current-area request immediately after launch while the protected probe
  is active;
- accepted and denied approximate-location flows;
- Robert's exact “near my location” request wording;
- exact online/offline route label and current-source receipt;
- text and ElevenLabs playback latency on the actual event service.

Windows still needs the exact new executable tested on the 8 GB laptop at its
normal DPI and at 125%/150% scaling. Confirm the composer is visible after
startup and resize, nearby requests ask once for an approximate city/ZIP, the
packaged event capability is used without a secret prompt, expired access gives
the simple refreshed-build instruction, a stale prior saved code cannot shadow
the event pair, and approved voice remains truthful.

## Rollback

Revert only the files listed in this checkpoint's changed-file report. Do not
delete or rewrite the preserved event artifacts, runtime data, profiles,
credentials, prior pause checkpoint, or durable-auth foundation. Reverting the
workflow metadata addition means the next event build will again rely solely
on server-side expiry truth and cannot preflight expiry locally.
