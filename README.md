# Sarah Morgan Android Companion

Sarah Morgan is a phone-first travel companion and conversational companion for Android. She is designed to remain useful when the phone is offline, become more capable when a connected model is enabled, remember useful travel and personal details with permission, speak aloud, accept push-to-talk input, help with first-flight anxiety, play personalized trivia, suggest destination-related media, and discuss selected trip photographs when Smart mode is available.

Current Android version: **0.6-smart-modes**

This repository is a private-development prototype, not a finished public-store release.

---

## 1. What changed in version 0.6

Version 0.6 fixes three important usability problems:

1. **The conversation mode is now visible in the main chat screen.**
   - The line directly under Sarah's name says either `Local mode • tap to switch` or `Smart mode • tap to switch`.
   - Tapping that line opens a clear mode chooser.
   - The person no longer has to discover the provider selector hidden behind the wrench icon.

2. **Sarah no longer describes Local mode as having a “smaller brain.”**
   - Local replies are written as Sarah speaking naturally.
   - Technical provider failures are shown as a small Android notification, not inserted into Sarah's conversation.
   - If Smart mode cannot connect, Sarah quietly answers locally and the app displays a toast explaining the fallback.

3. **The launcher icon is now a real adaptive Android icon.**
   - The new icon combines an `S`, a conversation bubble, a compass/orbit shape, and a travel arrow.
   - Android 8.0 and later can shape it correctly for Samsung, Pixel, and other launchers.

---

## 2. Repository layout

The repository currently keeps the Android project inside a package folder:

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
    │   ├── build.gradle
    │   ├── gradle.properties
    │   └── settings.gradle
    ├── desktop_optional/
    └── tests/
