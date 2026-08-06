# Sarah 1.4 Public Web Fallback

Sarah 1.4 distinguishes three different capabilities instead of treating internet access as identical to Smart mode.

## Connection states

### Smart online

Requirements:

- validated internet connection;
- configured connected-model provider;
- valid personal model key or protected team backend.

Capabilities may include broad natural conversation, arbitrary current research, image understanding, richer recommendations, and provider tools.

### Public lookup online

Requirements:

- validated internet connection;
- no model key required.

Capabilities are deliberately narrower:

- known official event-page lookup;
- selected public factual-reference questions;
- permanent maps, public photos, videos, routes, official event pages, and public web searches through Explore;
- official-source event monitoring for known events.

The status line says:

```text
Automatic • Public lookup online • Smart setup needed
```

This is not a full language model. Unsupported conversation continues through the structured local fallback.

### Local offline

When no validated internet connection exists, Sarah uses local memory, trip state, calm tools, trivia, saved destination packs, journey planning, and deterministic fallback logic.

## Known official event catalog

`KnownEventCatalog.java` stores canonical event identity, destination, official URL, known venue/address defaults, and aliases.

Initial entries:

- Bell County Comic Con — Belton, Texas;
- CES — Las Vegas, Nevada;
- San Diego Comic-Con — San Diego, California;
- New York Comic Con — New York City.

The Bell County entry recognizes the common phrase or transcription error:

```text
Bell Country Comic Con
```

and corrects it to:

```text
Bell County Comic Con
```

`OfficialEventPageLookup.java` reads the official public page, looks for a verified date range and hours, preserves the official URL, and stores a source note. It must not invent dates when the page cannot be read or parsed.

Known official pages are checked before model-backed event enrichment. A connected model may later add source-backed nearby food, nearby places, transport, accessibility, and new official announcements.

## Public factual reference

`PublicKnowledgeGateway.java` currently handles clear filming-location questions such as:

```text
Where did they film Smallville and Corner Gas?
```

It uses the public Wikipedia API when available, extracts only sentences relevant to filming or principal photography, and falls back to a small stable local reference for the two regression examples when the public API is unavailable.

This gateway is intentionally narrow. Do not silently turn it into a general unverified search scraper. New factual intents should define:

- the exact question type;
- the source family;
- extraction rules;
- uncertainty behavior;
- tests;
- privacy and rate-limit expectations.

## Permanent Explore button

`ExploreButton.java` appears directly below Sarah's header. It reads the most recent user message and opens `TravelSearchHelper`.

Available choices:

- Map;
- Photos;
- Videos;
- Route and local transit;
- official event page or public web search;
- live travel options.

Public sources do not endorse Sarah. Maps, search results, videos, and photos do not prove current opening hours, access, safety, schedule, price, or availability.

## Legacy data repair

Earlier builds could save `Bell Country Comic Con` as a destination and knowledge pack instead of an event.

`SarahApplication.java` performs a one-time narrowly scoped repair:

1. detect the malformed Bell Country/County destination rows;
2. remove them from the ordinary wish-list, destination-pack, legacy fare-watch, and malformed-memory tables;
3. create a monitored `Bell County Comic Con` event in `Belton, Texas`;
4. schedule an official event refresh.

The repair does not delete unrelated wish-list places, trips, memories, or watches.

## Privacy and network boundaries

- Public lookup sends the event name or factual search phrase to the selected public source.
- It does not send Sarah's full memory database.
- It does not sign into private accounts or reuse browser cookies.
- It does not make bookings or purchases.
- Official event lookup uses ordinary public pages only.
- Public filming lookup uses Wikipedia's public API and does not imply Wikipedia endorsement.
- A public release should add a transparent source/privacy screen, request throttling, caching, deletion controls, and an authenticated backend where appropriate.

## Tests

The GitHub workflow verifies:

- Automatic/Smart/Local routing;
- the public-lookup status label;
- Bell Country typo correction;
- Bell County canonical event identity, Belton destination, and official URL;
- previous Travel Brain, multimodal, event, booking, journey, and memory regressions;
- full Android compilation;
- versioned APK creation and artifact upload.
