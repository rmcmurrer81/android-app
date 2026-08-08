# Sarah 2.5 online mind and owner-acceptance candidate build

## Owner-use R2 route truth

R2 treats online/offline state as application-owned evidence for each turn.
The saved values are `ONLINE_WORKERS_AI`, `OFFLINE_LOCAL`,
`ONLINE_FAILED_FELL_BACK_OFFLINE`, `TOOL_RESULT`, `TOOL_UNAVAILABLE`, or
`UNKNOWN_LEGACY`. Android and Windows persist this value instead of guessing
from Sarah's wording. Each message permits one short retry after a failed first
connected attempt; both attempts share a strict 14.25-second network ceiling,
within the 15-second Android/Windows owner-wait limit.
Sarah then answers through the labeled offline path and tries online again on
the next ordinary message. This avoids multi-minute same-turn waits without
silently remaining offline.

No reply may claim that Sarah started background research, monitoring, or a
future summary unless an actual durable runnable job exists. A post-response
grounding guard removes unsupported work promises while preserving any other
natural sentence. Current travel prices, events, and provider results remain
unavailable when their connected source is not configured.

## Current event route

The candidate APK uses a protected Cloudflare Worker with:

```text
SARAH_MODEL_PROVIDER=workers-ai
SARAH_MODEL_ID=@cf/google/gemma-4-26b-a4b-it
```

This route does not require an OpenAI key or purchased OpenAI credits. Cloudflare Workers AI has a bounded free allocation rather than unlimited free use. When the network, Worker, model, or daily allocation is unavailable, Sarah must identify the connected failure and use her local/offline path for that turn. Automatic mode tries the online path again on the next message; it does not require a Settings change.

OpenAI remains an explicit optional rollback provider. It is not the current required hackathon route.

The workflow output is an owner-acceptance candidate, not a judge-ready or
owner-accepted build. Its manifest records live service smoke tests separately
from the still-pending physical Galaxy A17, populated-profile migration,
ten-message keyboard/inset, full route sequence, and hearing/latency gates.

## What the candidate workflow proves

The workflow `.github/workflows/sarah-2.5-online-judge-build.yml`:

1. validates the requested provider and model;
2. requires the owner-revocable `SARAH_MODEL_BACKEND_TOKEN` repository secret;
3. deploys a unique candidate Worker named from the immutable GitHub run ID
   and attempt through Wrangler dotenv secret transport, leaving the R1 Worker
   and every earlier candidate endpoint unchanged;
4. confirms exact source/config/deployment identity, all three route-limit
   bindings, Worker health, and exact-token authentication;
5. receives exactly `ONLINE_READY` from the selected Workers AI model;
6. proves protected `/search`, then proves the real chat route applies the
   Android-style contextual `search_query` and returns exact HTTPS receipts;
7. generates a short real ElevenLabs audio response through the protected `/voice` route without playback;
8. builds the Android APK with the tested Worker URL and the same event token;
9. builds and self-tests a Windows event installer with a CI-generated bundled event configuration;
10. verifies that direct OpenAI, ElevenLabs, and Tavily provider keys were not placed in either client;
11. uploads only clearly labeled candidate artifacts with hashes and manifests.

This workflow does not simulate or waive the 16 physical owner-acceptance
gates. A successful build or service smoke test is not owner acceptance.

Text chat remains independent of voice. If ElevenLabs fails in ordinary use, Android local speech is the fallback and text must remain available.

## Required GitHub Actions secrets

Repository **Settings → Secrets and variables → Actions** needs:

```text
CLOUDFLARE_ACCOUNT_ID
CLOUDFLARE_API_TOKEN
SARAH_ELEVENLABS_API_KEY
SARAH_MODEL_BACKEND_TOKEN
SARAH_TAVILY_API_KEY
```

