# Sarah Morgan Android Companion

Sarah Morgan is a phone-first Android travel and conversational companion. She can talk about ordinary subjects, remember approved details, support first-time travelers, continue locally when internet disappears, research current trips when a connected model is available, monitor event-centered travel, import user-selected booking links or screenshots, and now plan journeys across more than air travel.

Current Android version: **1.3-multimodal-travel**  
Private-test application ID: `com.kiraworld.sarahtravel.debug`

This repository is the authoritative hackathon source. It contains the Android project, tests, GitHub Actions workflow, developer documentation, and generated APK artifacts. It is a development prototype, not a public app-store release.

## What 1.3 fixes

Earlier local builds could combine old wish-list places, saved trips, and recent conversation into one unrelated response. That caused errors such as comparing Paris and New York when the traveler had asked about a train from New York to California.

Version 1.3 changes the context rules:

1. the current message wins;
2. a short direct follow-up may use only the most recent relevant user message;
3. saved wishes and old trips are not inserted into ordinary replies automatically;
4. `from A to B` is treated as one route with an origin and destination;
5. `I don't know yet`, `not sure yet`, and `undecided` close the travel subject without another question;
6. a new event or journey immediately replaces an older topic.

Important file:

```text
TravelContextResolver.java
```

## Multimodal journeys

Sarah no longer assumes every trip is a flight.

Recognized method families:

- air;
- Amtrak or other rail;
- local metro, subway, light rail, or transit;
- intercity bus;
- driving;
- ferry;
- bicycle;
- walking;
- mixed routes.

Examples:

```text
I would love to take a cross-country train trip from New York to California.
I was thinking about taking metro to New York Comic Con.
Monitor travel options to Paris.
Drive from Austin to San Antonio.
```

Sarah should compare the complete door-to-door trip: price, duration, transfers, baggage, station or airport access, accessibility, reliability, weather, parking, and the local connection at both ends.

A broad watch without a named method defaults to air, rail, and intercity bus where those methods make sense. It must not be described as airfare monitoring only.

Core files:

- `JourneyIntentParser.java`
- `JourneyPlannerCore.java`
- `MobilityWatchStore.java`
- `MobilityGateway.java`
- `MobilityWatchCoordinator.java`
- `MobilityNotificationManager.java`

Multimodal state is stored separately in:

```text
sarah_mobility.db
```

The Travel Notebook shows saved journeys, multimodal watches, backend status, last check time, latest result, and source note.

## Maps, photos, videos, and routes

For relevant trip, route, or event messages, Sarah can open an **Explore this trip** panel with:

- **Map** — OpenStreetMap search;
- **Photos** — Wikimedia Commons MediaSearch;
- **Videos** — YouTube travel-video search;
- **Route and local transit** — Google Maps directions view;
- **Live travel options** — current-source links such as Amtrak, Google Flights, or a route search.

The visual pages open inside `TravelExplorerActivity.java`, with an option to open the source externally.

These are public contextual sources. Their inclusion does not imply endorsement, and they do not prove current opening hours, accessibility, closures, service, construction, weather, or safety.

Detailed architecture:

```text
MULTIMODAL_TRAVEL_AND_VISUAL_EXPLORER.md
```

## Event-centered trips

Natural statements can create durable event trips:

```text
I am going to Vegas for CES.
I am going to San Diego for Comic-Con.
I was thinking about taking metro to New York Comic Con.
I am traveling to Austin for the Future of Travel Conference.
```

Sarah can save the event and destination, monitor source-backed official details, research nearby food and places, preserve the requested transport method, and avoid a long questionnaire.

Event and booking details are stored separately in:

```text
sarah_event_trips.db
```

Detailed documentation:

```text
EVENT_TRIPS_AND_BOOKING_IMPORTS.md
```

## Booking imports

Sarah is an Android Share target for `text/plain` and `image/*`.

A traveler may share:

- an Expedia, Booking.com, hotel, airline, rail, car, or event-booking link;
- a visible booking screenshot from the Gallery.

Links and screenshots are stored as pending imports. Sarah does not sign in to private accounts, reuse cookies, request account credentials, or treat extracted screenshot text as confirmed facts.

Selected screenshots are decoded and re-encoded by `ImageSanitizer`; ordinary EXIF and GPS metadata are not copied. Image-model extraction remains marked `needs_confirmation` until the traveler reviews it.

## Automatic connected and local behavior

Sarah uses Automatic mode by default.

| State | Route |
|---|---|
| Validated internet and saved model key | Connected model |
| No validated internet | Local Travel Brain |
| Internet but no model key | Local Travel Brain |
| Connected request fails | Local Travel Brain for that message |
| Connection becomes usable again | Connected model on the next message |
| Local only selected | Never send conversation to a connected model |

