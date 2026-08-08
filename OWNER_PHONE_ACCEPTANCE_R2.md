# Sarah owner-phone acceptance R2

Date: 2026-08-08

Status: source repair candidate only. No replacement APK or EXE has been
created, installed, uploaded, or published from these changes.

## Owner-observed R1 failures addressed in source

- The default/duplicate `Phone owner` identity is migrated into the confirmed
  owner without inventing age 18. Unknown age remains family-safe.
- Every assistant turn has an application-owned route, a visible human label,
  and database persistence. Cross-device record formats remain preserved for a
  future reviewed transfer path; R2 network sync itself is disabled.
- The explicit identity and mode Turing prompts are answered by application
  truth after the actual online, offline, or fallback route is known. Merely
  having validated internet and a configured URL no longer displays “online
  ready”; only a successful authenticated reply proves that state.
- Automatic mode permits one short retry only after a failed first connected
  attempt. Both attempts share a monotonic, Future-enforced 14.25-second
  network-wait ceiling within the 15-second Android/Windows limit; the exact
  timed-out connection is disconnected,
  then Sarah continues locally with a visible fallback disclosure and tries
  the connected route again on the next ordinary turn.
- Chat, photo sanitization, background research, and bounded network attempts
  use separate executors. Startup research is deferred; a foreground owner
  turn interrupts exact running research, whose FAILED receipt remains pending
  for a later bounded retry instead of competing with the reply.
- Background destination/event research is shown and persisted as enabled only
  when the active profile is the owner, memory permission exists, validated
  internet and the protected source route are available, public research is
  on, and conversation mode is not local-only. Otherwise Settings clears and
  disables the control with the exact setup or permission reason.
- Fare and mobility background monitoring defaults off at Settings, startup,
  action execution, and worker boundaries. An explicit natural-language watch
  can still be saved, but it remains visibly pending and unscheduled until the
  separate monitoring opt-in is enabled.
- Approximate areas now record whether they were device-resolved, entered by
  the owner, or inherited from a legacy record. A waiting near-me turn survives
  Activity recreation without being reassigned to a different profile.
- Externally shared booking text is rejected above 16,384 characters or 32,768
  UTF-8 bytes. Sarah displays the complete bounded text for owner review and
  does not persist or schedule it until the owner chooses Import for review.
- Profile changes stop progressive and local speech, advance the speaker
  generation, suppress old-profile fallback/UI, and append a truthful
  cancellation receipt. Photo callbacks require both the same generation and
  exact active person before attaching their cleaned derivative.
- Canned nonanswers and unsupported promises such as "I'll get to work" or
  "I'll be back with a summary" are removed when no durable job exists.
- Destination discussion does not silently become a saved trip, wish, or
  monitoring watch. A watch is created only after an explicit request and only
  when its real provider is configured.
- Android can request one coarse current-area sample, records only the resolved
  city/area for the active profile, expires it after 15 minutes, and never
  stores raw coordinates or overwrites hometown.
- Insets account for status bars, display cutouts, navigation, and the keyboard
  so the composer and settings remain reachable on the Galaxy A17.
- The normal connected search now sends current message, verified approximate
  area when relevant, recent user turns, and confirmed trip destinations as a
  bounded server-side query. Tavily credentials remain only in the Worker.
  Exact HTTPS sources can be opened from the reply; a failed lookup is routed
  as unavailable instead of being mislabeled as a verified public-source turn.
- Discovery refresh is disabled until its real opt-in, memory, profile,
  destination/area, network, and source preconditions pass. The UI says a
  request was saved only when Android JobScheduler accepts it.
- Travel tools are collapsed into expandable categories, “Add dates” is a
  direct action, Settings uses named owner groups, and Notebook, Discover,
  Connections, Sync, Explorer, and the demo surface now apply safe-area
  handling. Physical Samsung/font-size review is still required.
