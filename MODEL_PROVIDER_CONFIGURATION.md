# Model and Provider Configuration

This document is for the hackathon team. People who install Sarah **do not** choose a provider, type a model ID, or supply an API key in the app.

Sarah 1.5 is source-configured for:

```text
Provider: OpenAI
Model: gpt-5.1
API: OpenAI Responses API
```

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
        -> SarahBackendClient when a protected backend URL is present
        -> OpenAIClient when a private build key is present
    -> public-source or Local fallback if the connected call is unavailable
```

Important files:

| File | Responsibility |
|---|---|
| `SarahModelConfig.java` | Provider ID, model ID, and build-owned connection values |
| `ConnectedModelGateway.java` | One routing point for all model providers |
| `OpenAIClient.java` | Direct OpenAI Responses API adapter |
| `SarahBackendClient.java` | HTTPS adapter for a team-controlled provider router |
| `SarahPromptBuilder.java` | Sarah's identity, behavior, memory, travel, truth, and media instructions |
| `app/build.gradle` | Converts build environment variables into private-test `BuildConfig` values |
| `.github/workflows/build-apk.yml` | Reads optional GitHub Actions secrets during the APK build |
| `backend_examples/openai_proxy/` | Reference server that keeps the OpenAI key off the phone |

## 2. Default OpenAI model

Open:

```text
SarahModelConfig.java
```

The current line is:

```java
public static final String MODEL_ID = "gpt-5.1";
```

To use another OpenAI Responses model, change only that constant first:

```java
public static final String MODEL_ID = "another-openai-model-id";
```

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

## 3. Recommended connection: protected team backend

The preferred architecture is:

```text
Sarah Android app
    -> authenticated HTTPS
Sarah team backend
    -> OpenAI Responses API
```

This prevents every app user from needing an OpenAI account or personal key. It also keeps the OpenAI project key off the phone.

Configure these GitHub Actions repository secrets:

```text
SARAH_MODEL_BACKEND_URL
SARAH_MODEL_BACKEND_TOKEN
```

Example URL:

```text
https://your-host.example/v1/sarah/respond
```

The Android request and response contract is implemented in:

```text
SarahBackendClient.java
```

A runnable reference server is in:

```text
backend_examples/openai_proxy/
```

The server itself reads:

```text
OPENAI_API_KEY
SARAH_APP_TOKEN
SARAH_OPENAI_MODEL
```

The server token and the APK's `SARAH_MODEL_BACKEND_TOKEN` must match for the prototype example.

## 4. Private hackathon shortcut: inject OpenAI directly

For a bounded private test, the workflow can read:

```text
SARAH_OPENAI_API_KEY
```

`app/build.gradle` inserts it into the private test APK as a `BuildConfig` value.

This is **not safe for a public release**. Anyone with the APK may be able to extract an embedded key. Use a tightly limited project key, strict spending limits, and immediate rotation after the demonstration—or, preferably, use the protected backend instead.

A build with no team backend and no injected OpenAI key still compiles. It uses:

- public official-event lookup;
- public background-reference lookup;
- maps, public photo previews, video search, and route tools;
- the Local Travel Brain;
- saved memories, trips, event records, and calm tools.

The status line clearly says that OpenAI is not included in that build. It does not ask the app user for a key.

## 5. Changing from OpenAI to Claude

Claude is not included by default. A team member should make the following source changes.

### Step A: create the provider adapter

Create:

```text
ClaudeClient.java
```

Give it a method that accepts the same logical inputs used by `OpenAIClient.respond`:

```java
respond(
    apiKey,
    model,
    systemPrompt,
    history,
    message,
    webSearch,
    imageJpeg
)
```

The adapter must translate:

- Sarah's system prompt;
- role-based recent history;
- the current user message;
- optional JPEG input;
- current-source or tool requests;
- provider errors and timeouts.

Do not claim current web research merely because a model was trained on internet text. Connect a real current-source tool or backend.

### Step B: change the provider configuration

In `SarahModelConfig.java`, change:

```java
public static final String PROVIDER_ID = "openai";
public static final String MODEL_ID = "gpt-5.1";
```

to stable Anthropic values chosen by the team, for example:

```java
public static final String PROVIDER_ID = "anthropic";
public static final String MODEL_ID = "the-team-selected-claude-model";
```

Use the current official provider model identifier rather than guessing one.

### Step C: add a gateway branch

In `ConnectedModelGateway.java`, add a branch before the unsupported-provider exception:

```java
if ("anthropic".equals(normalized) || "claude".equals(normalized)) {
    return ClaudeClient.respond(
        effectiveKey,
        model,
        systemPrompt,
        history,
        message,
        effectiveWebSearch,
        imageJpeg
    );
}
```

If all providers run behind the team backend, the Android gateway can remain provider-neutral and the server can perform the provider switch instead.

### Step D: change build secrets or backend variables

Do not reuse an OpenAI key field for an Anthropic credential. Add a clearly named build/server variable and keep provider secrets separated.

### Step E: test every capability

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

## 9. Build verification

The GitHub workflow must produce:

```text
Sarah-Morgan-1.5-builtin-openai-media
```

The APK inside the GitHub Actions artifact must be:

```text
Sarah-Morgan-1.5-builtin-openai-media.apk
```

Verify on the phone under Sarah Settings:

```text
Build 1.5-builtin-openai-media
```

A team OpenAI-enabled build should show:

```text
Automatic • OpenAI online
```

A build without the team connection should show:

```text
Automatic • Public web online • OpenAI not included in this build
```

Neither state should ask the person installing Sarah to enter an API key.
