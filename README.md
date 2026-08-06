# Sarah Morgan Android Companion

Sarah Morgan is a phone-first Android travel and conversational companion. This repository is the authoritative hackathon source and contains the Android project, tests, GitHub Actions workflow, developer documentation, and APK artifacts.

Current Android version: **1.4-public-web-fallback**  
Private-test application ID: `com.kiraworld.sarahtravel.debug`

This is a development prototype, not a public app-store release.

## What 1.4 changes

Earlier builds showed `Smart setup needed` and then behaved as though internet access was useless without a model key. Version 1.4 separates three capabilities:

| State | Capability |
|---|---|
| Internet + model key/backend | Full Smart conversation and configured tools |
| Internet, no model key | Narrow public event and factual lookup plus Explore tools |
| No internet | Local Travel Brain, memory, saved trips, calm tools, and offline packs |

When internet is available but no model key is configured, the header now says:

```text
Automatic • Public lookup online • Smart setup needed
```

That means Sarah can use selected public sources, but broad natural conversation and arbitrary research still require Smart setup.

Detailed documentation:

```text
PUBLIC_WEB_FALLBACK.md
```

## Bell County Comic Con repair

Sarah now recognizes:

```text
Bell County Comic Con
Bell Country Comic Con
Bell County Comicon
BCCC
```

as the canonical event:

```text
Bell County Comic Con — Belton, Texas
```

The known-event catalog stores its official public website, venue/address defaults, and aliases. When internet is available, Sarah reads the official page directly before using model-backed enrichment.

Older builds could save `Bell Country Comic Con` as if it were a city. `SarahApplication.java` performs a one-time repair that removes only those malformed ordinary-destination records and creates the correct monitored event.

## Public factual lookup

Without a model key, Sarah can answer a limited set of clearly defined public-reference questions. The first supported intent is filming locations:

```text
Where did they film Smallville and Corner Gas?
```

`PublicKnowledgeGateway.java` uses public reference pages and extracts filming-related sentences. It does not claim to be a full web-search language model.

## Permanent Explore tools

A permanent button now appears below Sarah's header:

```text
Explore maps • photos • videos • routes
```

It offers:

- OpenStreetMap;
- Wikimedia Commons public-photo search;
- YouTube travel-video search;
- Google Maps route and local-transit view;
- official event page or public web search;
- current Amtrak, flight, rail, bus, and route sources.

External sources do not endorse Sarah. Verify schedules, prices, closures, accessibility, safety, and availability before relying on them.

## Active travel context

Sarah uses the current message rather than mixing every old wish-list place into a reply.

Rules:

1. the current message wins;
2. a short follow-up may use only the most recent relevant user message;
3. saved wishes and old trips are not automatically inserted;
4. `from A to B` is one route, not two competing vacations;
5. `I don't know yet` closes the subject without another question;
6. a new event or journey replaces the previous topic.

Core file:

```text
TravelContextResolver.java
```

## Multimodal journeys

Recognized method families:

- air;
- Amtrak or other rail;
- local metro, subway, light rail, and transit;
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

Broad travel watches compare air, rail, and intercity bus where appropriate rather than assuming airfare only.

Detailed architecture:

```text
MULTIMODAL_TRAVEL_AND_VISUAL_EXPLORER.md
```

## Event-centered trips

Natural statements can create durable monitored events:

```text
I am going to Vegas for CES.
I am going to San Diego for Comic-Con.
I was thinking about taking metro to New York Comic Con.
I am thinking about going to Bell Country Comic Con.
```

Event data is stored separately in:

```text
sarah_event_trips.db
```

Official dates, venue, registration, schedule, and policy changes should come from official event sources first. Nearby food and places require reputable current sources and must not imply endorsement.

Detailed documentation:

```text
EVENT_TRIPS_AND_BOOKING_IMPORTS.md
```

## Booking imports

Sarah is an Android Share target for text links and images. A traveler can share:

- Expedia, Booking.com, hotel, airline, rail, car, or event-booking links;
- visible booking screenshots from the Gallery.

Imports remain pending review. Sarah does not sign into private accounts, reuse cookies, request passwords, or treat screenshot extraction as confirmed booking truth.

## Automatic and local architecture

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
            ├── save wish/focus
            ├── queue destination pack
            ├── save journey plan
            ├── create multimodal watch
            ├── create event trip
            └── save booking import
    ↓
Conversation route
    ├── connected model
    ├── public event/factual lookup
    └── local Travel Brain
```

Spoken claims and durable actions are separate. Sarah must not claim that a watch, event, booking, or pack exists unless the corresponding store was actually updated.

## Model/provider changes

The connected-provider extension point is:

```text
ConnectedModelGateway.java
```

To add Claude, Bedrock, Gemini, or another provider:

1. implement a provider client matching the gateway inputs;
2. preserve Sarah's identity prompt, history, current message, and optional image;
3. add a stable provider ID and Settings option;
4. store separate encrypted credentials or use a protected backend;
5. test multi-turn text, images, current research, event JSON, booking JSON, timeouts, fallback, and recovery;
6. never claim current research unless a real source/tool supplies it.

## Renaming Sarah

Search all user-visible and persistent identity surfaces:

```text
Sarah
Sarah Morgan
sarahtravel
SarahMorgan
```

Review the Android label, launcher icon, onboarding/chat titles, prompt identity, local replies, voice instructions, notification channels, database names, preferences, Keystore aliases, workflow artifacts, documentation, and backend identifiers.

A cosmetic rename can preserve internal Java class names. Changing the application ID or persistent identifiers requires a migration plan.

## Building and testing

Workflow:

```text
.github/workflows/build-apk.yml
```

The workflow tests:

- Smart/Local/public-lookup routing and status labels;
- active destination context;
- multimodal rail/transit/event planning;
- Bell Country typo correction;
- Bell County official event identity and URL;
- journey, booking, event, memory, and no-question regressions;
- full Android compilation;
- APK renaming and artifact upload.

Expected artifact:

```text
Sarah-Morgan-1.4-public-web-fallback
```

Expected APK:

```text
Sarah-Morgan-1.4-public-web-fallback.apk
```

Verify on the phone in Settings:

```text
Build 1.4-public-web-fallback
```

## Known boundaries

- Public lookup is not a complete language model.
- Arbitrary general conversation remains limited without Smart setup.
- Official-page parsers may fail when sites redesign their HTML.
- Current schedules, fares, routes, delays, weather, and availability require live sources.
- Android may defer background work.
- Embedded web pages may work better in an external browser.
- Debug APKs are not production signed.
- A public release needs authentication, source/privacy disclosures, deletion controls, rate limits, billing controls, broader tests, and store compliance.
