# Sarah Morgan Android Companion

Sarah Morgan is a phone-first Android travel companion and general conversational companion. She can remember useful details with permission, speak aloud, accept push-to-talk input, help with first-flight anxiety and turbulence, play personalized trivia, suggest destination-related media when asked, keep trip and wish-list information, and discuss selected photographs when an image-capable connected model is available.

Current Android version: **0.9-auto-smart**  
Private-test application ID: `com.kiraworld.sarahtravel.debug`

This repository is a development prototype, not a finished public app-store release.

---

## 1. Version 0.9: connected by default, local when needed

Sarah now uses **Automatic mode by default**.

Automatic mode follows this routing rule:

| Phone/model state | Route used |
|---|---|
| Validated internet connection + encrypted model key | Smart connected model |
| No validated internet | Local companion |
| Internet exists but no model key is configured | Local companion |
| Connected model request fails or times out | Local companion for that message |
| Internet/model service becomes usable again | Smart connected model on the next message |
| User selects Local only | Local companion regardless of connection |

Sarah does not need to restart when connectivity changes. `ConnectivityMonitor.java` registers an Android default-network callback while the chat activity is open. The status line beneath Sarah's name updates to messages such as:

```text
Automatic • Smart online • tap to switch • Robert
Automatic • Local • offline • tap to switch • Robert
Automatic • Local • Smart setup needed • tap to switch • Robert
Automatic • Local fallback • tap to switch • Robert
```

A Wi-Fi or mobile-data icon by itself is not treated as proof of working internet. The app checks Android's `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED` capabilities.

The app never changes or deletes the profile, trips, memories, or wish list when switching routes. Both routes use the same local SQLite data.

### Available connection preferences

The mode selector contains:

1. **Automatic** — Smart online, Local when offline. This is recommended and is the default.
2. **Smart preferred** — also attempts the connected model whenever internet and a key are available, while preserving a local fallback so the person is not left without a reply.
3. **Local only** — never sends a conversation to a connected model.

The person can change this in Settings or by tapping the status line below Sarah's name.

---

## 2. Repository layout

```text
android-app/
├── .github/
│   └── workflows/
│       └── build-apk.yml
├── BUILD_VERSION.txt
├── README.md
└── Sarah_Morgan_Android_Phone_First_v3/
    ├── README_START_HERE.md
    ├── APK_BUILD_STATUS.md
    ├── PHONE_FIRST_ARCHITECTURE.md
    ├── SARAH_VOICE_DESIGN.md
    ├── android-app/
    │   ├── app/
    │   ├── build.gradle
    │   ├── gradle.properties
    │   └── settings.gradle
    ├── desktop_optional/
    └── tests/
```

The actual Gradle project starts at:

```text
Sarah_Morgan_Android_Phone_First_v3/android-app/
```

---

## 3. Building the APK

The workflow is:

```text
.github/workflows/build-apk.yml
```

It performs these steps:

1. checks out the repository;
2. installs Java 17;
3. compiles and runs the pure-Java automatic-routing test;
4. restores the private-test debug signing key cache;
5. installs Gradle 8.13;
6. builds the Android debug APK;
7. renames it to `Sarah-Morgan-0.9-auto-smart.apk`;
8. uploads the artifact `Sarah-Morgan-0.9-auto-smart`.

From a phone browser:

1. Open the repository on GitHub.
2. Open **Actions**.
3. Select **Build Sarah Android APK**.
4. Open the newest run and wait for a green check.
5. Download the `Sarah-Morgan-0.9-auto-smart` artifact.
6. Extract the artifact ZIP.
7. Install `Sarah-Morgan-0.9-auto-smart.apk`.

Do not download an artifact from an older run merely because it is green. Verify the artifact name and check Settings after installation for `Build 0.9-auto-smart`.

---

## 4. Important source files

### Conversation and route control

- `MainActivity.java` — chat UI, message routing, automatic fallback, speech/photo entry points.
- `ConversationModePolicy.java` — pure routing rules and status labels.
- `ConnectivityMonitor.java` — validated Android network monitoring.
- `ConnectedModelGateway.java` — single entry point for connected-model providers.
- `OpenAIClient.java` — included OpenAI Responses adapter.
- `DemoSarah.java` — local rule-based fallback conversation.
- `SarahPromptBuilder.java` — connected-model identity, memory, travel, age, and truth instructions.

### Identity and onboarding

- `OnboardingActivity.java` — one-question-at-a-time first conversation.
- `SpeakerContext.java` — active speaker and age-safe guest behavior.
- `SarahDatabase.java` — profile, messages, memories, trips, wish list, and photo records.
- `MemoryExtractor.java` — conservative local extraction of useful preferences.

