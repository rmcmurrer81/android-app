# Sarah 1.2 Event-Aware Trips and Booking Imports

This document describes the event-centered travel and booking-intake system added in Sarah 1.2. It is intended for hackathon teammates who may want to replace the connected model, add event sources, build a travel backend, or change Sarah's user experience.

## 1. User behavior

Natural statements can create durable event trips without a form:

```text
I am going to Vegas for CES.
I am going to San Diego for Comic-Con.
I am traveling to Austin for the Future of Travel Conference.
```

Sarah should:

1. recognize the event and destination;
2. save an event-centered trip record;
3. queue ordinary destination research too;
4. monitor source-backed event details;
5. research nearby food, nearby places, transportation, accessibility, and sensory considerations;
6. avoid asking a long questionnaire;
7. request clarification only when a missing fact would materially change a booking, legal requirement, accessibility plan, or safety decision.

Known aliases currently include CES, Consumer Electronics Show, San Diego Comic-Con, Comic-Con International, SDCC, Comic Con, and Comic-Con. Generic `city for event` wording is also supported.

## 2. Data separation

Event-centered travel uses a separate SQLite database:

```text
sarah_event_trips.db
```

This protects Sarah's established profile, memory, normal-trip, destination-pack, and deal-watch tables from event-system experimentation.

### event_trips

Stores:

- event name and normalized event key;
- destination;
- venue;
- verified start and end dates;
- official URL;
- monitor status;
- latest update summary;
- nearby food;
- nearby places;
- transportation notes;
- source note;
- last and next check times;
- active state.

### event_updates

Stores deduplicated monitored changes:

- category;
- title and detail;
- source URL;
- published date;
- detection time;
- stable update key.

A notification is produced only when a source-backed update is newly inserted.

### booking_imports

Stores user-selected booking material:

- booking type and provider;
- source kind: link or screenshot;
- source URL or cleaned local image path;
- raw shared text;
- candidate extracted summary;
- confirmation code;
- dates;
- address;
- total and currency;
- review status.

Booking extraction is never equivalent to booking verification. Screenshot output remains `needs_confirmation` until the traveler reviews it.

## 3. Event monitoring architecture

```text
Natural conversation
    -> EventTripIntentParser
    -> AgenticTravelPlanner action
    -> AgenticActionExecutor
    -> EventTripStore
    -> EventMonitorScheduler
    -> EventMonitorJobService
    -> EventResearchCoordinator
    -> EventNotificationManager
```

`EventMonitorScheduler` uses Android `JobScheduler`, not AndroidX WorkManager. The periodic job requests network access and may be deferred by Android for battery or system reasons. The scheduler wakes approximately every six hours, but each event has its own next-check timestamp.

Suggested cadence in `EventResearchCoordinator`:

- more than 120 days away: weekly;
- 61–120 days: every three days;
- 15–60 days: daily;
- within 14 days: approximately every six hours;
- unknown or completed dates: weekly.

These are application preferences, not guarantees of exact Android execution time.

## 4. Source policy

The connected research prompt requires:

- official event website first for official dates, venue, badge or registration announcements, schedules, policies, and event changes;
- official venue and transit sources when possible for transportation and accessibility;
- reputable current public sources for nearby food and places;
- no implication that a restaurant, hotel, venue, or source endorses Sarah;
- no invented dates, schedules, policies, prices, or opening hours;
- no access to private accounts, booking pages, cookies, or credentials;
- a source note explaining uncertainty.

Current details should be stored separately from stable destination background. A current event schedule should not overwrite unrelated historical knowledge about the city.

## 5. Booking share target

`BookingImportActivity` is registered as an Android `ACTION_SEND` target for:

- `text/plain`;
- `image/*`.

A traveler can use Android Share from Expedia, Booking.com, Hotels.com, Airbnb, an airline, a browser, or the Gallery and choose Sarah Morgan.

### Links

`BookingLinkParser` recognizes known travel providers or text explicitly described as a booking, reservation, hotel, room, flight, train, rental car, ticket, badge, or confirmation.

