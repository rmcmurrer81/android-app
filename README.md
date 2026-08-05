# Sarah Morgan Android Companion

Sarah Morgan is a phone-first Android travel companion and general conversational companion. She can remember approved details, speak aloud, accept push-to-talk input, help with first-flight anxiety and turbulence, play personalized trivia, compare destinations, keep trip and wish-list information, and use a connected model for broad conversation, image understanding, and current research.

Current Android version: **1.0-travel-brain**  
Private-test application ID: `com.kiraworld.sarahtravel.debug`

This repository is a development prototype, not a finished public app-store release.

---

## 1. What changed in 1.0 Travel Brain

Version 1.0 replaces the shallow phrase-to-paragraph fallback with a layered local architecture:

1. `DestinationParser.java` extracts more than one destination and keeps alternatives such as Paris **and** London.
2. `TravelKnowledgeBase.java` provides stable offline background knowledge for twelve destinations.
3. `TravelBrainCore.java` handles travel goals, topic corrections, comparisons, deal planning, airport-process questions, and first-flight support.
4. `DemoSarah.java` now handles lightweight ordinary conversation after the Travel Brain has had the first opportunity to answer.
5. `MemoryExtractor.java` is more conservative. It no longer hardens vague pronoun statements such as “I love seeing it in movies” into a permanent interest.
6. Deal-alert requests appear separately in the Travel Notebook and are explicitly labeled as requests rather than live monitoring.
7. The GitHub workflow runs regression tests based on the real phone conversation that exposed the earlier problems.

### Screenshot regression fixed

The test suite now covers this sequence:

```text
I would love to travel to either Paris or London.
The history.
I love seeing it in different movies and shows.
I don't care about watching stuff; I'm just looking for deals.
Just notify me about deals.
```

The required behavior is:

- keep both Paris and London in context;
- compare their history instead of asking a generic question;
- discuss media only while the person is interested in media;
- stop the media topic immediately after correction;
- never force a John Wick recommendation;
- preserve Newark as the origin, not a destination;
- remember flexible dates and light luggage correctly;
- state honestly that a saved deal request is not yet a live price alert.

---

## 2. Automatic connected/local behavior

Sarah uses **Automatic mode** by default.

| Phone/model state | Route used |
|---|---|
| Validated internet + saved model key | Connected model |
| No validated internet | Offline Travel Brain |
| Internet but no model key | Offline Travel Brain |
| Connected request fails or times out | Offline Travel Brain for that message |
| Connection/model becomes usable again | Connected model on the next message |
| User selects Local only | Offline Travel Brain regardless of connection |

Important files:

- `ConversationModePolicy.java` — pure routing rules.
- `ConnectivityMonitor.java` — Android validated-network monitoring.
- `ConnectedModelGateway.java` — single entry point for connected providers.
- `MainActivity.java` — chooses the route and preserves one shared local profile, memory store, trip notebook, and chat history.

The status line can display:

```text
Automatic • Smart online
Automatic • Local • offline
Automatic • Local • Smart setup needed
Automatic • Local fallback
```

Automatic switching does not delete or replace identity, trips, memories, or wishes.

---

## 3. Offline Travel Brain

### Covered destinations

The bundled stable knowledge pack currently contains:

- Paris
- London
- New York City
- Rome
- Tokyo
- Washington, D.C.
- Chicago
- Boston
- Salem
- Charleston
- San Francisco
- Los Angeles

Each entry separates:

- historical framing;
- first-visit structure;
- transport considerations;
- practical, sensory, walking, or accessibility concerns;
- family-friendly media atmosphere;
- adult media or documentary context.

The local knowledge deliberately avoids pretending to know live prices, opening hours, closures, service disruptions, visa rules, or current weather.

### Travel dialogue capabilities

`TravelBrainCore.java` can locally handle:

- two-destination comparisons;
- short follow-ups such as “the history”;
- destination overview, transport, planning, history, and requested media;
- fare-planning questions one missing item at a time;
- flexible dates, luggage, trip type, and traveler count;
- first-flight process explanations;
- turbulence support and redirection to local grounding/trivia tools;
- corrections such as “stop talking about movies; I want deals”;
- honest deal-watch status.

### Why local conversation is still limited