```

The actual Gradle Android project starts here:

```text
Sarah_Morgan_Android_Phone_First_v3/android-app/
```

The GitHub Actions workflow uses that path explicitly.

---

## 3. Quick build from GitHub Actions

The repository contains:

```text
.github/workflows/build-apk.yml
```

To build from a phone browser:

1. Open the repository on GitHub.
2. Open **Actions**.
3. Select **Build Sarah Android APK**.
4. Tap **Run workflow**.
5. Wait for the build job to finish.
6. Open the completed run.
7. Download the artifact named for the current Sarah version.
8. Extract the artifact ZIP.
9. Install the APK on the Android phone.

For private testing, the workflow builds a debug APK. Android may require permission for the browser or file manager to install unknown apps.

Do not present the debug APK as a store-ready public build. A public release needs release signing, a privacy policy, broader testing, and a protected provider backend.

---

## 4. Local mode and Smart mode

Sarah has one identity and one local memory system. The modes only change how a reply is generated.

### Local mode

Local mode is the default.

It:

- works without internet;
- needs no model account;
- stores the profile, memories, trips, wish-list entries, chat history, and cleaned photo copies on the phone;
- supports first-flight guidance;
- supports turbulence calm support;
- supports personalized trivia;
- can suggest some known destination media;
- can open external live travel-search pages;
- can continue ordinary conversation through the local rule-based conversation engine.

Local mode does not claim to inspect photo pixels, know current prices, or verify live opening hours.

### Smart mode

Smart mode uses the connected model configured in Settings.

It can provide:

- deeper free-form conversation;
- better follow-up handling;
- richer use of Sarah's prompt, memories, trips, and wish list;
- selected-photo understanding;
- optional live web research when the question requires current information;
- broader destination movie, book, attraction, and event suggestions.

Smart mode currently requires a personal API key. The key is stored through Android's secure storage implementation in `SecureStore.java`.

### Switching modes

The easiest route is:

1. Open the Sarah chat screen.
2. Tap the mode line directly under `Sarah Morgan`.
3. Choose **Smart mode**, **Local mode**, or **Open detailed settings**.

Smart mode cannot be enabled until a personal key is stored. Local mode remains available if a provider is unreachable.

---

## 5. Main application flow

The launcher activity is:

```text
app/src/main/java/com/kiraworld/sarahtravel/MainActivity.java
```

At startup:

1. `SarahDatabase` opens the local database.
2. If no profile exists, `OnboardingActivity` opens.
3. If a profile exists, `MainActivity` loads chat history.
4. `SpeakerContext` identifies the current speaker and age context.
5. `SarahTts` prepares Android text-to-speech.
6. The mode line is refreshed from `SharedPreferences`.

When a person sends a message:

1. The message is shown in the chat.
2. It is stored in the local message table.
3. Speaker-switch commands are checked.
4. `MemoryExtractor` may save approved categories if learning is enabled and the person granted memory permission.
5. The app loads recent history, memories, trips, and wish-list entries.
6. Local mode calls `DemoSarah.reply(...)`.
7. Smart mode calls `OpenAIClient.respond(...)`.
8. If Smart mode fails, Local mode creates the reply instead.
9. The reply is saved, displayed, and optionally spoken aloud.
10. Local fare questions can open `TravelSearchHelper`.

---

## 6. Important Java classes

### `MainActivity.java`

Coordinates the main chat screen, mode switching, voice, photo selection, message routing, calm tools, and Smart-to-Local fallback.

### `OnboardingActivity.java`

Collects the first basic profile:

- name;
- hometown or home area;
- age;
- whether flying is new;
- interests;
- worries, sensory needs, or accessibility needs;
- memory permission.

### `SarahDatabase.java`

Owns the SQLite database.

Current tables:

```text
profile
messages
memories
trips
wish_list
photos
```

Current database version: `3`.

### `DemoSarah.java`

The local conversation engine.

It is intentionally honest about what the phone can verify, but it should still speak as Sarah rather than as a diagnostic message. It receives:

- the current message;
- profile information;
- whether a photo is attached;
- recent conversation history;
- saved memories;
- trips;
- wish-list places.

Examples of local behavior include:

- recognizing follow-ups such as `Any days work` after a fare conversation;
- using the home area and remembered destination to form a route;
- acknowledging interests such as Miraculous Ladybug;
- explaining how to switch modes;
- recalling the latest saved memory or wish-list place;
- avoiding unsupported current fare claims.

To improve Local mode, add narrow, testable behavior here rather than inserting random canned paragraphs into `MainActivity`.

### `SarahPromptBuilder.java`

Builds the private instruction context supplied to the connected model. This is where Sarah's identity, age-aware behavior, memory rules, travel role, media guidance, photo behavior, and factual boundaries should be maintained.

### `OpenAIClient.java`

Sends the connected conversation request. It supplies:

- model name;
- Sarah's instruction prompt;
- recent message history;
- the current message;
- an optional selected JPEG;
- an optional web-search tool request.

The code requests that provider-side storage be disabled for the request. The public app should still use a protected server rather than a shared key inside the APK.

### `SecureStore.java`

Encrypts and retrieves the person's private provider key using Android secure storage.

### `SarahTts.java`

Uses Android text-to-speech for the default free voice.

### `CloudVoiceClient.java`

Optional connected speech generation. If it fails, the app returns to Android text-to-speech.

### `SpeakerContext.java`

Supports temporary guest or family speakers and applies family-friendly behavior until an age is known.

### `MemoryExtractor.java`

Extracts a small set of explicit memory candidates from ordinary conversation. Memory learning only runs when:

- the person granted memory permission during setup; and
- the learning checkbox remains enabled.

### `CalmSupport.java`

Contains local in-flight calm guidance, grounding activities, and personalized trivia questions.

### `MediaSuggestionEngine.java`

Contains age-aware media suggestions. A child traveling to Paris may receive Miraculous Ladybug, Ratatouille, or Hugo. Adult suggestions may include Amélie, and mature violent material should appear only when age and interests support it.

### `TravelSearchHelper.java`

Offers external search pages for live fare or travel information. The helper must not claim that an external result was checked until the person actually opens and reviews it.

### `ImageSanitizer.java`

Decodes the selected image and creates a new JPEG inside Sarah's local files. Ordinary EXIF and GPS metadata from the selected source are not copied into Sarah's sanitized version.

---

## 7. Android resources

Main layouts:

```text
app/src/main/res/layout/activity_main.xml
app/src/main/res/layout/activity_onboarding.xml
app/src/main/res/layout/activity_settings.xml
app/src/main/res/layout/activity_travel_notebook.xml
```

Main visual resources:

```text
app/src/main/res/values/colors.xml
app/src/main/res/values/styles.xml
app/src/main/res/drawable/chat_background.xml
app/src/main/res/drawable/chat_sarah.xml
app/src/main/res/drawable/chat_user.xml
app/src/main/res/drawable/composer_background.xml
app/src/main/res/drawable/mode_status_background.xml
```

New launcher artwork:

```text
app/src/main/res/drawable/ic_sarah_foreground.xml
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
```

The existing density PNG files remain as legacy fallback resources. Android 8.0 and newer should use the adaptive icon XML.

Some launchers cache icons. After installing a new APK, a phone may require uninstalling the old debug build or restarting the launcher before the new icon appears.

---

## 8. Voice design

Sarah has two voice routes.

### Android voice

- free;
- uses a voice installed on the device;
- can work offline if the selected Android speech engine has an offline voice installed;
- adjustable speed;
- used as automatic fallback.

### Cloud voice

- optional;
- requires the person's provider key;
- uses Sarah's calm adult voice direction;
- must not imitate a celebrity or real person without permission;
- falls back to Android voice if generation fails.

Voice is output only. Microphone input is push-to-talk through Android speech recognition. Sarah is not always listening.

---

## 9. Photo flow

1. The person taps the camera button.
2. Android's photo picker opens.
3. The person chooses a single image.
4. `ImageSanitizer` writes a cleaned JPEG copy to Sarah's private application directory.
5. The cleaned image is attached to the next message.
6. Local mode stores the image but does not claim to see it.
7. Smart mode may discuss visible content.
8. The photo record stores the local path and the person's caption or question.

Future work should add record-by-record photo deletion, thumbnails, trip grouping, and an explicit export tool.

---

## 10. Memory and identity rules

Sarah is separate from Kira and Lisa. Their memory files are not copied into this app.

Sarah may remember facts learned from the person only when permission is enabled. She should distinguish:

- something said in chat;
- a saved memory;
- a planned trip;
- a completed past trip;
- a wish-list destination;
- a live fact that requires current research.

Conversation history should not silently become a permanent personal fact. New memory patterns should be narrow enough for a person to understand and review.

The Travel Notebook is the current review interface. Before public release, add edit and delete controls for every saved record.

---

## 11. Calm support and turbulence

The Calm & Trivia button works locally so it remains available in airplane mode.

It can:

- stay with the person through turbulence;
- remind them to keep the seat belt fastened;
- remind them to follow crew instructions;
- guide a slower exhale;
- start age-aware personalized trivia;
- start a five-senses grounding activity.

It must not:

- diagnose the aircraft;
- claim that a situation is safe based only on chat;
- override cabin crew;
- continue a game when there is smoke, injury, a severe physical symptom, an evacuation order, or a direct crew instruction.

---

## 12. Destination media suggestions

Sarah may suggest movies, television, novels, memoirs, histories, documentaries, language sources, architecture books, or food sources connected to a destination.

Rules:

- age matters;
- interests matter;
- fiction is atmosphere, not travel guidance;
- spoilers should be avoided unless requested;
- highly violent or adult material must not be suggested to children;
- current availability should be verified in Smart mode or by opening an external source.

To add a new local destination pack, extend `MediaSuggestionEngine.java` and add tests for child, teen, and adult profiles.

---

## 13. Adding a different connected model provider

Create a provider class with a small interface equivalent to:

```java
String respond(
    String credential,
    String model,
    String systemPrompt,
    List<Map<String, String>> history,
    String message,
    boolean allowLiveSearch,
    byte[] optionalJpeg
) throws Exception;
```

Then:

1. add the provider choice to `SettingsActivity`;
2. preserve existing integer provider values or implement a migration;
3. route the provider in `MainActivity.sendCurrent()`;
4. never place a shared secret in source control;
5. preserve Local fallback;
6. document whether the provider supports images and live tools;
7. add error sanitization so raw provider errors do not become Sarah's spoken reply.

---

## 14. Improving Local mode safely

Local mode is not a language model. It is a bounded conversation engine.

Good additions:

- follow-up state based on recent messages;
- destination-specific packs;
- remembered-interest acknowledgements;
- trip checklist helpers;
- packing lists based on saved needs;
- local airport-process explanations;
- trivia packs;
- structured itinerary drafting;
- explicit unknown handling.

Avoid:

- pretending local data is current;
- inventing fares;
- claiming to inspect a photo;
- dumping technical limitations into every conversation;
- using one generic fallback for every subject;
- turning every conversation back into travel planning.

Every new branch should have at least one direct test and one follow-up test.

---

## 15. Testing

At minimum, test:

### Onboarding

- child age;
- teen age;
- adult age;
- memory permission on and off;
- first-flight flag;
- blank optional fields.

### Local conversation

- greeting;
- ordinary non-travel conversation;
- first-flight fear;
- turbulence;
- fare request;
- flexible-date follow-up;
- destination media request;
- mode question;
- saved-memory recall;
- photo attached in Local mode.

### Smart mode

- missing key blocked cleanly;
- valid key selected;
- live-search checkbox off;
- live-search checkbox on;
- image request;
- provider timeout falling back locally;
- raw error not spoken as Sarah.

### Voice

- Android TTS ready;
- Android TTS unavailable;
- cloud voice failure fallback;
- speech speed range;
- auto-speak disabled.

### Privacy

- key absent from ordinary backups;
- selected photo re-encoded;
- no source EXIF copied;
- memory permission honored;
- guest speaker does not overwrite owner profile.

---

## 16. Public-release work still required

Before a public app-store release:

- use a release signing key;
- move provider credentials behind a protected backend;
- implement user accounts only if truly needed;
- write a plain-language privacy policy;
- add delete/export controls;
- add retention settings;
- add accessibility review;
- test screen readers and large text;
- test more Android versions and manufacturers;
- add network-security review;
- add cost controls and usage limits;
- add provider outage behavior;
- add content and age-policy review;
- perform human testing of turbulence support language;
- clearly label that Sarah is not an airline, medical professional, or emergency service.

---

## 17. Troubleshooting

### The new icon does not appear

Uninstall the old debug APK and install the new one. Some launchers cache the old icon.

### Smart mode is not available

Open Settings, enter a personal API key, save, return to chat, and tap the mode line.

### Smart mode answered locally

The connected service could not be reached. Sarah intentionally falls back locally rather than showing a technical error as dialogue.

### Sarah will not speak

Check:

- `Read Sarah's replies aloud` is enabled;
- an Android speech engine is installed;
- phone media volume is up;
- the speech engine has a voice installed;
- cloud voice is not selected without a valid key.

