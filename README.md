# Sarah Morgan Android Companion

Sarah Morgan is a phone-first Android travel and conversational companion. She can talk about ordinary subjects, remember approved details, support first-time travelers, continue locally when internet disappears, research destinations when a connected model is available, maintain travel-deal watches through a team backend, monitor event-centered trips, and import user-selected booking links or screenshots.

Current Android version: **1.2-event-aware-travel**  
Private-test application ID: `com.kiraworld.sarahtravel.debug`

This repository contains the source, tests, workflow, documentation, and GitHub Actions APK artifacts. It is a development prototype, not a public app-store release.

## What 1.2 adds

Natural statements can create durable event-centered trips:

```text
I am going to Vegas for CES.
I am going to San Diego for Comic-Con.
I am traveling to Austin for the Future of Travel Conference.
```

Sarah should then:

- remember the event and destination;
- queue ordinary destination research;
- monitor official event dates, venue, schedule and policy changes;
- research transportation, accessibility, nearby food, and nearby places;
- notify only for newly stored, source-backed updates;
- avoid forcing the traveler through a long form.

The Android Share sheet can send Sarah:

- Expedia, Booking.com, airline, hotel, rail, car, or event-booking links;
- user-selected booking screenshots from the Gallery.

A link or screenshot is stored as a **pending import**. Screenshot extraction is a candidate for traveler review, not proof that a booking exists.

Detailed event and booking documentation:

```text
EVENT_TRIPS_AND_BOOKING_IMPORTS.md
```

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

Identity, approved memories, wishes, trips, destination packs, deal watches, monitored events, and booking imports remain local when the route changes.

## Conversation and action architecture

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
            ├── create deal watch
            ├── create event trip
            └── save booking link
    ↓