The two R1 SHA-256 checkpoints are public mandatory migration evidence, not
secrets: R1 APK
`be67ceb0adf6d920532bb46a8b79a2be4b6c98dca20a5765f33a70489204b314`
and signer certificate
`d49b6dea8f8ddb332c170abd2d79240de011d302bdbec8a732f783910134c63c`.
They are bound directly in the candidate workflow. The build stops if the
preserved signing-key cache is not an
exact hit or the built R2 signer differs from the recorded installed R1 signer.
Never uninstall the populated R1 app to bypass that gate.

`CLOUDFLARE_API_TOKEN` must be a restricted deployment token for the intended account, not a browser session value or global API key.

Set `SARAH_MODEL_BACKEND_TOKEN` to a 32-256 character cryptographically random URL-safe value containing only letters, digits, `_`, and `-`. Keep the same repository value for a complete event build. Each run creates a unique Worker endpoint; rotating the repository secret or deploying a new Worker does **not** revoke the token already stored on an older Worker. Retire each rejected or superseded exact Worker explicitly.

Every candidate manifest records an append-only Worker name, URL, deployment
ID, source/config hashes, and exact retirement command. A failed run deletes
only its own newly created Worker when no candidate artifact was preserved.
It never deletes an endpoint referenced by a preserved artifact. The owner
retirement form is:

```text
cd services/sarah-model-proxy
npx wrangler delete --config wrangler.jsonc --name <exact-manifest-worker-name> --force
```

For backward-compatible migration, the judge workflow accepts the older
`SARAH_ELEVENLABS_BACKEND_TOKEN` repository secret only when
`SARAH_MODEL_BACKEND_TOKEN` is absent. The legacy name represented the same
revocable client-to-protected-backend credential; it is not an ElevenLabs API
key. Prefer creating `SARAH_MODEL_BACKEND_TOKEN`, verify a complete judge run,
then retire the legacy secret after all event clients have been rebuilt.

The following are optional:

```text
SARAH_ELEVENLABS_MODEL_ID=eleven_flash_v2_5
SARAH_ELEVENLABS_VOICE_ID=WcGvc9xxaOYbKswm3NBx
OPENAI_API_KEY=<only for an explicitly selected openai rollback run>
```

The voice ID is a non-secret public identifier. If its repository secret is
absent, the workflow uses Sarah's existing approved Voice Design ID
`WcGvc9xxaOYbKswm3NBx`; it never substitutes a generic voice.

Do not put provider credentials in source, a pull-request comment, issue, screenshot, APK, EXE, or team chat. `SARAH_MODEL_BACKEND_TOKEN` is different: the event clients must possess it to authenticate, so CI intentionally bundles that one owner-revocable app token after keeping it out of source, logs, and manifests.

## Build the owner-acceptance candidates

1. Open PR #21's branch `agent/sarah-2.5-event-ready` in GitHub.
2. Open **Actions → Sarah 2.5 online judge build → Run workflow**.
3. Select `workers-ai`.
4. Keep `@cf/google/gemma-4-26b-a4b-it`, or enter another current Workers AI model ID deliberately.
5. Run the workflow and wait for both `deploy-smoke-test-and-build` and `build-tested-windows-event-installer`.
6. Download `Sarah-2.5-R2-OWNER-ACCEPTANCE-CANDIDATE-APK` only after the job passes.
7. Keep the included manifest and SHA-256 with the APK.
8. Download `Sarah-2.5-R2-OWNER-ACCEPTANCE-CANDIDATE-Windows-EXE` for the event laptop and keep its manifest and SHA-256 with it.
9. Run the manifest's pending physical gates before calling either artifact
   judge-ready, replacing the preserved installation, or distributing it.

A push that changes `.github/ONLINE_JUDGE_BUILD_REQUEST.json` also triggers the workflow. Its current default is the no-credit Workers AI route.

