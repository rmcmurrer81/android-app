# Sarah Morgan Android Companion

Sarah Morgan is a phone-first Android travel companion and general conversational companion. She is intended to remain useful without internet, become substantially more capable when Smart mode is connected, remember useful details with permission, speak aloud, accept push-to-talk input, help during first-flight anxiety or turbulence, play personalized trivia, suggest destination-related media, and discuss selected trip photographs when image-capable Smart mode is available.

Current Android version: **0.7-conversational**  
Current private-test application ID: `com.kiraworld.sarahtravel.debug`

This repository is a development prototype. It is not yet ready for public app-store distribution.

---

## 1. What changed in version 0.7

Version 0.7 was created after an older APK artifact caused three confusing regressions during phone testing.

### Conversational first setup

The first-run experience is no longer a large form. Sarah asks one question at a time inside a chat screen:

1. What name should she use?
2. How old is the person, or what year were they born?
3. Where are they from?
4. Is flying new, or have they flown before?
5. What do they enjoy?
6. Are there travel worries, sensory needs, or accessibility needs?
7. May Sarah remember useful information on the phone?

Each answer is accepted by typing or push-to-talk. Optional questions can be skipped. The onboarding header must display `First conversation • v0.7`.

### Clear Local and Smart modes

The status line directly below Sarah's name must say either:

- `Local mode • tap to switch`
- `Smart mode • tap to switch`

Tapping that line opens the mode chooser. The same setting is also available through the wrench icon.

The words **Demo mode** should never appear in version 0.7. Seeing Demo mode means an older APK was installed.

### Better Local conversation

Local mode now uses:

- the current profile;
- recent conversation turns;
- stored memories;
- planned and past trips;
- wish-list destinations;
- age group;
- hometown and interests.

It also has direct local answers for conversational follow-ups such as:

- `Good`
- `Tell me about Paris`
- `Any days work` after discussing flight fares
- `What do you remember about me?`
- first-flight worries and turbulence
- destination movie and book suggestions

Local mode is still a deterministic offline conversation engine, not a full language model. It should be warm, useful, and context-aware, but Smart mode is the route for deeper open-ended conversation, image understanding, and current research.

### Unmistakable launcher icon

Version 0.7 uses a new launcher resource named:

```text
app/src/main/res/drawable/ic_sarah_launcher_v2.xml
```

The Android manifest points directly to that resource for both normal and round icons. The icon combines:

- a cream `S` and speech-bubble tail;
- lavender conversation dots and navigation arcs;
- a sky-blue travel arrow;
- a dark slate background.

The old plain purple `S` belongs to an earlier APK. Some launchers cache icons, so uninstalling the old test build before installing 0.7 may be necessary.

### Visible version check

The Settings screen must show:

```text
Build 0.7-conversational
```

This is the fastest way to verify that the correct APK is installed.

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
    ├── IN_FLIGHT_CALM_AND_AGE_AWARE_MEDIA.md
    ├── PHONE_FIRST_ARCHITECTURE.md
    ├── SARAH_VOICE_DESIGN.md
    ├── TEST_REPORT.md
    ├── android-app/
    │   ├── app/
    │   │   └── src/main/
    │   ├── build.gradle
    │   ├── gradle.properties
    │   └── settings.gradle
    ├── desktop_optional/
    └── tests/
```

The actual Gradle project root is:

```text
Sarah_Morgan_Android_Phone_First_v3/android-app/
```

The GitHub Actions workflow uses that nested path explicitly.

---

## 3. Main Android components

### `MainActivity.java`

The primary chat screen. It:

- displays conversation history;
- accepts typed messages;
- launches push-to-talk recognition;
- accepts a selected photo;
- routes a turn to Local or Smart mode;
- speaks Sarah's reply;
- records chat history;
- invokes memory extraction;
- opens the mode chooser;
- starts Calm & Trivia, Settings, and the Travel Notebook.

Provider failures should not become Sarah's spoken dialogue. When Smart mode fails, the app should fall back to a Local response and show the technical problem as a small Android notification.

### `OnboardingActivity.java`

Runs only when no profile exists. It implements the one-question-at-a-time first conversation. It parses natural answers such as:

- `I'm Robert`
- `I was born in 1981`
- `I've flown before`
- `Flying is new to me`
- `skip`
- `yes` or `no`

It saves the completed profile to SQLite and optionally creates initial reviewed profile memories.

### `DemoSarah.java`

The Local mode conversation engine. The historical class name remains `DemoSarah`, but the user-visible name is Local mode.

It is deterministic Java code designed to remain available without a network connection. It handles:

- greetings and simple social replies;
- profile and memory questions;
- first-flight support;
- turbulence support;
- personalized travel follow-ups;
- fare-search preparation;
- stable destination descriptions;
- age-aware movie and book suggestions;
- travel planning structure;
- ordinary non-travel conversation fallbacks.

To improve Local mode, add narrow, testable conversational routes rather than one giant generic response. Preserve recent-history and memory grounding and do not invent live prices, current hours, or events.

