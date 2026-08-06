# Sarah Morgan Android Companion

Sarah Morgan is a phone-first Android travel and general conversational companion. The repository is the authoritative hackathon source and contains the Android project, tests, GitHub Actions workflow, model/provider instructions, public-source tools, event monitoring, travel planning, booking intake, and generated APK artifacts.

Current Android version: **1.5-builtin-openai-media**  
Private-test application ID: `com.kiraworld.sarahtravel.debug`

This is a development prototype, not a public app-store release.

## The most important 1.5 change

People who install Sarah are **not** asked to:

- choose OpenAI, Claude, or another provider;
- type a model name;
- paste an API key;
- configure a model endpoint.

The hackathon build is source-configured for:

```text
Provider: OpenAI
Model: gpt-5.1
```

The team controls those values in:

```text
Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel/SarahModelConfig.java
Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel/ConnectedModelGateway.java
```

Detailed provider and model instructions—including exactly how to replace OpenAI with Claude—are in:

```text
MODEL_PROVIDER_CONFIGURATION.md
```

## Connection states

Sarah uses Automatic mode by default.

| Phone and build state | Sarah's route |
|---|---|
| Internet + team OpenAI backend or private build key | Full OpenAI conversation, images, and configured tools |
| Internet but no team OpenAI connection in the APK | Public official-event lookup, public background references, maps, media, routes, then Local fallback |
| No internet | Local Travel Brain, memory, saved trips, calm tools, and offline state |
| Local only selected | No model or public lookup calls |

A full team-connected build says:

```text
Automatic • OpenAI online
```

A build without the team connection says:

```text
Automatic • Public web online • OpenAI not included in this build
```

Neither state asks the app user for a key.

## Recommended OpenAI architecture

The recommended design is:

```text
Sarah Android app
    -> authenticated HTTPS
Sarah team backend
    -> OpenAI Responses API
```

The reference client is:

```text
SarahBackendClient.java
```

A runnable FastAPI example is included at:

```text
backend_examples/openai_proxy/
```

The GitHub Actions build recognizes:

```text
SARAH_MODEL_BACKEND_URL
SARAH_MODEL_BACKEND_TOKEN
```

A private hackathon shortcut can also use:

```text
SARAH_OPENAI_API_KEY
```

That direct-key shortcut places a credential in the private test APK and is not safe for a public release. A protected backend is strongly preferred.

## Event understanding and follow-up context

Version 1.5 fixes the phone failure where an event name was saved as though it were a city and a short follow-up such as `When is it?` lost the event context.

Known event aliases now include:

- Bell County Comic Con, including the common `Bell Country` typo;
- PopCon Indy, including `Indy Pop Con` and `Indy PopCon`;
- CES;
- San Diego Comic-Con;
- New York Comic Con.

Example:

```text
Person: I am thinking about going to Indy Pop Con.
Sarah: recognizes PopCon Indy in Indianapolis, saves an event-centered record,
       checks the official page while online, and shows event media.

Person: When is it?
Sarah: carries forward the most recent recognized event and checks the official
       source instead of replying with a generic scripted sentence.
```

Important files:

```text
KnownEventCatalog.java
EventTripIntentParser.java
PublicOnlineFallback.java
OfficialEventPageLookup.java
EventResearchCoordinator.java
EventTripStore.java
```

Known official pages are checked directly before optional model enrichment. Unknown events can still be searched through OpenAI current-source tools or the public Explore search.

## Visible media in the chat

Earlier versions only showed a text button promising media. Sarah 1.5 retrieves and displays an actual public Wikimedia Commons thumbnail in the permanent media panel when a relevant place or recognized event is active.

The panel contains:

- a public photo preview;
- the active place or event name;
- Map;
- more public Photos;
- Videos;
- Route and local transit;
- Official event page or public web search;
- Live travel sources.

The preview follows the most recent active event through short follow-ups. After discussing PopCon Indy, typing `When is it?` does not replace the panel with a meaningless search for the phrase “When is it.”

Core files:

```text
ExploreButton.java
PublicMediaGateway.java
TravelMediaHelper.java
TravelSearchHelper.java
TravelExplorerActivity.java
```

Public media and external services do not endorse Sarah. The image preview is contextual; it is not proof of current access, opening hours, event dates, safety, or accessibility.

## General conversation outside travel

Sarah is designed to discuss ordinary subjects without turning everything into a trip. OpenAI handles broad natural conversation when the team connection is included. The Local fallback also has explicit paths for:

- greetings and ordinary check-ins;
- movies, television, books, comics, and games;
- AI, computers, robotics, programming, and project ideas;
- emotions and distraction without forcing a travel workflow;
- approved-memory questions;
- clearly phrased public background questions while online.

`DemoSarah.java` is a fallback, not a replacement for a full language model. Do not try to make it intelligent by adding hundreds of overlapping phrase checks. Add structured intents, source tools, or a connected model at the correct architecture layer.

## Public factual lookup without OpenAI

While online, Sarah can use narrowly scoped public sources even when the team OpenAI connection is absent.

Supported paths include:

- recognized official-event pages;
- filming-location questions;
- selected `who is`, `what is`, `tell me about`, `explain`, and `where is` background questions through public reference pages;
- maps, public photos, videos, routes, and public search.

This is background lookup, not an unrestricted autonomous web agent. Rapidly changing facts still need official current sources or the team model/backend.

## Travel methods

Sarah does not assume every journey is a flight. Recognized method families include:

- air;
- Amtrak or other rail;
- intercity bus;
- local metro, subway, light rail, and transit;
- driving;
- ferry;
- bicycle;
- walking;
- mixed routes.

A route such as:

```text
cross-country train from New York to California
```

is one journey, not two destinations to compare. Old Paris or New York wish-list context must not leak into it.

