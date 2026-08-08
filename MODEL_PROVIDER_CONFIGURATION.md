# Model and Provider Configuration

> **Current event selection (2026-08-08):** Android and Windows use one protected Cloudflare Worker contract. `SARAH_MODEL_PROVIDER=workers-ai` and `SARAH_MODEL_ID=@cf/google/gemma-4-26b-a4b-it` select the no-OpenAI-credit path. OpenAI remains an optional explicit provider. Historical OpenAI examples below are retained for rollback and should not be read as the current required route.

This document is for the hackathon team. People who install Sarah **do not** choose a provider, type a model ID, or supply an API key in the app.

Sarah R2 is source-configured for:

```text
Provider: Cloudflare Workers AI through Sarah's protected candidate Worker
Model: @cf/google/gemma-4-26b-a4b-it
Client API: authenticated Sarah Worker contract
```

This is the current candidate selection, not a benchmark-winner claim. A
bounded Gemma/Qwen/Llama comparison and physical latency/quality acceptance
remain pending. Do not silently change the model in a built client.

The authoritative values are in:

```text
Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel/SarahModelConfig.java
```

## 1. How Sarah chooses a model

The connected path is:

```text
MainActivity
    -> SarahModelConfig
    -> ConnectedModelGateway
        -> SarahBackendClient
        -> exact HTTPS candidate Worker recorded in the build manifest
        -> Workers AI model selected for that complete workflow run
    -> explicit Local fallback if the connected call is unavailable
```

Important files:

| File | Responsibility |
|---|---|
| `SarahModelConfig.java` | Provider ID, model ID, and build-owned connection values |
| `ConnectedModelGateway.java` | One routing point for all model providers |
| `OpenAIClient.java` | Preserved optional rollback adapter; not the R2 event default |
| `SarahBackendClient.java` | HTTPS adapter for the protected Sarah Worker |
| `SarahPromptBuilder.java` | Sarah's identity, behavior, memory, travel, truth, and media instructions |
| `app/build.gradle` | Converts build environment variables into private-test `BuildConfig` values |
| `.github/workflows/sarah-2.5-online-judge-build.yml` | Deploys/tests one unique Worker, then builds both candidate clients |
| `services/sarah-model-proxy/` | Current Worker for Workers AI, protected search, and ElevenLabs |
| `backend_examples/openai_proxy/` | Historical optional OpenAI rollback reference only |

## 2. Current Workers AI model

The event model is supplied to the unique Worker and both clients by the same
reviewed workflow run:

```text
SARAH_MODEL_PROVIDER=workers-ai
SARAH_MODEL_ID=@cf/google/gemma-4-26b-a4b-it
```

For a last-minute comparison, dispatch **Sarah 2.5 online judge build** with
`workers-ai` and one exact current Cloudflare catalog model ID. Do not edit an
installed APK or EXE, and do not reuse an older endpoint. The workflow must
deploy a new unique Worker, rerun authenticated health/chat/search/vision/voice
smokes, rebuild both clients from the same commit, and produce a new manifest.
Keep the earlier artifact and endpoint unchanged until the candidate passes.

Then build a new APK and test:

- ordinary multi-turn conversation;
- web-backed current questions;
- event follow-ups such as `When is it?`;
- photo understanding;
- booking screenshot extraction;
- automatic fallback when the service is unavailable;
- response speed and cost;
- age-aware behavior and memory use.

Do not assume every model supports the same text, image, tool, and web-search capabilities.

## 3. Current connection: protected candidate Worker

The preferred architecture is:

```text
Sarah Android app + Sarah Windows app
    -> authenticated HTTPS with revocable app credential
Unique manifest-bound Sarah Worker
    -> Cloudflare Workers AI
    -> protected current-source search when configured
    -> protected approved ElevenLabs voice
```

This route requires no OpenAI account or purchased OpenAI credits. Provider
credentials remain in Cloudflare; the client-held Sarah credential is
extractable and therefore revocable, rate-limited, and event-only.

Configure these GitHub Actions repository secrets:

```text
SARAH_MODEL_BACKEND_TOKEN
CLOUDFLARE_API_TOKEN
CLOUDFLARE_ACCOUNT_ID
ELEVENLABS_API_KEY
SARAH_TAVILY_API_KEY
```

The workflow creates the unique backend URL. Do not configure a shared mutable
URL by hand. The Android request and response contract is implemented in:

```text
SarahBackendClient.java
```