The repository `SARAH_MODEL_BACKEND_TOKEN` is intentionally embedded as an app-to-Worker credential in both event binaries. It is not a Cloudflare, OpenAI, or ElevenLabs account credential, but a determined person can extract it from an APK or EXE. Treat it as event-only and revocable. The model/search/voice rate-limit bindings are per Cloudflare location and reduce ordinary abuse; they are not a hard global/day spending cap. Rotate the repository secret, redeploy the Worker, and rebuild/reinstall both clients after the event, or immediately whenever either binary leaves the intended team, and use provider/account budget controls where available.

The separate online-diagnostic workflow deploys `sarah-model-proxy-diagnostic`. It must not deploy over the judge/production `sarah-model-proxy`; diagnostic work cannot invalidate the shared event token and tested judge clients.

## Protected voice route

The same Worker exposes authenticated `POST /voice`.

- `ELEVENLABS_API_KEY` stays in the Worker.
- `SARAH_ELEVENLABS_VOICE_ID` selects the approved Sarah voice server-side.
- A client request for another voice ID is rejected.
- Android receives only `<Worker URL>/voice` and the revocable Sarah event token.
- The event Windows build derives the same voice URL and token from its bundled configuration; per-user setup can override it.

Do not replace the approved voice with a generic voice merely to make a demo pass. Local device speech is an explicitly labeled offline/error fallback.

The current Android source uses a Media3 progressive, one-shot POST and no
longer waits for a complete ElevenLabs MP3 or cache file before feeding the
approved response to the player. It blocks synthesis retries/range reopens and
requires the protected route receipt before that route can be recorded as
actual. Voice receipts contain `requested_at`, `synthesis_start`,
`first_network_byte`, `player_ready`, `response_complete`, the compatibility
alias `synthesis_end`, `playback_start`, and `playback_end`. Android CI
compilation and physical Galaxy A17 hearing/latency acceptance remain pending;
the source change and protected `/voice` byte/header smoke do not prove an
audible latency improvement.

## Protected current-source route

`TAVILY_API_KEY` exists only as a Worker secret. Android's corresponding
BuildConfig field is deliberately blank, and Android/Windows call the
authenticated Worker `/search` route. A connected model reply without an
applied search receipt and exact HTTPS URLs is not current research. The
candidate workflow also exercises the normal chat endpoint with
`web_search=true` and an Android-style contextual `search_query` so `/search`
cannot pass independently while chat ignores it.

## Windows event provisioning and owner override

The online-judge Windows installer contains no provider key. CI creates a non-source `sarah-event-config.json` and bundles the tested Worker URL, provider/model selection, approved voice ID, and the same revocable event app token inside the application EXE. The workflow never uploads the plain config or writes the token to logs or manifests.

1. Install `SarahMorganTravelOS-R2-Candidate-Setup.exe` from the Windows
   owner-acceptance candidate artifact. Its executable, shortcuts, uninstall
   entry, and data root remain side-by-side with the preserved R1 Windows build.
2. Start Sarah; the tested event route works without manually transcribing the token.
3. Use **Connection** only to override the event defaults or remove a per-user override.
4. The protected Worker supplies voice; ordinary owner setup never asks for an ElevenLabs provider key.

Settings are stored for the current user in:

```text
%APPDATA%\SarahMorgan-R2-Candidate\runtime-config.json
```

That local file is outside the EXE and repository. Do not distribute it.
Resolution order is environment variable, then R2 per-user runtime config,
then bundled event config. The event token remains extractable from the
installed binary despite being absent from source and manifests, so post-event
rotation is mandatory. The side-by-side Windows candidate does not silently
inherit R1's private database. Network device sync is disabled in the R2
candidate pending an accepted TLS or authenticated key-agreement transport;
only an owner-selected verified backup/restore path may move private data.

## Online, offline, and automatic-reconnection acceptance

Run this on the real phone before the event:

1. With validated internet, send an ordinary conversational message.
2. Record submit time, first visible text time, and voice playback start time.
3. Enable airplane mode.
4. Send another ordinary message and confirm Sarah uses the local path without claiming the online model answered.
5. Restore internet without opening Settings.
6. Send a third message and confirm Sarah automatically returns to the online route.
7. Confirm text remains visible if voice is unavailable.