Multimodal state is stored separately in:

```text
sarah_mobility.db
```

Detailed architecture:

```text
MULTIMODAL_TRAVEL_AND_VISUAL_EXPLORER.md
```

## Event-centered trips

Natural statements can create monitored event trips without a long form:

```text
I am going to Vegas for CES.
I am going to San Diego for Comic-Con.
I was thinking about taking metro to New York Comic Con.
I am thinking about going to Indy Pop Con.
```

Sarah can store:

- event name and canonical destination;
- venue and official URL;
- verified dates and hours when available;
- source-backed updates;
- nearby food and places;
- transportation and accessibility notes;
- monitoring status and check times.

Event and booking state is stored separately in:

```text
sarah_event_trips.db
```

Detailed documentation:

```text
EVENT_TRIPS_AND_BOOKING_IMPORTS.md
```

## Booking links and screenshots

Sarah is an Android Share target for `text/plain` and `image/*`.

A traveler can share:

- an Expedia, Booking.com, airline, hotel, rail, car, or event-booking link;
- a visible booking screenshot from the Gallery.

The app:

- stores the user-selected item as a pending import;
- does not sign in to a private account;
- does not reuse cookies or ask for a booking password;
- decodes and re-encodes screenshots so ordinary EXIF/GPS metadata is not copied;
- sends a selected screenshot only when an image-capable team model is available;
- marks extracted details as `needs_confirmation`.

Model output is never proof that a booking exists.

## Memory and truth rules

Sarah separates:

1. conversational text;
2. approved personal memories;
3. trip and event planning state;
4. confirmed booking facts;
5. source-backed current information;
6. unverified suggestions or extracted candidates.

A spoken claim must not create a fake booking, notification, event monitor, knowledge pack, or deal watch. Durable actions pass through application stores and executors before Sarah may describe them as saved.

## Background monitoring

Android `JobScheduler` handles background work. The operating system may delay jobs for battery, standby, or connectivity reasons, so a requested interval is not an exact alarm.

Real automatic airfare, Amtrak, bus, transit, driving, ferry, schedule, delay, and price notifications require team-owned lawful data sources or a protected backend. Sarah must not invent current prices or schedules.

## Main source structure

```text
Sarah_Morgan_Android_Phone_First_v3/
├── android-app/
│   └── app/src/main/java/com/kiraworld/sarahtravel/
├── tests/
└── ...

.github/workflows/build-apk.yml
MODEL_PROVIDER_CONFIGURATION.md
MULTIMODAL_TRAVEL_AND_VISUAL_EXPLORER.md
EVENT_TRIPS_AND_BOOKING_IMPORTS.md
PUBLIC_WEB_FALLBACK.md
backend_examples/openai_proxy/
```

Important Android classes:

| Class | Role |
|---|---|
| `MainActivity` | Chat, voice, photo input, model/public/local routing, and visible replies |
| `SarahModelConfig` | Team-owned OpenAI provider and model configuration |
| `ConnectedModelGateway` | Provider routing point |
| `OpenAIClient` | Direct Responses API implementation |
| `SarahBackendClient` | Protected provider-router client |
| `SarahPromptBuilder` | Sarah's identity and behavioral instructions |
| `AgenticTravelPlanner` | Converts natural statements into useful durable actions |
| `AgenticActionExecutor` | Applies actions to real stores |
| `TravelContextResolver` | Keeps current subjects separate from stale trips |
| `PublicOnlineFallback` | No-key official/public lookup with recent-event context |
| `ExploreButton` | Real inline public image preview and media tools |
| `TravelNotebookActivity` | Visible evidence of memories, trips, events, bookings, and watches |

## Renaming Sarah

A cosmetic rename requires reviewing every user-visible identity surface. Search for:

```text
Sarah
Sarah Morgan
sarahtravel
SarahMorgan
```

Check the Android label, icon, onboarding, greetings, prompt identity, Local identity, voice text, notifications, documentation, workflow artifact names, and backend identifiers.

Changing the application ID, database filenames, preferences, Keystore aliases, or notification channel IDs requires a migration plan.

## Building and testing

The workflow is:

```text
.github/workflows/build-apk.yml
```

Expected artifact:

```text
Sarah-Morgan-1.5-builtin-openai-media
```

Expected APK inside the GitHub Actions artifact:

```text
Sarah-Morgan-1.5-builtin-openai-media.apk
```

Verify in Settings:

```text
Build 1.5-builtin-openai-media
```

The workflow tests:

- automatic OpenAI/public/local routing policy;
- active travel context;
- multimodal routes;
- event, booking, and agentic planning;
- Bell County and PopCon Indy aliases;
- Android compilation;
- APK creation.

Physical-phone review should include:

1. install as an update over 1.4;
2. verify no API-key or model-name field exists;
3. mention `Indy Pop Con` and confirm PopCon Indy/Indianapolis recognition;
4. ask `When is it?` and confirm official-event context carries forward;
5. wait for the inline public photo preview;
6. tap the panel and test Map, Photos, Videos, Route, and Official Source;
7. test a non-travel conversation;
8. test internet loss and recovery;
9. test large text and screen rotation;
10. verify old Bell Country and Indy PopCon malformed records are repaired rather than duplicated.

## Known boundaries

- A build cannot use full OpenAI conversation unless the team supplies a backend or private build credential.
- Public lookup is useful but narrower than a language model.
- Official page parsers may require maintenance when websites change.
- Wikimedia may not have a perfect event-specific image; the preview falls back to the event destination.
- External sites may work better in the phone browser than an embedded WebView.
- Android background work is not exact.
- A debug APK is not production-signed.
- A public release requires authentication, privacy/deletion controls, source documentation, billing/rate controls, security review, broader testing, and store compliance.
