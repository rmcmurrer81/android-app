# APK Build Status

Current source version: `0.6-smart-modes`

Status: `SOURCE_UPDATED__GITHUB_ACTIONS_BUILD_PENDING`

## What has been physically demonstrated

A prior debug APK was successfully built, installed, and opened on Robert's Samsung Android phone. Screenshots demonstrated:

- the Sarah Morgan launcher entry;
- the main chat screen;
- completed onboarding with Robert's name and profile;
- Local conversation about Paris;
- age-aware Paris media suggestions;
- a live-fare search warning dialog;
- push-to-talk and photo buttons present in the composer.

That physical test exposed three real usability problems:

1. Local and connected behavior were mentioned but the main screen had no obvious mode switch.
2. Local fallback language described Sarah as having a “smaller brain.”
3. The launcher icon still looked like a placeholder.

## Version 0.6 source changes

- Added a visible, tappable `Local mode` / `Smart mode` status line under Sarah's name.
- Added an in-chat mode chooser.
- Smart mode cannot be enabled without a saved personal key.
- Smart provider failure now falls back locally without speaking a raw technical error.
- Local conversation now receives recent history, memories, trips, and wish-list places.
- Added follow-up handling for flexible flight dates.
- Removed the “smaller brain” fallback wording.
- Added a new adaptive launcher icon.
- Added a detailed root developer README.
- Updated settings explanations and provider labels.

## Existing completed source capabilities

- Native Android Java source project
- Phone onboarding
- Age-aware profile
- Local SQLite memory and trip notebook
- General and travel conversation prompt
- Destination movie, book, and media suggestions
- First-flight calming behavior
- Local turbulence support
- Local personalized trivia
- Push-to-talk speech input
- Android text-to-speech output
- Optional cloud voice output
- Connected text, image, and optional web-search adapter
- Photo selection and metadata-stripping re-encode
- GitHub Actions cloud build workflow
- Smart-to-Local fallback

## Still required before calling version 0.6 device-tested

- Build the v0.6 debug APK through GitHub Actions.
- Install it on the physical Android phone.
- Confirm the tappable mode line.
- Confirm Local-to-Smart and Smart-to-Local switching.
- Confirm missing-key behavior.
- Confirm Smart-provider failure fallback.
- Confirm the new launcher icon after uninstall/reinstall if the launcher caches the prior icon.
- Re-test onboarding, accessibility, speech engine, microphone, photo picker, encrypted key, connected model call, web research, photo analysis, and cloud voice.
- Add record editing and deletion controls.
- Add export/import and encrypted backup.
- Add notification-based deal watches only after a reliable travel-pricing source is chosen.
- Replace direct client API-key use with a protected backend for any public release.