Required sequence:

```text
online conversation
→ airplane mode
→ offline conversation
→ restore internet
→ automatic online reconnection on the next message
```

Measure separately:

- submit-to-complete-text latency;
- complete-text-to-playback-start latency;
- playback duration;
- online versus offline response time.

Do not call a route accepted merely because a status label appeared. Preserve timestamps and the provider/voice route actually used.

## Stay22 acceptance

The Stay finder has a separately labeled **Stay22 keyless demo**.

- It sends only the destination, exact adult-traveler/room counts, and optional complete date pair after a traveler taps Search.
- Keyless demo is limited to 5 requests per minute per network.
- It uses one small page and does not automatically retry or paginate.
- Undated discovery cannot claim price or availability.
- A dated supplier total is a temporary quote, not a booking or guaranteed final price.
- Results remain in current activity memory and are not stored as inventory.

Test one undated destination and one complete date window. Do not consume event quota through repeated automatic tests.

## Model comparison

The team may run separate bounded workflow-dispatch builds for current Workers AI catalog IDs. Useful candidates from the event plan include:

```text
@cf/google/gemma-4-26b-a4b-it
@cf/qwen/qwen3-30b-a3b-fp8
@cf/meta/llama-3.3-70b-instruct-fp8-fast
```

Compare natural conversation, travel reasoning, time to text, failure rate, and free-allocation consumption. Do not switch models during the live demonstration without re-running the exact online smoke test. Model IDs and availability can change; verify against Cloudflare's current catalog.

## Troubleshooting

### Missing secret

The workflow names the missing repository secret and stops before deployment. Add only that exact secret; do not paste it into a workflow file.

### HTTP 401 from the Worker

The Sarah app token used by a client did not match the deployed token. Confirm that `SARAH_MODEL_BACKEND_TOKEN` is the intended current repository secret, then rerun the complete judge workflow so Worker deployment, live smoke tests, APK, and Windows installer all use that exact value. Do not paste the token into source or workflow output.

### Workers AI error or quota exhaustion

Inspect the bounded Worker error and Cloudflare dashboard. Free allocation exhaustion is expected to fail closed. Do not silently route to paid OpenAI. Sarah's installed client should continue locally for that turn and retry online on the next message.

### ElevenLabs voice gate fails

Confirm the repository key and approved voice ID, then inspect the `/health` `voice_ready` field. Do not put the provider key into Android BuildConfig to bypass the protected route.

### Android refuses an update

The R2 APK deliberately retains application ID `com.kiraworld.sarahtravel` so
its migration runs against Robert's populated R1 app sandbox. The candidate has
a distinct filename/version and the R1 APK/hash remains preserved, but it is an
in-place Android update rather than a side-by-side app. CI now requires an
exact signing-cache hit, compares the built R2 certificate SHA-256 with the
recorded installed R1 signer, records both signer hashes plus the R1 APK hash,
and stops before upload on any mismatch. If Android reports a signature
conflict, preserve Sarah's data and do not uninstall the populated app.

### Windows blocks an unsigned local build

Do not disable Defender, Smart App Control, UAC, or Application Control to make a local PyInstaller artifact run. Use the clean GitHub Windows build/self-test as the acceptance artifact and pursue normal code signing for distribution.

## Source map

```text
.github/workflows/sarah-2.5-online-judge-build.yml
.github/workflows/sarah-2.5-online-diagnostic.yml
.github/ONLINE_JUDGE_BUILD_REQUEST.json
services/sarah-model-proxy/wrangler.jsonc
services/sarah-model-proxy/src/index.js
services/sarah-model-proxy/test/index.test.js
Sarah_Morgan_Android_Phone_First_v3/android-app/app/build.gradle
Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel/SarahModelConfig.java
Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel/SarahBackendClient.java
Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel/ConnectedModelGateway.java
windows-companion/sarah_core.py
windows-companion/sarah_event_ready.py
windows-companion/sarah_windows.py
```
