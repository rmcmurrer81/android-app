# Sarah 2.5 R3 online mind and current owner-test build

## Owner-use R3 route truth

R3 treats online/offline state as application-owned evidence for each turn.
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
2. requires the owner-revocable `SARAH_MODEL_BACKEND_TOKEN` repository secret
   for Worker deployment and bounded service smoke tests only;
3. deploys a unique candidate Worker named from the immutable GitHub run ID
   and attempt through Wrangler dotenv secret transport, leaving the R1 Worker
   and every earlier candidate endpoint unchanged;
4. confirms exact source/config/deployment identity, all three route-limit
   bindings, public Worker health, and exact-token authentication on the
   bounded `/capabilities` route used by the installed clients;
5. receives exactly `ONLINE_READY` from the selected Workers AI model;
6. runs four bounded live turns through the same Windows `ModelClient` used by
   the owner surface, requiring the exact Workers AI route and recording only
   aggregate text-latency results in the public artifact manifest; raw/private
   turn evidence remains ephemeral and is not uploaded;
7. proves protected `/search`, then proves the real chat route applies the
   Android-style contextual `search_query` and returns exact HTTPS receipts;
8. generates a short real ElevenLabs audio response through the protected `/voice` route without playback and records first-byte and response-complete latency separately;
9. builds the Android APK with the tested Worker URL and public identity, but no
   access code or provider credential;
10. builds and self-tests a Windows event installer whose CI-generated bundled
   configuration contains only the tested URL, provider/model, and voice IDs;
11. verifies that no provider, protected-backend, voice, travel-commerce, or
    concierge credential was placed in either client;
12. uploads only clearly labeled candidate artifacts with hashes and manifests.

This workflow does not simulate or waive the 16 physical owner-acceptance
gates. A successful build or service smoke test is not owner acceptance.

The workflow's network/synthesis timings do not prove audible playback timing
on the Galaxy A17 or 8 GB event laptop. Physical owner-hearing acceptance
remains required.

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

`SARAH_TAVILY_API_KEY` is a server-side current-source credential. It is never
embedded in the APK or EXE. Protected run `31291499147` proved deployment,
wrong/absent-token rejection, authenticated capabilities, and the exact
Workers AI Gemma reply, then stopped before artifact creation because this
secret was absent. Do not bypass that gate or claim current search without a
working source route. Tavily documents a free Researcher allowance of 1,000
credits per month with no credit card; creating that account and storing its
key as the exact Actions secret is a deliberate repository-owner setup step.
When the allowance is unavailable or exhausted, Sarah must keep unsupported
current claims withheld and retain her offline/known-source behavior.

The two R1 SHA-256 checkpoints are public mandatory migration evidence, not
secrets: R1 APK
`be67ceb0adf6d920532bb46a8b79a2be4b6c98dca20a5765f33a70489204b314`
and signer certificate
`d49b6dea8f8ddb332c170abd2d79240de011d302bdbec8a732f783910134c63c`.
They are bound directly in the candidate workflow. The build stops if the
preserved signing-key cache is not an
exact hit or the built R3 signer differs from the recorded installed R1 signer.
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

Do not put provider credentials or the Sarah backend access code in source, a
pull-request comment, issue, screenshot, APK, EXE, or team chat. The repository
`SARAH_MODEL_BACKEND_TOKEN` is used only by deployment and smoke-test steps.
After installation, Robert enters that revocable app access code through
Android **Activate online mind** or Windows **Connection** using a separate
private transfer. It is never a Cloudflare, OpenAI, ElevenLabs, or Tavily key.

## Build the owner-acceptance candidates

1. Open PR #21's branch `agent/sarah-2.5-event-ready` in GitHub.
2. Open **Actions → Sarah 2.5 online judge build → Run workflow**.
3. Select `workers-ai`.
4. Keep `@cf/google/gemma-4-26b-a4b-it`, or enter another current Workers AI model ID deliberately.
5. Run the workflow and wait for both `deploy-smoke-test-and-build` and `build-tested-windows-event-installer`.
6. Download `Sarah-2.5-R3-CURRENT-OWNER-TEST-Android-APK` only after the job passes.
7. Keep the included manifest and SHA-256 with the APK.
8. Download `Sarah-2.5-R3-CURRENT-OWNER-TEST-Windows-ElevenLabs-Candidate` for the event laptop and keep its manifest and SHA-256 with it. Do not install any artifact whose name contains `LEGACY-EVIDENCE`, `ENGINEERING-EVIDENCE`, or `DO-NOT-INSTALL`.
9. Run the manifest's pending physical gates before calling either artifact
   judge-ready, replacing the preserved installation, or distributing it.

A push that changes `.github/ONLINE_JUDGE_BUILD_REQUEST.json` also triggers the workflow. Its current default is the no-credit Workers AI route.