The offline Travel Brain is a deterministic, inspectable fallback. It is much more capable than the former script list, but it is not a full language model. Broad philosophy, unusual factual questions, nuanced social conversation, image analysis, and current research still belong to the connected model.

Do not expand local behavior by adding dozens of overlapping phrase checks to `DemoSarah.java`. Add structured intent or knowledge support to `TravelBrainCore.java` or `TravelKnowledgeBase.java` instead.

---

## 4. Travel deal requests versus real notifications

Sarah may remember a request such as:

```text
Wants travel deal alerts for Paris and London
```

That record is displayed in the Travel Notebook under **Travel deal requests**.

It is not proof that the app is already monitoring prices.

Real automatic deal notifications require all of the following:

1. a lawful and reliable airfare data source or partner API;
2. a backend or approved device-side provider adapter;
3. normalized routes, airports, currency, dates, bags, and traveler count;
4. a definition of what qualifies as a deal;
5. periodic checks that respect provider rate limits;
6. Android notification permission and scheduling;
7. retry, duplicate-alert, expiry, and deletion behavior;
8. privacy and billing controls.

Recommended public architecture:

```text
Android app
    ↓ authenticated HTTPS
Sarah backend
    ├── user deal-watch records
    ├── scheduled fare checks
    ├── provider adapter
    ├── price-history/deal rules
    └── push-notification service
```

Until that exists, Sarah may open live fare-search pages and help compare results, but must not claim that she is watching prices in the background.

---

## 5. Building the APK

The workflow is:

```text
.github/workflows/build-apk.yml
```

It performs:

1. repository checkout;
2. Java 17 setup;
3. automatic Smart/Local routing test;
4. Travel Brain conversation regression test;
5. conservative memory regression test;
6. debug signing-key cache restore;
7. Gradle setup;
8. Android debug build;
9. artifact rename and upload.

Expected artifact:

```text
Sarah-Morgan-1.0-travel-brain
```

Expected extracted APK:

```text
Sarah-Morgan-1.0-travel-brain.apk
```

Phone-only build route:

1. Open the repository on GitHub.
2. Open **Actions**.
3. Open the newest **Build Sarah Android APK** run.
4. Wait for a green check.
5. Download only `Sarah-Morgan-1.0-travel-brain`.
6. Extract the artifact ZIP.
7. Install the APK.
8. Open Settings and verify `Build 1.0-travel-brain`.

A debug APK is for private testing. A public release requires release signing, a privacy policy, backend protection, billing controls, broader device testing, accessibility review, and store compliance.

---

## 6. Important source files

### Conversation and knowledge

- `MainActivity.java` — chat UI, routing, fallback, voice, photo entry points.
- `TravelBrainCore.java` — structured offline travel dialogue.
- `TravelKnowledgeBase.java` — stable offline destination knowledge.
- `DestinationParser.java` — multi-destination extraction and canonical names.
- `DemoSarah.java` — general local fallback after Travel Brain routing.
- `MemoryExtractor.java` — conservative approved-memory candidates.
- `SarahPromptBuilder.java` — connected-model identity and context.

### Connected providers

- `ConnectedModelGateway.java` — provider-neutral dispatch point.
- `OpenAIClient.java` — included connected adapter.
- `SecureStore.java` — Android Keystore-backed credential encryption.
- `ConnectivityMonitor.java` — validated connection changes.

### Local data and tools

- `SarahDatabase.java` — profile, messages, memories, trips, wishes, photos.
- `TravelNotebookActivity.java` — readable review of trips, memories, and deal requests.
- `CalmSupport.java` — turbulence, grounding, and trivia support.
- `ImageSanitizer.java` — re-encoded local photo copy without ordinary EXIF/GPS metadata.
- `SarahTts.java` — Android text-to-speech.

---

## 7. Adding or changing offline knowledge

Edit:

```text
app/src/main/java/com/kiraworld/sarahtravel/TravelKnowledgeBase.java
```

Add a new `Entry` with:

```java
add(new Entry(
    "Destination name",
    "stable historical framing",
    "first-visit structure",
    "transport considerations",
    "practical and accessibility considerations",
    "family media atmosphere",
    "adult media/documentary atmosphere"));
```

