# Sarah Morgan — Phone-First Android Companion v0.3

This revision changes Sarah from a laptop-hosted web application into a native Android project designed to become an installable APK.

## What changed

- No laptop runtime is required after the Android app is installed.
- First install asks only for:
  - the person's name;
  - where they are from;
  - age, so Sarah can match tone, media, and trivia appropriately;
  - whether flying is new;
  - optional interests, worries, sensory needs, or accessibility needs;
  - permission for Sarah to remember useful facts.
- Sarah can continue learning from ordinary conversation.
- Sarah can talk about anything, not only trips.
- Sarah remembers likely travel preferences, past trips, worries, and wish-list places in a private phone database.
- Sarah sometimes suggests movies, documentaries, novels, memoirs, history books, or travel books connected to a destination.
- Media suggestions separate factual preparation from fictional atmosphere.
- Sarah can speak replies aloud using Android text-to-speech without a paid service.
- An optional Sarah cloud voice uses a warm, calm voice design through a speech API.
- Push-to-talk speech input is included; it is not always listening.
- A photo picker lets the person choose one picture. Sarah's saved copy is decoded and re-encoded as JPEG so ordinary EXIF/GPS metadata is not copied into Sarah's version.
- With a vision-capable cloud model, Sarah can discuss visible composition and suggest another photo angle, time, or type of location.
- Demo mode works without an API account, but it cannot conduct live research or really see pictures.
- OpenAI Responses mode can support current web research and image input when the person supplies a private API key.


## New in v0.3: calm and trivia on the plane

The app now has a **Calm & Trivia** button in the top bar. It is deliberately local so it can still help when airplane Wi-Fi is unavailable. It offers:

- a short turbulence-support response that reminds the traveler to keep the seat belt fastened and follow the crew;
- a five-senses grounding game;
- multiple-choice trivia based on age, hometown, current/planned destination, wish-list places, and interests.

Sarah does not promise that turbulence is safe or diagnose what the aircraft is doing. If there is smoke, an injury, a severe physical symptom, an evacuation order, or a direct crew instruction, the traveler should follow the crew and seek immediate in-person help rather than continue a game.

## Age-aware destination media

Age is stored in the local profile and supplied to Sarah as an age group. A child going to Paris may receive family-friendly suggestions such as *Miraculous Ladybug*, *Ratatouille*, or *Hugo*. An adult may receive *Amélie* and, only when mature violent action matches their interests, *John Wick: Chapter 4*. Sarah must label fiction as atmosphere rather than travel guidance and must not recommend adult-rated material to a child.

## Important truth about the APK

The complete Android source project is included under `android-app/`, but this environment did not contain the Android SDK or Gradle dependencies needed to compile and device-test the APK. I did **not** rename a ZIP or an untested file as an APK.

The project includes `.github/workflows/build-apk.yml`. A GitHub repository can build the debug APK in the cloud without the owner possessing a laptop. The resulting artifact is named `Sarah-Morgan-debug-apk`.

## Phone-only build route

1. Put the contents of `android-app/` into a GitHub repository.
2. Open the repository's **Actions** tab from a phone browser.
3. Run **Build Sarah Android APK**.
4. When it finishes, download `Sarah-Morgan-debug-apk`.
5. Extract the artifact ZIP and install `app-debug.apk` on the Android phone.
6. Android may ask for permission to install an app from the browser or file manager used to open it.

A debug APK is appropriate for private testing. A public release needs a release signing key, privacy policy, protected cloud backend, billing plan, and broader device/security testing.

## Conversation modes

### Demo mode

Works locally and stores profile, messages, memories, trips, wish-list places, and sanitized photo copies. Its conversation is intentionally limited and it cannot verify current fares, hours, events, books, movies, or attractions.

### OpenAI Responses mode

The person enters their own API key in Sarah's settings. The key is encrypted with Android Keystore and is excluded from ordinary Android backup rules. This private prototype calls the model directly from the phone.

A public app must not contain a shared developer API key. It should call a protected Sarah server that authenticates users and protects provider credentials.

## Sarah's voice

### Default: Android voice

The app uses an installed Android text-to-speech voice. It is free, can work offline when the phone has an offline voice installed, and does not require a model API key. The exact available voice depends on the phone and its speech engine.

### Optional: Sarah cloud voice

The app can call a cloud speech endpoint with the following design:

> Warm, calm, natural adult voice. Emotionally present and reassuring without sounding clinical or overly cheerful. Medium-slow pace with ordinary conversational variation.

The prototype uses the built-in `marin` voice with `gpt-4o-mini-tts`. This is a selected provider voice, not an unauthorized imitation of a real person. A truly custom voice requires separate voice consent, provider eligibility, and additional implementation.

## Media suggestions for a destination

Sarah's core prompt tells her to suggest destination media only when it fits naturally. She should usually offer a small mix such as:

- one documentary, memoir, history, or practical travel source;
- one novel, film, or series that gives the place atmosphere;
- one local-culture, food, architecture, or language source.

She must label fiction as atmosphere rather than a reliable guide and should avoid spoilers unless asked.

## Local data

Sarah stores these on the phone:

- onboarding profile;
- recent chat messages;
- selected memories learned from conversation;
- past, current, and planned trips;
- wish-list destinations;
- sanitized photo copies.

The Travel Notebook screen displays what she remembers. This prototype currently supports review but not deletion buttons for each record; record-by-record edit/delete is a required next revision before a public release.

## Included validation

- The pure-Java memory extractor compiled and passed a small test.
- The pure-Java prompt builder compiled and was checked for destination media guidance.
- The demo first-flight response passed a small test.
- All JSON and XML files were parsed after generation.
- Android-specific source was statically inspected but not compiled against the Android SDK in this environment.
