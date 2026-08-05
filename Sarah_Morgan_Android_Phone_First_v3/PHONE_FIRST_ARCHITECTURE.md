# Phone-First Architecture

## Runs on the phone

- user interface
- onboarding
- Android speech recognition button
- Android text-to-speech
- encrypted personal API credential storage
- local SQLite profile, trips, memories, and messages
- photo selection and local sanitized copy
- demo conversation

## Uses the internet only when selected

- cloud conversation model
- current destination research
- current hours/events/deals
- photo understanding
- optional cloud voice

## No laptop dependency

The installed APK does not call the Python laptop server. The previous laptop package remains in `desktop_optional/` only as an optional alternative.

## Public-release boundary

A private owner can enter their own API key. A public APK should authenticate to a Sarah backend. The backend would hold provider keys, enforce rate limits, support subscriptions or sponsor credits, and run scheduled deal checks without exposing secrets in the phone app.