Also add aliases in:

```text
DestinationParser.java
```

Rules for offline entries:

- use stable background knowledge;
- distinguish history from fiction;
- do not hardcode current prices or schedules;
- say when a live check is needed;
- avoid reducing a destination to one stereotype;
- include walking, sensory, weather-pattern, or accessibility considerations when useful;
- keep child and adult media suggestions separate;
- add a regression test for new parsing behavior.

---

## 8. Changing the connected model

### Change the model within the existing provider

Open Sarah Settings and change **Model name**. The value is saved under:

```text
model
```

Before changing the default, verify that the selected model supports every feature Sarah may request. Text, images, tool use, and web research are separate capabilities.

### Add Claude or another provider

Claude is not implemented in this repository, but the extension point already exists.

1. Create a provider client, for example:

```text
app/src/main/java/com/kiraworld/sarahtravel/ClaudeClient.java
```

2. Give it a method compatible with the gateway inputs:

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

3. Translate Sarah's system prompt, user/assistant history, current message, and optional JPEG into the provider's current documented format.
4. Return only Sarah's public response text.
5. Add a branch in `ConnectedModelGateway.java`:

```java
if ("anthropic".equals(normalized) || "claude".equals(normalized)) {
    return ClaudeClient.respond(
        apiKey, model, systemPrompt, history,
        message, webSearch, imageJpeg);
}
```

6. Add a provider selector to Settings and save a stable ID such as `openai` or `anthropic`.
7. Separate encrypted provider credentials or use a protected backend.
8. Test text, multi-turn continuity, image input, timeouts, errors, automatic local fallback, age behavior, and no transmission in Local-only mode.
9. Do not label a provider feature as live web research unless a real provider tool or backend supplies it.

For a public app, provider keys should live on a protected backend rather than inside the APK or a shared client configuration.

---

## 9. Renaming Sarah

A partial rename is not acceptable. The launcher, onboarding, voice, prompt, and conversation identity must agree.

Search the repository for:

```text
Sarah
Sarah Morgan
sarah
```

Review at least:

- `AndroidManifest.xml` application label;
- `activity_main.xml` title;
- `activity_onboarding.xml` title and button text;
- `activity_settings.xml` labels;
- `OnboardingActivity.java` greetings and prompts;
- `DemoSarah.java` identity response;
- `SarahPromptBuilder.java` connected identity instructions;
- `SarahTts.java` and cloud voice instructions;
- README and workflow artifact names;
- icon letters or identity artwork;
- database, preference, and Keystore names before changing technical identifiers.

Internal Java class names can remain `SarahDatabase`, `SarahTts`, and similar if the team wants a cosmetic rename only. Changing the application ID, database filename, preferences, or Keystore alias is a migration, not a simple text replacement, and may create a separate Android app or make old encrypted data unreadable.

Add a test that checks the new visible identity and confirms that the old name is not spoken unexpectedly.

---

## 10. Privacy and truth rules

- Ordinary conversation is not automatically proof of a saved memory.
- Memory extraction should remain conservative.
- Vague pronoun statements should not become hard facts.
- A saved request is not proof that an external action occurred.
- Sarah must not claim that she booked, paid, called, tracked, or monitored something without runtime evidence.
- Local-only mode must not send conversation data to a provider.
- Selected photos are re-encoded before local storage so ordinary EXIF/GPS metadata is not copied.
- A public release should provide record deletion, export/import, account controls, and a clear privacy policy.

---

## 11. Testing before release

Minimum private-test matrix:

- fresh onboarding;
- upgrade from the prior debug APK;
- Automatic online response;
- loss and restoration of connectivity;
- missing and invalid model key;
- connected provider timeout;
- Paris/London screenshot regression;
- vague-memory rejection;
- flexible-date and light-luggage memory;
- deal-request notebook display;
- first-flight and turbulence tools offline;
- child, teen, and adult media behavior;
- microphone permission and recognition;
- Android TTS availability;
- photo picker and cleaned local copy;
- screen rotation and keyboard layout;
- accessibility labels and large text;
- artifact name and visible build number.

Current status: the pure-Java Travel Brain and memory regression tests pass locally. The Android APK still requires a successful GitHub Actions build and physical-phone testing after merge.