Ordinary event information links must not become bookings. For example:

```text
https://www.ces.tech/attendee/overview
```

is not a booking unless the surrounding text explicitly says it is a reservation or ticket.

Sarah stores links but does not sign in, scrape private pages, reuse cookies, or request account credentials. If details are hidden behind a login, Sarah asks for a visible screenshot or confirmation text.

### Screenshots

`ImageSanitizer` decodes and re-encodes the user-selected image. Ordinary EXIF and GPS metadata are not copied into Sarah's stored JPEG.

`BookingExtractionCoordinator` sends only the selected cleaned screenshot to the configured image-capable model. It asks for visible fields only:

- booking type;
- provider;
- summary;
- confirmation code;
- start and end dates;
- address;
- total and currency.

Unclear fields remain empty. The result is marked `needs_confirmation`.

## 6. Provider and model changes

Event research and screenshot extraction both route through `ConnectedModelGateway.java`.

To add Claude or another provider:

1. implement a provider client with the gateway's existing inputs;
2. preserve Sarah's system prompt and message roles;
3. support image input for booking screenshots if that feature is advertised;
4. implement real web research or a backend tool before claiming current-event monitoring;
5. add a stable provider ID to Settings;
6. keep provider keys encrypted or move them to a protected backend;
7. test ordinary chat, event JSON, screenshot JSON, timeouts, local fallback, and recovery.

A model's training knowledge is not a substitute for current monitoring. The provider path must have an actual current-source capability.

## 7. Event JSON contract

`EventResearchCoordinator` expects one JSON object:

```json
{
  "event_name": "Example Event",
  "destination": "Example City",
  "venue": "Example Convention Center",
  "start_date": "2027-01-05",
  "end_date": "2027-01-08",
  "official_url": "https://official.example/",
  "updates_summary": "Verified current summary",
  "nearby_food": "Source-aware nearby options",
  "nearby_places": "Source-aware nearby places",
  "transport_notes": "Venue and transit notes",
  "source_note": "What was checked and what remains uncertain",
  "latest_updates": [
    {
      "update_key": "official_schedule_release_2027",
      "category": "schedule",
      "title": "Official schedule released",
      "detail": "Short verified detail",
      "source_url": "https://official.example/schedule",
      "published_at": "2026-11-15"
    }
  ]
}
```

The date fields must use `YYYY-MM-DD` only when verified.

## 8. Booking screenshot JSON contract

`BookingExtractionCoordinator` expects:

```json
{
  "booking_type": "hotel",
  "provider": "Expedia",
  "summary": "Hotel booking candidate extracted from screenshot",
  "confirmation_code": "ABC123",
  "start_date": "2027-01-04",
  "end_date": "2027-01-09",
  "address": "Visible address",
  "total": 725.40,
  "currency": "USD"
}
```

This is extraction, not confirmation.

## 9. Tests

The workflow must run:

- `EventTripIntentParserTest`;
- `BookingLinkParserTest`;
- `EventTripPlannerTest`;
- the existing routing, Travel Brain, memory, and agentic tests;
- the full Android build.

Required regression behavior:

- Vegas + CES becomes CES in Las Vegas;
- San Diego + Comic-Con becomes an event-centered San Diego trip;
- generic city-for-event wording is retained;
- event replies do not end in another questionnaire;
- an Expedia hotel link becomes a pending booking import;
- an ordinary event-information link is not misclassified as a booking;
- screenshots are sanitized and remain pending review;
- no notification is sent for a duplicate event update.

## 10. Public-release work still required

Before public distribution, the team still needs:

- release signing;
- a privacy policy and deletion/export controls;
- secure authenticated backend design;
- source, rate-limit, and billing agreements;
- broader device and Android-version testing;
- accessibility review;
- clear notification controls;
- booking confirmation UI with edit/delete actions;
- event monitor pause and expiry controls;
- security review for shared links and file imports;
- app-store compliance.