`LiveTravelIntent.java` requests current-source research for route, rail, Amtrak, metro, subway, bus, ferry, driving, traffic, parking, delays, events, weather, maps, and fare questions.

A connected model's training knowledge is not current transport data. Current schedules, prices, closures, delays, routes, events, and weather require real source tools or a protected backend.

## Conversation and durable-action architecture

```text
User message
    ↓
SpeakerContext
    ↓
AgenticTravelPlanner
    ├── public reply plan
    └── durable actions
            ↓
      AgenticActionExecutor
            ├── save wish or focus
            ├── queue destination pack
            ├── save journey plan
            ├── create multimodal watch
            ├── create event trip
            └── save booking link
    ↓
Connected or local reply
```

The spoken reply and durable action are separate. Sarah must not claim that a journey, watch, event monitor, destination pack, or booking import exists unless the appropriate local store was actually updated.

## Low-question policy

Sarah asks only when a missing fact would materially change:

- a booking;
- a legal or entry requirement;
- accessibility planning;
- a safety decision;
- the traveler's explicit goal.

Otherwise Sarah should use reversible defaults, do useful work, explain what she did, and allow corrections later.

She must not repeatedly answer with `tell me more`, `what matters most`, or another generic question when she already has enough to begin.

## Destination knowledge packs

Mentioning a destination can queue a reusable pack containing:

- overview;
- recommended starting points;
- transportation;
- accessibility and sensory notes;
- seasonal context;
- current events;
- source and verification note.

Connected research fills current information. The local pack must not invent prices, opening hours, closures, entry requirements, schedules, or forecasts.

## Travel backend contract

The same authenticated HTTPS backend may accept legacy airfare watches and new multimodal requests.

A multimodal request includes:

```json
{
  "watch_kind": "multimodal",
  "watch_id": 4,
  "origin": "Newark, New Jersey",
  "destination": "California",
  "event_name": "",
  "modes": "rail",
  "purpose": "options",
  "include_price": true,
  "include_schedule": true,
  "include_service_alerts": true,
  "include_station_airport_access": true,
  "include_local_connection": true,
  "include_weather_context": true
}
```

A normalized response may include:

```json
{
  "found": true,
  "significant": true,
  "recommended_mode": "rail",
  "summary": "A current rail combination is available with one transfer. Verify the official timetable before booking.",
  "source_note": "Based on current official carrier and station information.",
  "action_url": "https://example.com/source-backed-result"
}
```

The backend, not the language model, should normalize providers and decide what counts as a meaningful option or change.

## Changing the connected model

The extension point is:

```text
ConnectedModelGateway.java
```

To add Claude, Bedrock, Gemini, or another provider:

1. create a provider adapter matching the gateway inputs;
2. preserve Sarah's system prompt, message history, current message, and optional image;
3. add a stable provider ID and Settings option;
4. keep credentials encrypted or move them behind an authenticated backend;
5. implement real current-source tools before advertising route or event monitoring;
6. support image input before advertising photo or booking-screenshot understanding;
7. test text, multiple turns, images, route research, event JSON, booking JSON, timeouts, fallback, and recovery.

## Renaming Sarah

Search for all user-visible identity strings:

```text
Sarah
Sarah Morgan
sarahtravel
SarahMorgan
```

Review the Android label, icon, onboarding and chat titles, greetings, prompt identity, local identity, settings, voice instructions, notification channels, documentation, workflow artifact names, and backend identifiers.

Internal Java class names may remain for a cosmetic rename. Changing the application ID, database filenames, preferences, Keystore aliases, notification channel IDs, or backend identifiers requires a migration plan.

## Build and tests

Workflow:

```text
.github/workflows/build-apk.yml
```

The pull-request build tests:

- automatic Smart/Local routing;
- active-context resolution;
- old Paris context not leaking into New York-to-California rail;
- `I don't know yet` clearing travel context;
- Paris/London direct comparison follow-ups;
- cross-country Amtrak parsing and response;
- metro to New York Comic Con;
- broad watches including air, rail, and bus;
- Orlando and Universal low-question behavior;
- CES, Comic-Con, booking-link, and destination-pack behavior;
- full Android compilation and APK creation.

Expected artifact:

```text
Sarah-Morgan-1.3-multimodal-travel
```

Expected APK:

```text
Sarah-Morgan-1.3-multimodal-travel.apk
```

Verify in Settings:

```text
Build 1.3-multimodal-travel
```

## Known boundaries

- Local Sarah is a structured fallback, not a complete language model.
- Current route, schedule, fare, delay, service, event, and weather information requires source-backed connected services.
- Android background jobs are not exact clock-time alarms.
- Some public sites may work better in an external browser than an embedded WebView.
- External maps, media, carriers, and travel sites do not endorse Sarah.
- The debug APK is not production signed.
- A public release needs authentication, deletion controls, privacy policy, source attribution, accessibility review, broader device testing, release signing, and store compliance.