### Voice and photos

- `SarahTts.java` — Android text-to-speech.
- `CloudVoiceClient.java` — optional connected voice.
- `ImageSanitizer.java` — selected-image decode/re-encode so ordinary EXIF/GPS metadata is not copied into Sarah's stored JPEG.

---

## 5. Changing the connected model

There are two different changes a team may mean by “change the model.”

### A. Change to another model supported by the existing OpenAI Responses adapter

This is the simple case. No Java code change is required.

In the installed app:

1. Open Sarah's wrench/settings screen.
2. Enter the desired model ID in **Model name**.
3. Save settings.

The default model is currently set in `SettingsActivity.java`:

```java
p.getString("model", "gpt-5-mini")
```

and passed through `ConnectedModelGateway.java` to `OpenAIClient.java`.

Before changing the default, confirm that the selected model supports the features the app will request. Text, image input, and web tools are separate capabilities; a model name accepting text does not automatically prove that it accepts photographs or provider-hosted web search.

### B. Add a different provider such as Claude

Claude is **not currently implemented**. The code is deliberately routed through `ConnectedModelGateway.java` so the team can add it without rewriting `MainActivity`.

Recommended implementation sequence:

1. Create:

```text
app/src/main/java/com/kiraworld/sarahtravel/ClaudeClient.java
```

2. Give it a method matching the information already passed to the gateway:

```java
public static String respond(
        String apiKey,
        String model,
        String systemPrompt,
        List<Map<String, String>> history,
        String message,
        boolean webSearch,
        byte[] imageJpeg) throws Exception
```

3. Translate Sarah's data to the provider's current message format:

- place `systemPrompt` in the provider's system/instructions field;
- preserve user/assistant roles from `history`;
- append the current user message once;
- encode the selected JPEG using the provider's documented image-input format;
- do not pretend the provider supports live web research unless a real tool or team backend supplies it;
- return only Sarah's public response text.

4. Add a branch in `ConnectedModelGateway.java`, for example:

```java
if ("anthropic".equals(normalized) || "claude".equals(normalized)) {
    return ClaudeClient.respond(
            apiKey, model, systemPrompt, history,
            message, webSearch, imageJpeg);
}
```

5. Add a provider selector to `SettingsActivity.java` and save a stable provider ID such as:

```text
openai
anthropic
```

The routing code already reads:

```java
prefs.getString("connected_provider", "openai")
```

6. Decide how credentials are stored. The current `SecureStore.java` stores one encrypted key under an Android Keystore-backed AES/GCM key. Supporting multiple providers may require separate encrypted entries such as `openai_key` and `anthropic_key`, or—preferably for a public app—a protected server so provider secrets never live in the APK.

7. Add provider-specific tests for:

- ordinary text conversation;
- multi-turn continuity;
- image input;
- timeout and HTTP-error handling;
- automatic Local fallback;
- return to Smart after connectivity is restored;
- age-appropriate responses;
- no duplicate user message;
- no accidental transmission when Local only is selected.

8. Update the README and Settings description to state exactly which provider features are available. Do not call a feature “web search” merely because the model may know public information from training.

### Provider-neutral public architecture

For a public release, the recommended path is:

```text
Android app
    ↓ authenticated HTTPS request
Sarah backend/provider router
    ├── OpenAI adapter
    ├── Anthropic/Claude adapter
    ├── Amazon Bedrock adapter
    └── another approved provider
```

That backend should handle authentication, rate limits, billing, abuse controls, provider keys, audit-safe errors, and model selection. A shared production key must never be embedded in the APK.

---

## 6. Renaming Sarah

The app can be renamed without renaming every Java class, but all user-visible identity strings and identity instructions must be changed together. A partial rename can cause the launcher to show one name while the companion still calls herself Sarah in conversation.

### Minimum user-visible rename checklist

Change the following:

1. **Android launcher label**

```text
app/src/main/AndroidManifest.xml
```

Change:

```xml
android:label="Sarah Morgan"
```

2. **Main and onboarding titles**

Search layouts under:

```text
app/src/main/res/layout/
```

especially:

- `activity_main.xml`
- `activity_onboarding.xml`
- `activity_settings.xml`

3. **Conversational identity and greeting text**

Search Java files under:

```text
app/src/main/java/com/kiraworld/sarahtravel/
```

especially:

- `MainActivity.java`
- `OnboardingActivity.java`
- `DemoSarah.java`
- `SarahPromptBuilder.java`
- `SettingsActivity.java`
- `SpeakerContext.java`
- `CalmSupport.java`
- `CloudVoiceClient.java`

4. **Content descriptions and hints**

Examples include:

```text
Talk to Sarah
Sarah is thinking…
Sarah remembered:
Sarah settings
```

