# Sarah 2.5 online mind and judge build

## Current event route

The judge APK uses a protected Cloudflare Worker with:

```text
SARAH_MODEL_PROVIDER=workers-ai
SARAH_MODEL_ID=@cf/google/gemma-4-26b-a4b-it
```

This route does not require an OpenAI key or purchased OpenAI credits. Cloudflare Workers AI has a bounded free allocation rather than unlimited free use. When the network, Worker, model, or daily allocation is unavailable, Sarah must identify the connected failure and use her local/offline path for that turn. Automatic mode tries the online path again on the next message; it does not require a Settings change.

OpenAI remains an explicit optional rollback provider. It is not the current required hackathon route.

The ordinary `Sarah-2.5-validated-APK` is a source/build validation artifact. It is not judge-ready unless its manifest proves a live online smoke test. Use the artifact named `Sarah-2.5-ONLINE-JUDGE-APK`.

## What the judge workflow proves

The workflow `.github/workflows/sarah-2.5-online-judge-build.yml`:

1. validates the requested provider and model;
2. requires the owner-revocable `SARAH_MODEL_BACKEND_TOKEN` repository secret;
3. deploys the Worker with that token through Wrangler dotenv secret transport;
4. confirms Worker health and exact-token authentication;
5. receives exactly `ONLINE_READY` from the selected Workers AI model;
6. generates a short real ElevenLabs audio response through the protected `/voice` route without playback;
7. builds the Android APK with the tested Worker URL and the same event token;
8. builds and self-tests a Windows event installer with a CI-generated bundled event configuration;
9. verifies that direct OpenAI and ElevenLabs provider keys were not placed in either client;
10. uploads the APK and Windows installer with hashes and truthful manifests only after every live gate passes.

Text chat remains independent of voice. If ElevenLabs fails in ordinary use, Android local speech is the fallback and text must remain available.

## Required GitHub Actions secrets

Repository **Settings → Secrets and variables → Actions** needs:

```text
CLOUDFLARE_ACCOUNT_ID
CLOUDFLARE_API_TOKEN
SARAH_ELEVENLABS_API_KEY
SARAH_ELEVENLABS_VOICE_ID
SARAH_MODEL_BACKEND_TOKEN
```

`CLOUDFLARE_API_TOKEN` must be a restricted deployment token for the intended account, not a browser session value or global API key.

Set `SARAH_MODEL_BACKEND_TOKEN` to a 32-256 character cryptographically random URL-safe value containing only letters, digits, `_`, and `-`. Keep the same repository value for a complete event build; rotation is a deliberate Worker redeploy plus client rebuild/reinstall, not an automatic per-job change.

The following are optional:

```text
SARAH_ELEVENLABS_MODEL_ID=eleven_flash_v2_5
OPENAI_API_KEY=<only for an explicitly selected openai rollback run>
```

Do not put provider credentials in source, a pull-request comment, issue, screenshot, APK, EXE, or team chat. `SARAH_MODEL_BACKEND_TOKEN` is different: the event clients must possess it to authenticate, so CI intentionally bundles that one owner-revocable app token after keeping it out of source, logs, and manifests.

## Build the judge APK

1. Open PR #21's branch `agent/sarah-2.5-event-ready` in GitHub.
2. Open **Actions → Sarah 2.5 online judge build → Run workflow**.
3. Select `workers-ai`.
4. Keep `@cf/google/gemma-4-26b-a4b-it`, or enter another current Workers AI model ID deliberately.
5. Run the workflow and wait for both `deploy-smoke-test-and-build` and `build-tested-windows-event-installer`.
6. Download `Sarah-2.5-ONLINE-JUDGE-APK` only after the job passes.
7. Keep the included manifest and SHA-256 with the APK.
8. Download `Sarah-2.5-ONLINE-JUDGE-Windows-EXE` for the event laptop and keep its manifest and SHA-256 with it.

A push that changes `.github/ONLINE_JUDGE_BUILD_REQUEST.json` also triggers the workflow. Its current default is the no-credit Workers AI route.

The repository `SARAH_MODEL_BACKEND_TOKEN` is intentionally embedded as an app-to-Worker credential in both event binaries. It is not a Cloudflare, OpenAI, or ElevenLabs account credential, but a determined person can extract it from an APK or EXE. Treat it as event-only and revocable. Rotate the repository secret, redeploy the Worker, and rebuild/reinstall both clients after the event, or immediately whenever either binary leaves the intended team.

The separate online-diagnostic workflow deploys `sarah-model-proxy-diagnostic`. It must not deploy over the judge/production `sarah-model-proxy`; diagnostic work cannot invalidate the shared event token and tested judge clients.

## Protected voice route

The same Worker exposes authenticated `POST /voice`.

- `ELEVENLABS_API_KEY` stays in the Worker.
- `SARAH_ELEVENLABS_VOICE_ID` selects the approved Sarah voice server-side.
- A client request for another voice ID is rejected.
- Android receives only `<Worker URL>/voice` and the revocable Sarah event token.
- The event Windows build derives the same voice URL and token from its bundled configuration; per-user setup can override it.

Do not replace the approved voice with a generic voice merely to make a demo pass. Local device speech is an explicitly labeled offline/error fallback.

## Windows event provisioning and owner override

The online-judge Windows installer contains no provider key. CI creates a non-source `sarah-event-config.json` and bundles the tested Worker URL, provider/model selection, approved voice ID, and the same revocable event app token inside the application EXE. The workflow never uploads the plain config or writes the token to logs or manifests.

1. Install the `SarahMorganTravelOS-Setup.exe` from `Sarah-2.5-ONLINE-JUDGE-Windows-EXE`.
2. Start Sarah; the tested event route works without manually transcribing the token.
3. Use **Online setup** only to override the event defaults or remove a per-user override.
4. Leave the direct ElevenLabs-key prompt blank; the protected Worker supplies voice.

Settings are stored for the current user in:

```text
%APPDATA%\SarahMorgan\runtime-config.json
```

That local file is outside the EXE and repository. Do not distribute it. Resolution order is environment variable, then per-user runtime config, then bundled event config. The event token remains extractable from the installed binary despite being absent from source and manifests, so post-event rotation is mandatory.

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

The judge APK preserves the existing debug-signing cache when available. If Android reports a signature conflict, preserve Sarah's data first, record the installed package/version/signature, and only then decide whether uninstalling the older private test package is acceptable.

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
