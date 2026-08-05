# Sarah Morgan — Phone-First Android Companion v0.6

Sarah Morgan is a native Android travel companion and conversational companion. She works in a private Local mode without internet and can switch to a more capable Smart mode when a connected model is configured.

The most detailed technical documentation is now at the repository root:

```text
README.md
```

That developer README explains the architecture, class responsibilities, database schema, build process, mode switching, voice, photo flow, testing, contribution guidance, privacy rules, known limitations, and public-release requirements.

## New in v0.6

### Visible Local / Smart mode switch

The line directly under `Sarah Morgan` now says:

```text
Local mode • tap to switch
```

or:

```text
Smart mode • tap to switch
```

Tap that line to choose:

- **Smart mode** — connected conversation, selected-photo understanding, and optional live web research;
- **Local mode** — private, fast, and available without internet;
- **Open detailed settings**.

Sarah keeps the same local profile, memories, trips, wish list, and chat records in either mode.

### Better Local conversation

Sarah no longer says that Local mode gives her a “smaller brain.” Local conversation now uses recent chat history, saved memories, trips, and wish-list places for bounded follow-ups.

Examples include:

- understanding `Any days work` after a flight-deal conversation;
- remembering the likely destination from recent chat or the wish list;
- acknowledging interests such as Miraculous Ladybug;
- explaining how to switch modes when asked;
- keeping ordinary non-travel conversation open.

If Smart mode cannot connect, Sarah answers locally and Android shows a small notification. Raw technical errors are not inserted into Sarah's speech.

### New adaptive launcher icon

The icon now combines:

- Sarah's `S`;
- a conversation bubble;
- a compass/orbit motif;
- a travel arrow.

Some Android launchers cache old icons. Uninstalling the old debug APK before installing v0.6 may be necessary to see the replacement.

## Existing phone-first features

- No laptop runtime is required after installation.
- First setup asks for name, hometown, age, first-flight status, interests, worries or accessibility needs, and memory permission.
- Sarah can talk about travel or ordinary life.
- Sarah remembers approved preferences, past trips, and wish-list destinations.
- Age-aware destination media suggestions are included.
- Calm support, turbulence support, five-senses grounding, and personalized trivia work locally.
- Android text-to-speech can read replies without a paid voice service.
- Optional cloud voice is available when configured.
- Push-to-talk speech input is included.
- Selected photos are re-encoded as cleaned JPEG copies so ordinary source EXIF/GPS metadata is not copied.
- Smart mode can discuss selected photos when the configured model supports image input.

## Build the APK from a phone

1. Open the repository on GitHub.
2. Open **Actions**.
3. Select **Build Sarah Android APK**.
4. Tap **Run workflow**.
5. Download the completed Sarah artifact.
6. Extract the artifact ZIP.
7. Install the APK.

The Android project is located at:

```text
Sarah_Morgan_Android_Phone_First_v3/android-app/
```

## Important prototype limits

- The APK is a debug build for private testing.
- Local mode does not verify current fares, hours, weather, events, or closures.
- Smart mode currently requires the person's own provider key.
- A public release must use a protected backend rather than shipping a shared key.
- The Travel Notebook still needs record-by-record edit and delete controls.
- Sarah is not an airline, medical professional, or emergency service.
- Cabin-crew instructions override trivia or calm activities.

## Where to begin modifying the code

Main conversation and mode routing:

```text
android-app/app/src/main/java/com/kiraworld/sarahtravel/MainActivity.java
```

Local conversation:

```text
android-app/app/src/main/java/com/kiraworld/sarahtravel/DemoSarah.java
```

Connected prompt:

```text
android-app/app/src/main/java/com/kiraworld/sarahtravel/SarahPromptBuilder.java
```

Database:

```text
android-app/app/src/main/java/com/kiraworld/sarahtravel/SarahDatabase.java
```

Calm and trivia:

```text
android-app/app/src/main/java/com/kiraworld/sarahtravel/CalmSupport.java
```

Age-aware media:

```text
android-app/app/src/main/java/com/kiraworld/sarahtravel/MediaSuggestionEngine.java
```

Read the root `README.md` before changing provider routing, memory, photos, voice, age behavior, or release configuration.