### `OpenAIClient.java`

Private-test Smart mode adapter using the Responses API. It sends:

- Sarah's system instructions;
- recent conversation history;
- the current message;
- an optional JPEG image;
- an optional web-search tool request.

The request uses `store=false`.

A public release must not rely on users pasting a developer-owned key into the APK. It should use a protected backend with authentication, rate limits, billing controls, abuse monitoring, and provider-key isolation.

### `SarahPromptBuilder.java`

Builds the Smart mode identity and grounding prompt from:

- age and age group;
- hometown;
- interests and worries;
- memories;
- trips and wish-list places;
- image presence;
- live-search availability.

Age, desire, consent, current facts, memories, and fictional media must remain separate concepts.

### `SarahDatabase.java`

SQLite database containing:

- `profile`
- `messages`
- `memories`
- `trips`
- `wish_list`
- `photos`

Ordinary chat messages are records, not automatically trusted memories. `MemoryExtractor` identifies limited candidate facts and stores them only when memory permission is active.

### `TravelNotebookActivity.java`

Displays the profile, memories, past and planned trips, and wish-list destinations. Record-by-record editing, deletion, export, and import should be expanded before public release.

### `SarahTts.java`

Uses Android Text-to-Speech for Sarah's default voice. This can work without a paid voice provider when the phone has a suitable voice installed.

### `CloudVoiceClient.java`

Optional private-test cloud speech route. It should fall back to Android TTS if cloud speech fails.

### `CalmSupport.java`

Provides offline turbulence support, grounding, and personalized trivia. It must never claim to diagnose the aircraft or override cabin-crew instructions.

### `ImageSanitizer.java`

Reads a selected image and creates a new JPEG copy for Sarah. Re-encoding avoids copying ordinary EXIF and GPS metadata into Sarah's stored version.

---

## 4. Conversation modes

### Local mode

Purpose:

- work without internet;
- provide fast, private, predictable support;
- preserve profile, memories, trips, trivia, and calm tools;
- answer a growing set of common travel and social conversations.

Local mode limitations:

- no genuine image understanding;
- no verified live fares;
- no current opening hours, weather, events, or closures;
- no unlimited open-ended reasoning;
- destination knowledge is only what is explicitly implemented and tested.

Local mode should not apologize repeatedly or call itself dumb, limited, a demo, or a smaller brain. It should answer naturally and mention Smart mode only when a capability truly requires it.

### Smart mode

Purpose:

- deeper open-ended conversation;
- image understanding;
- optional live web research;
- broader destination and media knowledge;
- more natural continuity across unusual questions.

Smart mode currently requires a personal API key in this private prototype. The key is encrypted with Android Keystore through `SecureStore.java`.

If Smart mode cannot connect, the app should:

1. preserve the person's message;
2. produce a Local mode answer;
3. show a small technical fallback notice;
4. avoid putting raw exception text into Sarah's spoken reply.

---

## 5. Age-aware behavior

The app stores the person's age locally and derives an age group:

- child: under 13
- teen: 13–17
- adult: 18 or older

Age affects:

- media suggestions;
- trivia difficulty and content;
- tone and vocabulary;
- handling of unknown guest speakers;
- family-friendly defaults.

Example for Paris:

- child: *Miraculous Ladybug*, *Ratatouille*, or *Hugo*
- adult: *Amélie*; mature action such as *John Wick: Chapter 4* only when interests support it, with a clear statement that it is violent fiction and not travel guidance

Fiction is atmosphere, not proof about the real destination.

---

## 6. Voice and speech input

### Android voice

The default route uses Android TTS. The exact voice depends on the phone's installed speech engine. The person can change speech speed in Settings.

### Optional cloud voice

A connected speech provider can give Sarah a more consistent voice. The current design target is:

> Warm, calm, natural adult voice. Emotionally present and reassuring without sounding clinical or overly cheerful. Medium-slow pace with ordinary conversational variation.

Do not imitate a real person without authorization and provider consent requirements.

### Push-to-talk

The microphone button launches Android speech recognition only after the person taps it. The app is not designed as an always-listening microphone service.

---

## 7. Photo flow and privacy

1. The person taps the camera icon.
2. Android's picker grants access to the selected image, not the entire gallery.
3. `ImageSanitizer` decodes and re-encodes the image as JPEG.
4. Sarah stores the cleaned local copy and caption.
5. Local mode can save and associate the picture but cannot inspect its visual content.
6. Smart mode can receive the cleaned image for composition, lighting, mood, and respectful next-photo suggestions.

Before public release, add:

- deletion controls;
- retention settings;
- clear cloud-upload consent;
- an image-size cap;
- a privacy-policy explanation of provider processing.

---

## 8. Building the APK with GitHub Actions

Workflow:

```text
.github/workflows/build-apk.yml
```

The current artifact must be named:

```text
Sarah-Morgan-0.7-conversational
```