### Push-to-talk does not work

Grant microphone permission and verify that the phone has a speech-recognition service.

### Live fare search opens a warning but no prices appear in chat

That is expected in Local mode. Sarah opens external search pages and refuses to invent prices. Smart mode can research current information when live search is enabled.

### GitHub Actions cannot find the project

The workflow must use:

```text
Sarah_Morgan_Android_Phone_First_v3/android-app
```

The current workflow already supplies that project path to Gradle.

---

## 18. Contribution guidance

For team experiments:

1. create a branch;
2. change one capability at a time;
3. update tests and documentation together;
4. do not commit API keys;
5. do not copy Kira or Lisa private memories;
6. preserve Local fallback;
7. preserve age-aware behavior;
8. keep current facts separate from suggestions;
9. build the debug APK in Actions;
10. record what was actually device-tested.

Useful branch examples:

```text
feature/local-itinerary-builder
feature/trip-photo-gallery
feature/editable-memory-notebook
feature/new-york-trivia-pack
fix/samsung-large-text-layout
```

---

## 19. Project principles

Sarah should feel like a continuing person to talk with, not a fare-search form with a voice.

She should:

- remember carefully;
- admit uncertainty;
- talk about non-travel subjects;
- respond calmly without becoming clinical;
- adapt to age and interests;
- remain useful without internet;
- become more capable when connected;
- never pretend a booking, payment, call, price check, image inspection, or physical action happened when it did not.

That distinction is the foundation for every future feature.