- Voice receipts are append-only and turn-bound. They distinguish request,
  synthesis, playback, interruption, supersession, actual route, and fallback;
  onboarding distinguishes configured ElevenLabs from phone fallback.
- Device network synchronization is visibly disabled in R2. The preserved LAN
  prototype exposed its bearer/key material over cleartext and is not accepted
  until TLS or authenticated key agreement and bounded pairing are proven.
  Build version and short source commit are visible in Settings.
- Windows discoveries use the real nine-column schema; routes survive sync;
  unknown Android age is not promoted to adult; current area is distinct from
  hometown; and failed ElevenLabs synthesis falls back to Windows speech.
  Windows speech is now generation-bound and owner-interruptible: a newer turn,
  profile-archive restore, exit, or **Stop voice** terminates only Sarah's exact
  active child and prevents obsolete queued speech from playing.

## Bounded adaptive help

Background destination knowledge remains opt-in and requires validated
internet, a configured backend, owner profile, memory consent, and a real
destination or interest. One run is capped at two knowledge packs and four
discoveries. Power Rangers plus New Zealand can form a source-backed research
query, but the application may not claim that it found or downloaded anything
until a connected source actually returns and the result is persisted.

Offline calm choices remain available for takeoff and other ordinary travel
stress. They can offer breathing, conversation, noticing games, or trivia
without diagnosing the traveler or claiming live vehicle awareness.

## Stay22 truth

Android contains a user-initiated, rate-bounded Stay22 Direct keyless demo
search and pure request-policy tests. It sends entered destination, dates,
traveler count, and room count; results are temporary and are never called a
booking. The R2 repair did not perform a live Stay22 request. The candidate build
still leaves optional `SARAH_STAY22_BACKEND_URL` and token empty, so no
production Stay22 backend is claimed.

## Verification at this checkpoint

- Protected Worker tests: 17 passed.
- Windows tests: 57 passed.
- Windows source self-test: `SARAH_EVENT_READY_SELF_TEST_OK`.
- Android static package validation: `STATIC_PACKAGE_VALIDATION_PASS_R2`.
- Workflow YAML parse: passed.
- `git diff --check`: passed; only line-ending notices were emitted.

This computer has no Java/JDK or Gradle wrapper. The first hosted run for
commit `29b655b584cf10acce60af5cab0f0a55a17724e4` supplied Java 17 and Gradle
8.13 and exposed two Android blockers: the pure-Java test compared lowercase
`offline` to the human label beginning `Offline`, and the app used AndroidX
Media3 while `android.useAndroidX` was still false. The next source revision
changes only that test to require the exact `Offline mind ready` prefix,
enables AndroidX, and adds the AndroidX requirement to static validation.

Still pending are a populated-install migration fixture with collision cases,
fresh-user run, physical Galaxy A17 online/offline/reconnect sequence, complete
exact-mode transcript artifacts, physical coarse-location permission/denial/
stale-area cases, human Google OAuth configuration and a real Gmail read test,
live Stay22 path, ten-message keyboard/inset/font-size run, owner hearing, and
Windows operation on the 8 GB no-GPU laptop. Android's
approved cloud voice now uses progressive one-shot playback and lifecycle
cancellation, but first-audio latency remains pending physical A17 measurement.
The preserved R1 APK/EXE remain the only physically owner-tested artifacts.
The first hosted R2 run built and self-tested a separate Windows installer,
but it is not owner accepted. No R2 APK was produced.

## First hosted R2 evidence

- Source extraction run `31263116561`: passed. Artifact digest:
  `sha256:3752b98b08f164cde65f00b67aeccda66a016dbfec12dc8aa83c421ed348900d`.
- Observable validation run `31263116577`: Windows passed and uploaded
  artifact `9023364837`, digest
  `sha256:0e0304bb3be93bcdd15b3429d6ea3aad964e4b36eb31ebf1e0004a94e10815be`;
  Android stopped before its APK build on the two exact blockers above.