The repository `SARAH_MODEL_BACKEND_TOKEN` is not embedded in either event
binary. It remains an event-only revocable app credential on the Worker and in
the narrowly scoped deployment/smoke steps. Rotate it and redeploy the Worker
if the privately transferred access code is exposed; clients then fail closed
until the confirmed owner activates the replacement code. Route limits reduce
ordinary abuse but are not a hard global/day spending cap, so provider/account
budget controls remain necessary.

The separate online-diagnostic workflow deploys `sarah-model-proxy-diagnostic`. It must not deploy over the judge/production `sarah-model-proxy`; diagnostic work cannot invalidate the shared event token and tested judge clients.

## Protected voice route

The same Worker exposes authenticated `POST /voice`.

- `ELEVENLABS_API_KEY` stays in the Worker.
- `SARAH_ELEVENLABS_VOICE_ID` selects the approved Sarah voice server-side.
- A client request for another voice ID is rejected.
- Android derives `<Worker URL>/voice` from the owner-activated protected route
  and uses the same Keystore-encrypted revocable Sarah access code.
- The event Windows build contains the same public route/voice identity but no
  token. The owner supplies the access code after installation through
  **Connection**; voice derives from that per-user protected route.

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

## Authenticated capability truth

`GET /health` is public deployment/readiness metadata. It does not prove that
an installed owner's access code matches the deployed Worker and therefore must
not make Android report the online mind or protected voice as ready. Android
uses bounded `GET /capabilities` with its exact bearer token. Missing or wrong
tokens receive HTTP 401 (or HTTP 503 when the Worker itself has no configured
token), and those responses cannot create a ready capability cache entry. The
short-lived cache is also bound to a SHA-256 fingerprint of the local token,
the authenticated-probe contract version, build/deployment identity,
source/config hashes, provider, and model. That contract-version binding
invalidates any earlier readiness value produced by the old public-health
probe after an app update.

## Windows event provisioning and owner override

The online-judge Windows installer contains no provider key or protected-route
access code. CI creates a non-source `sarah-event-config.json` containing only
the tested Worker URL, provider/model selection, and approved voice/model IDs.
The build fails if a token, API-key, password, or secret field is added.

1. Install `SarahMorganTravelOS-2.5-R3-CURRENT-OWNER-TEST-Setup.exe` from the Windows
   owner-acceptance candidate artifact. Its executable, shortcuts, uninstall
   entry, and data root remain side-by-side with the preserved R1 Windows build.
2. Start Sarah and use **Connection** to enter the privately transferred,
   revocable Sarah access code. The included URL normally needs no editing.
3. Use **Connection** again to replace or clear that per-user activation.
4. The protected Worker supplies voice; ordinary owner setup never asks for an ElevenLabs provider key.

Settings are stored for the current user in:

```text
%APPDATA%\SarahMorgan-R2-Candidate\runtime-config.json
```

The R3 application deliberately retains this compatibility data root so a
repair install does not silently discard a prior per-user activation. That
local file is outside the EXE and repository. Credential values are
Windows-DPAPI protected for the current Windows account rather than stored as
plaintext. Do not distribute the file.
Resolution order is environment variable, then the preserved per-user runtime config,
then non-secret bundled event defaults. The access code is not extractable
from the installed binary because it is entered only after installation.
Rotate it if the private transfer or installed per-user credential is exposed.
The side-by-side Windows candidate does not silently inherit R1's private
database. R3's authenticated key-agreement and encrypted preview/import path is
implemented in source, but same-LAN Windows/phone acceptance remains pending;
no discovery or pairing event alone may move private data.

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

The Sarah access code saved after installation did not match the deployed
Worker. Confirm the Worker still uses the intended current repository secret,
then privately re-enter that exact revocable app code through Android
**Activate online mind** or Windows **Connection**. Do not rebuild it into an
artifact or paste it into source or workflow output.

### Workers AI error or quota exhaustion

Inspect the bounded Worker error and Cloudflare dashboard. Free allocation exhaustion is expected to fail closed. Do not silently route to paid OpenAI. Sarah's installed client should continue locally for that turn and retry online on the next message.

### ElevenLabs voice gate fails

Confirm the repository key and approved voice ID, then inspect the authenticated
`/capabilities` `voice_ready` field with the exact event bearer token. Public
`/health` is deployment metadata and is not client capability proof. Do not put
the provider key into Android BuildConfig to bypass the protected route.

### Android refuses an update

The R3 APK deliberately retains application ID `com.kiraworld.sarahtravel` so
its migration runs against Robert's populated R1 app sandbox. The candidate has
a distinct filename/version and the R1 APK/hash remains preserved, but it is an
in-place Android update rather than a side-by-side app. CI now requires an
exact signing-cache hit, compares the built R3 certificate SHA-256 with the
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