A runnable Worker is in:

```text
services/sarah-model-proxy/
```

The Worker's Sarah app token and the token bundled by that exact workflow run
must match. The workflow manifest records its Worker name, URL, deployment ID,
source/config hashes, and owner-invoked retirement command.

## 4. Preserved optional rollback: direct OpenAI

The legacy source can still support a separately authorized private rollback
build using:

```text
SARAH_OPENAI_API_KEY
```

This is disabled in the R2 Workers AI candidate and is not required for the
event. `app/build.gradle` can insert it only into an explicitly selected private
rollback build.

This is **not safe for a public release**. Anyone with the APK may be able to extract an embedded key. Use a tightly limited project key, strict spending limits, and immediate rotation after the demonstration—or, preferably, use the protected backend instead.

A build with no team backend and no injected OpenAI key still compiles. It uses:

- public official-event lookup;
- public background-reference lookup;
- maps, public photo previews, video search, and route tools;
- the Local Travel Brain;
- saved memories, trips, event records, and calm tools.

The ordinary app does not ask its user for a provider key.

## 5. Switching away from the current Workers AI model

Prefer another compatible current Workers AI catalog ID first, because the
provider-neutral client contract then remains unchanged. Changing to another
provider requires a separately reviewed server adapter; provider credentials
must remain server-side and must never reuse another provider's secret field.

A model name in documentation is not a pass. Every candidate requires a fresh
unique Worker, the bounded online workflow smokes, both clients rebuilt from
one commit, and a new manifest. Current-source claims still require the real
search tool; model training data is not live research.

At minimum test:

1. ordinary conversation unrelated to travel;
2. event identification and short follow-ups;
3. image understanding;
4. booking screenshot extraction;
5. current event and travel-source use;
6. voice fallback;
7. automatic online-to-offline and offline-to-online transitions;
8. timeout, quota, and invalid-credential handling;
9. memory separation between the phone owner and a guest;
10. no accidental exposure of provider errors or credentials.

## 6. Changing Sarah's prompt or personality

Edit:

```text
SarahPromptBuilder.java
```

Keep these separations intact:

- Sarah's spoken answer;
- private application state;
- confirmed trip or booking facts;
- current source-backed information;
- unverified ideas or extracted candidates.

Do not make model output itself proof that a booking, event monitor, notification, or deal watch exists. Durable actions must still pass through the corresponding application stores and executors.

## 7. Renaming Sarah

A cosmetic rename requires more than changing the launcher label. Search the repository for:

```text
Sarah
Sarah Morgan
sarahtravel
SarahMorgan
```

Review:

- `AndroidManifest.xml` label;
- onboarding and chat titles;
- `SarahPromptBuilder.java` identity;
- Local fallback introductions;
- voice instructions;
- notification channels;
- launcher icon artwork;
- README and workflow artifact names;
- backend names and user-agent strings.

Changing the application ID, database filenames, preference names, Android Keystore aliases, or notification channel IDs requires a migration plan or existing installations may lose access to prior local data.

## 8. Security rules

Never commit an actual OpenAI or other provider key to a Java, XML, Markdown, workflow, screenshot, issue, or pull request.

For a public release:

- keep provider keys on a protected server;
- authenticate users or devices;
- add rate limits and usage quotas;
- set provider and cloud spending limits;
- rotate secrets;
- log operational metadata without exposing private conversations;
- provide deletion and privacy controls;
- validate image and message sizes;
- document source, data-retention, and billing behavior;
- production-sign the APK;
- complete security and store-policy review.

## 9. R2 build verification

The GitHub workflow must produce:

```text
Sarah-2.5-R2-OWNER-ACCEPTANCE-CANDIDATE-APK
Sarah-2.5-R2-OWNER-ACCEPTANCE-CANDIDATE-Windows-EXE
```

The APK inside the GitHub Actions artifact must be:

```text
Sarah-Morgan-2.5-R2-OWNER-ACCEPTANCE-CANDIDATE.apk
```

Verify on the phone under Sarah Settings:

```text
Build 2.5 R2 owner-acceptance candidate + exact commit
```

A successful connected turn should show:

```text
Online mind
```

A failed connected turn that continues locally should show:

```text
Online unavailable — answered offline
```

An intentionally offline turn should show `Offline mind`. None of these states
asks the person installing Sarah to enter a provider key. Compilation and CI
smokes alone do not satisfy physical Galaxy A17 or 8 GB Windows acceptance.