Phone-browser build steps:

1. Open the repository.
2. Open **Actions**.
3. Select **Build Sarah Android APK**.
4. Open the newest run associated with the 0.7 commits.
5. Wait for a green check.
6. Download the artifact `Sarah-Morgan-0.7-conversational`.
7. Extract the downloaded ZIP.
8. Install `Sarah-Morgan-0.7-conversational.apk`.

The workflow caches `~/.android/debug.keystore` using a stable cache key. This improves the chance that later private-test builds install as updates instead of requiring a full uninstall.

Because older artifacts may have used a different debug signing key, installing 0.7 may still require uninstalling the current Sarah test app once. Uninstalling erases the current test database.

---

## 9. Verifying the correct APK

After installing 0.7, verify all four items:

1. The first screen is a chat conversation, not a large form.
2. The onboarding header says `First conversation • v0.7`.
3. The main header says Local or Smart mode, never Demo mode.
4. Settings says `Build 0.7-conversational`.
5. The launcher icon is the dark slate travel/conversation design, not the plain purple `S`.

If any one of these is wrong, the phone is running an older APK.

---

## 10. Local development

Requirements:

- JDK 17
- Android SDK 35
- Gradle 8.13 or a compatible wrapper setup
- Android device or emulator running Android 8.0/API 26 or later

From the repository root:

```bash
gradle -p Sarah_Morgan_Android_Phone_First_v3/android-app :app:assembleDebug
```

Expected APK:

```text
Sarah_Morgan_Android_Phone_First_v3/android-app/app/build/outputs/apk/debug/app-debug.apk
```

Recommended team workflow:

1. create a branch for one feature;
2. make the smallest coherent change;
3. add or update tests;
4. build the debug APK;
5. install and test on a physical phone;
6. document behavior changes;
7. open a pull request rather than editing main blindly.

---

## 11. Testing checklist

### First run

- one question appears at a time;
- age accepts an age or birth year;
- optional answers can be skipped;
- voice input works when permission is granted;
- memory consent is respected;
- completed onboarding opens MainActivity.

### Conversation

- `Hi` receives a natural greeting;
- `Good` receives a direct social reply;
- `Tell me about Paris` receives actual Paris information;
- Local mode does not say Demo mode or smaller brain;
- Smart mode fallback does not speak exception text;
- recent follow-ups use recent conversation context.

### Modes

- tapping the status line opens the chooser;
- Local mode works without a key;
- Smart mode refuses cleanly when no key exists;
- Settings saves provider and voice selections;
- the mode survives closing and reopening the app.

### Voice and photos

- TTS initializes and shuts down correctly;
- speech speed changes;
- microphone permission flow works;
- one selected photo is sanitized;
- Local mode does not claim to see the photo;
- Smart mode receives the image only when selected.

### Calm and trivia

- turbulence support keeps crew instructions primary;
- trivia uses age and remembered interests/destinations;
- the game can be stopped;
- serious flight conditions do not get treated as a game.

### Installation

- correct icon appears after a clean install;
- Settings shows 0.7;
- app update preserves data when signing keys match;
- uninstall warning is understood when keys do not match.

---

## 12. Known limitations

- Local mode is a hand-built deterministic conversation engine.
- Smart mode currently uses a direct personal-key prototype route.
- Deal searching opens research paths but does not autonomously purchase tickets.
- There is no protected multi-user backend.
- There are no background push notifications for deal watches yet.
- Travel Notebook editing and deletion need expansion.
- Encrypted export/import and account recovery are not complete.
- Broader accessibility testing is required.
- The app has not undergone a professional mobile-security review.
- Public child use would require additional parental, privacy, content, and legal design.

---

## 13. Public-release requirements

Before Play Store or broad distribution:

- release signing key and protected key-management process;
- protected backend for model and speech providers;
- authentication and account recovery;
- clear privacy policy and terms;
- data deletion and export;
- parental/age design review;
- rate limits and billing controls;
- crash reporting with privacy filtering;
- accessibility review;
- dependency and security scanning;
- broader device testing;
- moderation and abuse-response plan;
- accurate disclosures for AI-generated speech and content.

---

## 14. Truth and product boundaries

Sarah may plan, suggest, compare, remember, explain, and open research links. She must not claim that she:

- purchased a ticket;
- booked a hotel;
- contacted an airline;
- verified a live price while offline;
- saw a photo in Local mode;
- diagnosed turbulence or a medical condition;
- remembered something that was never stored;
- knows another person's private thoughts.

Conversation, memory, factual trip state, external actions, and provider capabilities should remain separate.

---

## 15. Reporting a bug

A useful bug report includes:

- installed build number from Settings;
- phone model and Android version;
- Local or Smart mode;
- exact message typed;
- expected reply;
- actual reply;
- screenshot with private information removed;
- whether the app was updated or clean-installed;
- whether the problem repeats after reopening.

Never post API keys, private trip records, full personal memory databases, or unredacted private photos in a public issue.