- Online-judge run `31263114954`: the unique Worker deployed and the live
  Workers AI reply passed using `@cf/google/gemma-4-26b-a4b-it`. The run then
  failed closed because `SARAH_TAVILY_API_KEY` was absent. No current-source
  result was invented, no candidate APK or event Windows installer was
  uploaded, and the failed run's unique Worker was retired.

The missing protected current-source credential is a deployment/setup blocker,
not a Workers AI conversational failure. Do not weaken the current-source gate
or claim live research merely to produce an artifact.

## Second hosted R2 evidence

- Source extraction run `31263579454`: passed. Artifact `9023467470`, digest
  `sha256:f39d9da714978db2bb26abbc23c185273da64a758cbfdd5aebe977be56680b2d`.
- Observable validation run `31263579464`: the Windows job passed and uploaded
  artifact `9023483806`, digest
  `sha256:3ab3b03abdeb173a076548a8507a8006b028f51853a514708a737dcd56e546da`.
  Android source extraction and the pure-Java policy suite passed, then real
  Gradle compilation exposed one new exact blocker: `ProactiveDiscoveryButton`
  extended AppCompat although this deliberately small package declares no
  AppCompat dependency. The resulting missing-superclass messages were
  cascading compile errors, not separate runtime defects.
- The narrow repair uses the Android platform `Button`, matching the existing
  upgrade generator and avoiding a large unused AppCompat dependency. Static
  validation now fails if this source reintroduces `androidx.appcompat`.

The repaired source still requires a fresh hosted Gradle compile before any R2
APK is claimed. The second Windows artifact is superseded by that source repair
and remains engineering evidence only.

The Android candidate is an in-place upgrade only: it retains
`com.kiraworld.sarahtravel`. CI must have an exact R1 signing-cache hit, require
the recorded R1 APK and signer SHA-256 checkpoints, compare the R2 signer to R1,
and block upload on mismatch. No source-only result authorizes uninstalling R1.
Every workflow run deploys a uniquely named candidate mind endpoint so a later
run cannot silently mutate the endpoint embedded in an older candidate APK.
The candidate manifest inventories its exact Worker name/URL/deployment and
retirement command. Token rotation or another deploy does not revoke older
unique endpoints; rejected/superseded endpoints must be retired explicitly.
If the installed R1 signer cannot be recovered and matched, Android R2 remains
`MIGRATION_BLOCKED`; an independently reviewed export/import bridge is required
before replacement, and that bridge is not claimed by this checkpoint.

## Lossless populated-profile collision repair

The three known source-level collision losses are now repaired. Exact old and
confirmed payloads for Traveler Needs and Hotel Search are preserved in an
encrypted, append-only, deterministic migration archive before the active
confirmed value is merged or retained. Loyalty keeps every distinct full
record payload—even when program/member identity fields collide—and also
archives the exact pre-merge source and target payloads. Placeholder data is
removed only after the confirmed destination and required archive record are
synchronously reread and verified. Crash retries reuse deterministic record
IDs instead of manufacturing a new history entry.

Owner-memory merges now preserve exact placeholder source text and original
creation time in a dedicated provenance table before a UNIQUE collision can
ignore the active-row insert. Legacy and placeholder discovery rows use the
same rowwise archive/verify/delete move as other profile migrations; a
differing same-URL payload is archived before its source row may be deleted.

The hosted route must also pass an exact generated solid-red JPEG vision smoke
through the deployed provider/model. This proves only the bounded synthetic
fixture; natural Galaxy A17 photos and owner visual acceptance remain pending.

This closes the known overwrite behavior in source; it does not convert the
populated-install gate into a pass. GitHub Android compilation and a real
Galaxy A17 fixture containing conflicting values in all three stores must
still prove the encrypted archive, restart/resume behavior, owner visibility,
and zero loss before R2 owner acceptance.