Connected or local reply
```

The spoken reply and durable action are separate. Sarah must not claim that a watch, event monitor, pack, or booking import exists unless the corresponding store was actually updated.

Important files:

- `AgenticTravelPlanner.java` — low-question intent and action planner.
- `AgenticActionExecutor.java` — applies durable Android actions.
- `DestinationParser.java` — ordinary destination extraction.
- `EventTripIntentParser.java` — event and event-city extraction.
- `BookingLinkParser.java` — conservative booking-link detection.
- `EventTripStore.java` — separate event/update/booking database.
- `DestinationKnowledgeCoordinator.java` — connected destination research.
- `EventResearchCoordinator.java` — official-source-first event research.
- `BookingExtractionCoordinator.java` — visible screenshot-field extraction.
- `DealWatchScheduler.java` and `EventMonitorScheduler.java` — Android background jobs.
- `TravelNotebookActivity.java` — visible evidence of saved work.

## Low-question policy

Sarah asks only when a missing fact would materially change:

- a booking;
- a legal or entry requirement;
- accessibility planning;
- a safety decision;
- the traveler’s explicit goal.

Otherwise she should use reversible defaults, do useful work, explain what she did, and allow corrections later.

If the person says “that is it,” “nothing,” “I don’t care,” or gives one attraction as the full reason for a trip, Sarah accepts the answer and stops questioning them.

## Destination knowledge packs

Mentioning a possible destination can queue a reusable knowledge pack. Connected research may fill:

- overview;
- recommended starting points;
- transportation;
- accessibility and sensory notes;
- seasonal context;
- current events;
- source and verification note.

Current prices, openings, closures, entry rules, schedules, and weather require current sources. Generated packs must not invent them.

## Deal watches

A dream destination or explicit deal request can create a persistent local watch using reversible defaults:

- saved hometown as origin area;
- round trip;
- one traveler;
- carry-on travel;
- flexible dates;
- nearby airports;
- 3–14 nights;
- search horizon up to one year.

Actual fare results require a lawful, authenticated travel-data backend. The phone app does not scrape airlines and must not invent prices.

A backend response may include:

- departure and return dates;
- airports;
- total fare and currency;
- booking link;
- baggage assumptions;
- whether it qualifies as a deal;
- weather context labeled as forecast, climate, or unknown.

Long-range seasonal context must never be described as a confirmed forecast.

## Event monitoring

Event monitoring uses the separate database `sarah_event_trips.db` with:

- `event_trips`;
- `event_updates`;
- `booking_imports`.

`EventMonitorScheduler` uses Android `JobScheduler`. Android may defer work for battery, standby, or connectivity reasons; it is not an exact clock-time alarm.

Suggested event-check cadence:

- more than 120 days away: weekly;
- 61–120 days: every three days;
- 15–60 days: daily;
- within 14 days: approximately every six hours;
- unknown or completed dates: weekly.

Research policy:

- official event site first for official dates, venue, schedule, registration, and policy changes;
- official venue and transit sources when possible;
- reputable public sources for nearby food and places;
- no implication of endorsement;
- no private-account access, cookies, or credentials;
- no invented schedules, prices, or policies.

## Booking imports

`BookingImportActivity` is an Android share target for `text/plain` and `image/*`.

Links:

- known travel providers are recognized automatically;
- generic URLs require explicit booking or reservation context;
- ordinary event-information links are not bookings;
- Sarah never signs in to private itinerary pages.

Screenshots:

- are decoded and re-encoded by `ImageSanitizer`;
- ordinary EXIF and GPS metadata are not copied;
- are sent to an image-capable connected model only when configured;
- produce candidate fields marked `needs_confirmation`.

Candidate fields can include provider, booking type, confirmation code, dates, address, total, and currency. Unclear fields remain empty.

## Voice, photos, and calm support

- Android text-to-speech works without a paid voice service.
- Optional connected voice may be selected in Settings.
- Push-to-talk uses the phone speech recognizer.
- Selected trip photos are sanitized before local storage.
- Turbulence support, grounding, and personalized trivia remain available locally.

## Changing the connected model

To change the model within the existing provider, change the model ID in Settings and test every required capability. Text, images, tool use, and web research are separate capabilities.

To add Claude or another provider:

1. create a provider client matching the logical inputs used by `ConnectedModelGateway`;
2. preserve Sarah’s system prompt, history, current message, and optional image;
3. add a stable provider ID and Settings option;
4. store separate encrypted credentials or use a protected backend;
5. test text, multiple turns, images, event JSON, booking JSON, timeouts, fallback, and recovery;
6. do not claim current research unless the provider has a real current-source capability.

Recommended public architecture:

```text
Android app
    ↓ authenticated HTTPS
Sarah backend/provider router
    ├── OpenAI adapter
    ├── Anthropic / Claude adapter
    ├── Amazon Bedrock adapter
    ├── destination and event research
    ├── fare and weather sources
    └── push-notification service
```

## Renaming Sarah

Search for all user-visible identity references:

```text
Sarah
Sarah Morgan
sarahtravel
SarahMorgan
```

Review the Android label, launcher icon, onboarding and chat titles, greetings, prompt identity, local identity, settings, voice instructions, notification channels, documentation, workflow artifact names, and backend identifiers.

Internal Java class names may remain for a cosmetic rename. Changing the application ID, database filenames, preferences, Keystore aliases, notification channel IDs, or backend identifiers requires a migration plan.

## Building the APK

The workflow is:

```text
.github/workflows/build-apk.yml
```

It runs on pull requests and pushes to `main`. It tests:

- automatic connected/local routing;
- Travel Brain conversation and memory;
- no-question-loop planning;
- destination pack responses;
- CES and Comic-Con event parsing;
- generic city-for-event parsing;
- booking-link boundaries;
- event-planner actions;
- full Android compilation.

Expected artifact:

```text
Sarah-Morgan-1.2-event-aware-travel
```

Expected APK:

```text
Sarah-Morgan-1.2-event-aware-travel.apk
```

Verify in Settings:

```text
Build 1.2-event-aware-travel
```

## Physical-phone test checklist

Before the hackathon demo, test:

- onboarding;
- online/local transition;
- Orlando → Universal Studios → “that is it”;
- Austin destination pack;
- China dream watch;
- Vegas for CES;
- San Diego for Comic-Con;
- notification permission accepted and denied;
- event monitor after restart;
- Expedia link shared through Android Share;
- event-information link not misclassified as booking;
- booking screenshot import and sanitized copy;
- connected screenshot extraction;
- Travel Notebook event and booking sections;
- photos, voice, microphone, rotation, large text, and accessibility;
- database upgrade from earlier Sarah versions.

## Known boundaries

- Local conversation is structured and inspectable, not a full language model.
- Destination and event research require a connected current-source capability.
- Real airfare alerts require a lawful travel backend.
- Booking screenshot extraction is not confirmation.
- Android background jobs are not exact alarms.
- The debug APK is not production signed.
- A public release still needs authentication, privacy/export/delete controls, source agreements, billing and rate controls, security review, broader device testing, accessibility review, and store compliance.
