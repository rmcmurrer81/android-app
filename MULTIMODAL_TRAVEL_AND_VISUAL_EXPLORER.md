# Sarah 1.3 Multimodal Travel and Visual Explorer

This document is for hackathon teammates who want to improve route planning, connect transport data, change visual sources, or add another model/provider.

## 1. Problems this revision fixes

Earlier local builds could combine every recent destination, saved wish, and old trip. That produced replies such as comparing New York and Paris when the traveler had asked about a cross-country train to California.

Version 1.3 changes the rule:

1. the current message wins;
2. a short direct follow-up may use only the most recent relevant user turn;
3. saved trips and wish-list places are not automatically inserted into ordinary replies;
4. `from A to B` is a route, not two vacation choices;
5. `I don't know yet` clears the travel subject instead of becoming an awkward echoed phrase.

Core file: `TravelContextResolver.java`.

## 2. Journey parsing

`JourneyIntentParser.java` extracts:

- origin;
- destination;
- event name, when relevant;
- transport methods;
- whether monitoring was requested;
- whether the route was described as cross-country.

Recognized method families:

- `air`
- `rail`
- `local_transit`
- `intercity_bus`
- `drive`
- `ferry`
- `bike`
- `walk`

Examples:

```text
I would love to take a cross-country train trip from New York to California.
I was thinking about taking metro to New York Comic Con.
Monitor travel deals to Paris.
Drive from Austin to San Antonio.
```

A broad request without a named method defaults to air, rail, and intercity bus where those methods make sense. It must not be described as airfare monitoring only.

## 3. Low-question journey replies

`JourneyPlannerCore.java` gives a useful starter response before asking anything.

Cross-country rail covers:

- current Amtrak route combinations;
- transfers;
- coach versus sleeper choices;
- total travel time;
- station access;
- scenery;
- meals;
- overnight stops.

Local event transit covers:

- event venue;
- realistic transit chain;
- walking distance;
- elevators and accessibility;
- current service changes;
- a backup route.

The local build must be honest that current schedules, prices, and service changes need live sources.

## 4. Durable data

Multimodal travel uses a separate SQLite database:

```text
sarah_mobility.db
```

This keeps experimental route work separate from Sarah's established identity, memories, event trips, booking imports, and legacy airfare watches.

### journey_plans

Stores:

- origin;
- destination;
- event name;
- modes;
- notes;
- status;
- creation/update time.

### mobility_watches

Stores:

- origin and destination;
- event name;
- mode list;
- purpose;
- active state;
- backend status;
- last check time;
- last summary;
- source note.

The Travel Notebook displays both tables.

## 5. Background monitoring

`MobilityWatchCoordinator.java` is called from the existing platform `JobScheduler` travel job.

A watch can request:

- current price;
- current schedule;
- service alerts;
- station or airport access;
- local connections;
- weather context.

The phone does not scrape Amtrak, airlines, transit agencies, or bus companies. It calls the team-configured authenticated travel backend.

Android may defer jobs for battery, network, standby, or system reasons. This is not an exact alarm.

## 6. Multimodal backend request

`MobilityGateway.java` sends JSON similar to:

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

Suggested response:

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

The backend should normalize transport providers rather than asking the language model to invent schedules or prices.

## 7. Visual explorer

`TravelExplorerActivity.java` displays public visual sources inside Sarah.

The Explore panel can open:

- **Map** — OpenStreetMap search;
- **Photos** — Wikimedia Commons MediaSearch;
- **Videos** — YouTube search results;
- **Route** — a Google Maps directions view;
- **Live options** — external current-source links such as Amtrak, Google Flights, or a current route search.

`TravelSearchHelper.java` opens the panel after relevant route, destination, or event statements. It is not proof that any source endorses Sarah.

Public images and videos are context only. A public map or video does not prove current accessibility, opening hours, construction, service, safety, or weather.

## 8. Current-source routing

`LiveTravelIntent.java` makes connected mode request current research for:

- maps and directions;
- Amtrak, train, rail, metro, subway, transit, bus, ferry, driving, traffic, or parking;
- delays and service changes;
- events such as CES, Comic-Con, and NYCC;
- fares, weather, and current openings.

`ConnectedModelGateway.java` applies that rule even if an older UI heuristic failed to mark the turn as live research.

## 9. Provider changes

All connected conversation still enters through `ConnectedModelGateway.java`.

To add Claude, Bedrock, Gemini, or another provider:

1. create a provider adapter;
2. preserve Sarah's system prompt and role history;
3. support current-source tools before advertising current route monitoring;
4. support image input before advertising photo or booking-screenshot understanding;
5. add a stable provider ID in Settings;
6. keep credentials encrypted or move them behind an authenticated backend;
7. test timeouts and automatic Local fallback;
8. test the route regression suite.

A model's training data is not current transport data.

## 10. Regression tests

The GitHub workflow tests:

- old Paris context does not leak into New York-to-California rail;
- `I don't know yet` clears context;
- Paris/London direct comparison follow-ups still work;
- New York Comic Con replaces old Paris context;
- cross-country train parsing;
- metro to NYCC parsing;
- broad watches include air, rail, and bus;
- Orlando/Universal low-question behavior;
- event and booking parsing;
- Android compilation and APK creation.

## 11. Known boundaries

- Local Sarah is a structured fallback, not a full language model.
- Current schedules and prices require source-backed services.
- Google Maps, YouTube, Wikimedia, OpenStreetMap, Amtrak, and other linked sources are separate services and do not endorse Sarah.
- Some sites may work better in an external browser than an embedded WebView.
- The debug APK is not production signed.
- A public release needs privacy controls, source attribution, authentication, deletion tools, accessibility testing, and store review.