5. **README and package documentation**

Update `README.md`, `README_START_HERE.md`, architecture notes, voice notes, artifact names, and build labels.

6. **Artifact and workflow names**

Update `.github/workflows/build-apk.yml` if the APK/artifact should use the new name.

### Recommended search command

From the repository root:

```bash
grep -RIn --exclude-dir=.git --exclude='*.png' --exclude='*.jpg' \
  -e 'Sarah Morgan' -e 'Sarah' Sarah_Morgan_Android_Phone_First_v3 .github README.md BUILD_VERSION.txt
```

Review every match rather than performing a blind global replacement. Some class names, database names, filenames, and historical migration strings may be intentionally preserved.

### Internal names that do not have to change

The following can remain for compatibility during a user-visible rename:

- Java class names such as `SarahDatabase` or `SarahTts`;
- package name `com.kiraworld.sarahtravel`;
- application ID;
- existing database filename;
- existing SharedPreferences filename.

Keeping those stable allows an update to preserve installed user data.

### Internal names to change only with a migration plan

These identifiers affect continuity or upgrades:

- `applicationId` in `app/build.gradle`;
- database name `sarah.db` in `SarahDatabase.java`;
- SharedPreferences name `sarah_settings` in `SettingsActivity.java`;
- Android Keystore alias `SarahMorganApiKey` in `SecureStore.java`;
- package directory and Java package declaration;
- GitHub artifact names.

Changing the application ID creates a different Android app rather than an update. Changing the database or preference names without copying old data can make memories and settings appear lost. Changing the Keystore alias without migration can make the saved API key unreadable.

### Voice after a rename

The default Android voice is not a cloned identity and can continue to be used. If the team changes the cloud voice provider, voice instructions, or identity name, update `CloudVoiceClient.java` and the voice documentation together. Do not imitate a real person's voice without authorization and provider-compliant consent.

---

## 7. Local versus Smart responsibilities

### Local companion

Best for:

- no-internet situations;
- turbulence calming;
- grounding and trivia;
- saved profile, trips, memories, and wish-list review;
- common first-flight explanations;
- basic structured travel follow-ups;
- privacy-preserving continuity while disconnected.

Local conversation is rule-based. It should not claim to perform current research, see a photo, know a live fare, or understand an unrestricted topic like a full language model.

### Smart connected model

Used for:

- natural open-ended conversation;
- nuanced follow-ups;
- photo understanding when supported;
- current research when an actual web tool is enabled;
- richer itinerary reasoning;
- current destination, fare, event, and opening-hour questions.

If a Smart request fails, the error is not inserted into Sarah's spoken reply. Sarah answers locally and the app shows a small Android notice.

---

## 8. Privacy and data handling

Sarah stores locally:

- onboarding profile;
- chat history;
- approved extracted memories;
- past and planned trips;
- wish-list destinations;
- cleaned selected-photo copies;
- mode, voice, and model settings;
- an encrypted personal API key for private testing.

Automatic routing must never send data when `Local only` is selected. A connected request may include the system prompt, recent conversation, relevant local memories/trips/wishes, the current message, and a selected photo if one is attached.

Before public release, add:

- a full privacy policy;
- per-record edit/delete controls;
- account/data deletion;
- export/import and encrypted backup;
- retention limits;
- protected backend credentials;
- user-visible provider disclosure;
- consent and parental controls appropriate to the intended audience;
- security review and broad device testing.

---

## 9. Testing automatic switching

Test on a physical phone with Automatic mode and a valid connected-model key:

1. Start online. Confirm the status says `Automatic • Smart online`.
2. Ask a question and confirm the connected model answers.
3. Enable airplane mode. Confirm the status changes to Local/offline.
4. Ask for turbulence support or ordinary conversation. Confirm Sarah answers locally without a network error in the chat.
5. Restore Wi-Fi or mobile data.
6. Wait for Android to validate the connection. Confirm the status returns to Smart online.
7. Ask another question and confirm the connected model is used.
8. Select Local only and confirm no connected request occurs even with internet.
9. Remove or invalidate the API key and confirm Automatic mode remains usable locally.
10. Force a connected HTTP failure and confirm Sarah falls back locally for that message.

The pure-Java `ConversationModePolicyTest.java` verifies the central route table during every GitHub build.

---

## 10. Known limitations

- Claude and other non-OpenAI providers are documented but not implemented.
- The private prototype still calls its connected provider directly from the phone.
- Local conversation remains a bounded rule system.
- Background deal monitoring and push notifications are not implemented.
- Photo understanding requires a compatible connected model.
- Android voice quality depends on installed speech engines and voices.
- A complete public-release privacy, security, billing, and account system remains future work.
