# Sarah Morgan Travel OS

R2 adaptive offline/calming and opt-in source-backed research boundaries are recorded in `SARAH_ADAPTIVE_OFFLINE_AND_RESEARCH_R2.md`. They remain owner-acceptance candidates, not a replacement for the preserved R1 phone/Windows artifacts.

Sarah Morgan is a phone-first Android travel companion and general conversational companion. She combines continuing personal memory, shared-phone profiles, trip planning, hotels, transportation, local experiences, accessibility, hotel guest support, public travel research, premium online voice, and reliable offline fallback.

This repository is the authoritative hackathon source.

Current development version:

```text
Sarah Morgan Android 2.5-r2-owner-repair
```

The R2 source is a repair candidate following Robert's first Galaxy A17 use.
It is not a replacement APK yet. The previous passed APK and Windows installer
remain preserved while R2 awaits the real Android compile/CI gates and a fresh
phone acceptance run.

R2 makes the application-owned route visible on every Sarah reply, migrates
the old placeholder `Phone owner` without inventing age 18, keeps unknown age
family-safe, keeps current location separate from hometown, removes unsupported
promises of background work, and does not create a fare/event watch when its
provider is absent. Android records only an approximate city/area for the
active profile and never stores raw coordinates. Windows now preserves reply
routes through sync, treats unknown imported age as unknown, stores current
area separately, and falls back to Windows speech if ElevenLabs fails.

The repair also keeps Tavily credentials on the protected Worker, binds
current-source queries to the actual conversation/trip context, exposes exact
HTTPS source receipts, makes explicit mode questions use the actual completed
turn route, and prevents network callbacks from claiming the online mind is
ready before a successful authenticated reply. Discovery says research was
saved only after its real preconditions and JobScheduler acceptance pass.

Any workflow output from R2 is named an `OWNER-ACCEPTANCE-CANDIDATE`; it is not
owner-accepted or judge-ready. Physical Galaxy A17 migration, fresh-profile,
online/offline/reconnect, location, ten-message keyboard/inset, voice-hearing,
and latency gates remain pending. Android source now uses a Media3 progressive,
one-shot POST for the approved ElevenLabs MP3 rather than waiting for a full
response or cache file before handing audio to the player. Receipts preserve
`requested_at`, `synthesis_start`, `first_network_byte`, `player_ready`,
`response_complete`, the compatibility alias `synthesis_end`,
`playback_start`, and `playback_end`. This is implemented source truth only:
Android CI compilation and physical Galaxy A17 hearing/latency measurement have
not passed, so no audible latency improvement is claimed yet.

Expected GitHub Actions artifact:

```text
Sarah-Morgan-2.2-phone-windows-continuity
```

Expected APK inside the artifact:

```text
Sarah-Morgan-2.2-phone-windows-continuity.apk
```

Private-test application ID:

```text
com.kiraworld.sarahtravel.debug
```

Sarah is a working development prototype, not a public app-store release. The APK must pass GitHub Actions before it is merged into `main` or offered for phone testing.

## Product idea

Sarah is not only an itinerary generator. She is intended to remain with a traveler before, during, and after a trip.

A typical flow is:

```text
Conversation or imported confirmation
        ↓
Active person and trip context
        ↓
Itinerary, hotel, transportation, event and local-experience tools
        ↓
Maps, photos, videos, rides and current sources
        ↓
Hotel requests, road-trip assistance and accessibility support
        ↓
Memories and preferences preserved for the correct person
        ↓
Offline Local fallback and Flight Companion when internet disappears
```

Sarah separates suggestions from confirmed facts. Opening a website is not a booking. Saving a draft is not proof that a hotel received it. Model output is not proof that a price, event, reservation, notification, call, or completed task exists.


## Sarah 2.2 phone and Windows continuity

Version 2.2 fixes emotional-state words being mistaken for names, adds universal transport-aware calm support, routes onboarding through Sarah Morgan's ElevenLabs voice when connected, records separate SPOKEN / PRIVATE MIND / FACTUAL TRUTH channels, adds Tavily-backed proactive discoveries, hides the empty visual panel, and introduces explicitly verified same-Wi-Fi synchronization with the Windows companion.

The Windows companion lives in `windows-companion/`. It provides a movable animated Sarah, a larger trip and photo workspace, ElevenLabs plus offline Windows speech, optional local or connected conversation, proactive research, encrypted backup, Google Drive app-data backup when the owner supplies OAuth client credentials, tray operation, and a paired local sync server.

No search result is treated as a booking or confirmed event. Nearby discoveries require the owner's setting, and private mind records are not displayed or sent to speech.

See `docs/SARAH_2_2_EVENT_READ_FIRST.md` and `docs/SARAH_2_2_REAL_WORLD_TESTS.md`.

## What Sarah 2.1 adds

Version 2.1 adds a fully local **Offline Flight Companion** for takeoff, turbulence, landing, and ordinary flight anxiety.

The airplane icon in Sarah's header opens a screen that works without cellular service, Wi-Fi, OpenAI, ElevenLabs, maps, location, or any travel backend.

It includes:

- takeoff support;
- turbulence support;
- landing support;
- quiet company without forcing questions;
- concern-specific conversation prompts;
- six-cycle gentle breathing;
- child-friendly flower-and-candle breathing;
- profile-aware offline trivia;
- color and noticing games;
- an alphabet travel game;
- short public-domain children's sing-alongs;
- Android text-to-speech so the feature remains available in airplane mode.

Sarah still tells the traveler to keep the seat belt fastened when required, follow the flight crew, and use the phone only as the airline permits. She never claims that the phone can assess the aircraft, interpret a sound, or decide whether a movement is safe.

Detailed design, safety boundaries, rights notes, and test instructions:

```text
OFFLINE_FLIGHT_COMPANION.md
```

Main source files:

```text
FlightCalmActivity.java
FlightCalmButton.java
CalmSupport.java
OfflineSongCatalog.java
SarahTts.java
```

## Hackathon tracks

Sarah Travel OS covers all four Travel Hack NYC tracks.

### 1. AI trip planning

- continuing conversational planning;
- itinerary, budget and packing lists;
- profile-specific interests and preferences;
- destination and event research;
- booking-link and screenshot intake;
- current-source and model-provider gateways;
- automatic online/public/offline routing;
- maps, public photos, videos and routes;
- calm support, offline flight support and personalized trivia.

### 2. Hotel and hospitality operations

- hotel search criteria;
- comparison links for major booking sites;
- direct official hotel-site search;
- complete-price comparison guidance;
- loyalty and status context;
- hotel stay request drafts;
- supervised voice-concierge contract;
- front-desk, housekeeping, maintenance and guest-experience operations demo;
- status separation between draft, sent, acknowledged, confirmed and completed.

### 3. Local and on-the-ground experiences

- free and inexpensive activities;
- food and restaurants;
- current events;
- museums and history;
- filming locations;
- age-aware suggestions;
- quiet or indoor alternatives;
- public maps, photos and videos;
- local transit, walking, Uber, Lyft and taxi launch paths;
- return-to-hotel and route tools.

### 4. Sustainability and the future of travel

- rail, Amtrak, bus, transit, ferry, driving, biking, walking and mixed routes;
- door-to-door comparison rather than airfare only;
- EV charging and fuel-stop planning;
- route-aware road-trip stops;
- walking limits, step-free needs and rest breaks;
- sensory, hearing, vision and dietary needs;
- pace and sustainability preferences;
- offline anxiety and child-support tools that do not depend on a network connection;
- greener alternatives presented without ignoring time, safety or accessibility.

The complete feature list is represented in:

```text
TravelOsFeatureCatalog.java
TravelOsFeatureCatalogTest.java
```

## Main interface

The chat remains Sarah's relationship and conversation surface.

The main screen also includes:

- calm and quick trivia;
- a dedicated airplane button for the offline flight companion;
- profile switch;
- travel notebook;
- Settings;
- visible media and route panel;
- Sarah Travel OS command center.

The command center contains large card-based entry points for:

```text
Itinerary, budget and packing
Hotels and rooms
Flights, Amtrak, buses and transit
Airport and local rides
Food, events and experiences
Road-trip companion
Event trip center
Loyalty wallet
Accessibility, pace and greener choices
Hotel stay assistant
Supervised voice concierge
Hospitality operations demo
```

The design intentionally keeps important tasks visible instead of requiring a user to remember hidden commands.

## Offline flight companion

The flight companion is intentionally independent from Sarah's online voice and model.

```text
Normal online conversation
    → OpenAI when configured
    → ElevenLabs Sarah Morgan voice when configured

Offline Flight Companion
    → bundled local guidance and games
    → active local profile
    → Android offline text-to-speech
```

This separation means the traveler can still use breathing, grounding, trivia, noticing games, phase-specific support, and children's sing-alongs after losing signal or enabling airplane mode.

### Breathing defaults

Adult:

```text
inhale comfortably for 4 counts
exhale gently for 6 counts
6 cycles
no required breath hold
```

Young child:

```text
smell the pretend flower for 3 counts
blow out the pretend candle for 4 counts
6 cycles
no required breath hold
```

The screen tells the traveler not to force the breath and to return to ordinary breathing if the count feels uncomfortable or causes lightheadedness.

### Public-domain children's sing-alongs

Bundled songs:

- `Twinkle, Twinkle, Little Star`;
- `Row, Row, Row Your Boat`;
- `Mary Had a Little Lamb`;
- `Baa, Baa, Black Sheep`.

Sarah does not copy a commercial recording or a modern arrangement. `OfflineSongCatalog` stores an old first verse and an original pitch-and-rate sequence. `SarahTts` performs it with the installed Android voice. Depending on the phone, it may sound like gentle singing or rhythmic spoken lyrics.

## Hotel search and room comparison

`HotelSearchActivity` carries the active destination, dates and traveler count into hotel searches.

Current external sources include entry points for:

- Google hotel results;
- Expedia;
- Booking.com;
- Priceline;
- Hotels.com;
- Rove;
- hotel map search;
- direct official hotel websites.

Sarah instructs the traveler to compare:

- complete price after taxes and mandatory fees;
- resort or destination fees;
- room type;
- cancellation terms;
- payment timing;
- breakfast;
- parking;
- accessibility;
- distance and local transport;
- loyalty eligibility and benefits.

A cheap headline nightly rate is not automatically the lowest complete cost.

Live in-app inventory requires a team travel-commerce backend. The Android provider-neutral contract is in:

```text
TravelCommerceConfig.java
TravelCommerceClient.java
```

Optional build values:

```text
SARAH_TRAVEL_COMMERCE_URL
SARAH_TRAVEL_COMMERCE_TOKEN
```

Without that backend, the external comparison and direct-hotel links still work. Sarah must not invent room prices.

## Transportation and road trips

Sarah does not assume every trip is a flight.

Supported method families include:

- flights;
- Amtrak and rail;
- intercity bus;
- subway, metro, light rail and local transit;
- driving;
- ferries;
- biking;
- walking;
- mixed routes.

The transportation tools are expected to consider the whole journey:

- complete price;
- duration;
- transfers;
- baggage;
- station or airport access;
- accessibility;
- reliability;
- parking;
- weather exposure;
- the local connection at each end.

The road-trip companion includes:

- route search;
- gas stations and current fuel-price searches;
- EV charging and PlugShare;
- rest areas;
- food along the route;
- roadside attractions;
- scenic stops;
- overnight hotels;
- fuel-cost estimates;
- encrypted profile-specific vehicle preferences.

A fuel estimate is not a live price. A real cheapest-safe-stop recommendation requires current lawful fuel and route data.

## Local rides

`RideLauncherActivity` can open:

- Uber;
- Lyft;
- taxis;
- public transit;
- rental-car search;
- walking directions.

Sarah may pass a destination into an external service where supported, but the traveler confirms pickup, price, vehicle, payment and the final request inside the external app.

Opening Uber or Lyft is not proof that a ride was requested.

## Local experiences

`LocalExperienceActivity` offers targeted public searches for:

- free activities;
- inexpensive activities;
- restaurants;
- lower-cost food;
- current events;
- museums;
- history;
- filming locations;
- quiet places;
- parks, gardens and libraries;
- accessible attractions;
- maps, photos and videos.

Sarah should use the active person's age, interests, trip timing, pace, budget and needs. Current opening hours, reservations, event dates and access information require verification.

## Events

Known events can use direct official mappings. Unfamiliar event-shaped names use best-effort public discovery without pretending the event name is a city.

Short follow-ups retain context:

```text
Person: I am thinking about going to a randomly chosen convention.
Person: When is it?
```

Sarah should keep researching the same event rather than treat `When is it?` as a new subject.

Core files:

```text
KnownEventCatalog.java
GenericEventReference.java
EventTripIntentParser.java
PublicEventDiscoveryGateway.java
PublicOnlineFallback.java
OfficialEventPageLookup.java
EventTripStore.java
EventTripCenterActivity.java
```

Search discovery is not proof that a page is official. Verified fields and source notes remain visible.

## Shared-phone profiles

The person icon switches the active profile.

Natural introductions are supported:

```text
My name is Emma.
I am Daniel.
This is Maya.
```

Sarah creates or selects a separate profile, asks age when missing, remains family-friendly until age is known, and can ask once whether the person is joining the active trip.

Separate profiles have separate:

- age;
- memory consent;
- interests and preferences;
- conversation history;
- trip participation;
- loyalty context;
- accessibility needs;
- vehicle preferences;
- itinerary, budget and packing state.

Owner-private memories, wishes, deal watches and unrelated trip information are not inserted into another person's prompt.

The flight companion also reads the active profile. A child receives simpler breathing language, easier trivia, and age-appropriate games without seeing the owner's private records.

Detailed architecture:

```text
SHARED_PHONE_PROFILES_AND_EVENT_DISCOVERY.md
```

## Memory and truth separation

Sarah separates:

1. spoken conversation;
2. active-person identity;
3. approved profile-specific memories;
4. trip and event planning state;
5. imported booking candidates;
6. confirmed booking facts;
7. current source-backed information;
8. unverified ideas and model suggestions;
9. hotel and operations task status.

No model reply may independently prove that Sarah booked, paid, called, reserved, changed, cancelled, confirmed, notified or completed something.

## Booking links and screenshots

Sarah is an Android Share target for text and images.

A person can share:

- Expedia or other booking links;
- airline, hotel, rail, car or event links;
- a visible booking screenshot.

Sarah:

- stores a pending import;
- does not sign into private accounts;
- does not request passwords;
- sanitizes the local image copy;
- sends a selected image only when an image-capable team model is available;
- marks extracted information as needing review.

The person confirms extracted dates, provider, property, address, price and confirmation code before the data becomes a trusted trip fact.

## Loyalty wallet

The encrypted profile-specific loyalty wallet can store:

- program name;
- program type;
- optional membership identifier;
- tier or status;
- official website;
- notes.

It must not store:

- passwords;
- recovery codes;
- security answers;
- full payment-card numbers;
- banking credentials.

Sarah can consider loyalty value, but she cannot sign into a program, transfer points or redeem benefits without a separately authorized integration.

## Accessibility, pace and sustainability

Each profile may store:

- mobility equipment;
- walking limits;
- rest-break needs;
- step-free and elevator requirements;
- sensory needs;
- hearing or vision needs;
- dietary needs;
- preferred pace;
- sustainability preferences.

Sarah should use these before making recommendations, not append them as an afterthought.

Current elevator outages, accessible entrances, hotel-room features and service disruptions require official verification.

## Hotel stay assistant

`StayAssistantActivity` supports editable drafts for:

- late arrival;
- quiet room;
- accessible room or route;
- bedding or allergy requests;
- housekeeping;
- maintenance;
- late checkout;
- custom requests.

Possible statuses include:

```text
draft
sent_by_traveler
confirmed_by_hotel
```

Saving a draft is not proof that it was delivered.

## Hospitality operations demo

`HospitalityOpsActivity` demonstrates how guest requests could become tasks for:

- front desk;
- housekeeping;
- maintenance;
- accessibility staff;
- guest experience.

Only a human or authenticated property integration should mark a task acknowledged, confirmed or completed.

## Voice

Sarah's voice architecture is:

```text
Online premium voice: ElevenLabs Sarah Morgan voice
Offline/error fallback: Android text-to-speech
Conversation brain: OpenAI when the team connection is present
```

The supplied Sarah Morgan Voice Design uses Eleven Multilingual v2 and is represented by the source defaults in:

```text
ElevenLabsVoiceConfig.java
CloudVoiceClient.java
```

Team build values:

```text
SARAH_ELEVENLABS_API_KEY
SARAH_ELEVENLABS_VOICE_ID
SARAH_ELEVENLABS_MODEL_ID
SARAH_ELEVENLABS_BACKEND_URL
SARAH_ELEVENLABS_BACKEND_TOKEN
```

Complete setup guide:

```text
ELEVENLABS_VOICE_SETUP.md
```

The workflow performs a small live TTS validation before creating an ElevenLabs-enabled APK. A build with no ElevenLabs secret still compiles and uses Android voice.

A direct ElevenLabs key inside a private hackathon APK is only a shortcut. Public releases should route speech through a protected backend.

The Offline Flight Companion always uses Android speech so calm support and songs remain available without signal.

## Supervised voice concierge

The hotel-call architecture is separate from ordinary text-to-speech.

Core files:

```text
VoiceConciergeActivity.java
VoiceConciergeConfig.java
VoiceConciergeClient.java
```

Optional build values:

```text
SARAH_VOICE_CONCIERGE_URL
SARAH_VOICE_CONCIERGE_TOKEN
```

A supervised call must require traveler review and explicit confirmation. It must not authorize charges, provide payment-card details, or cancel/change a booking without verified authorization.

## Conversation model

The person installing Sarah does not choose a provider or enter a key.

Default source configuration:

```text
Provider: Cloudflare Workers AI through Sarah's protected Worker
Model: @cf/google/gemma-4-26b-a4b-it
```

This event route uses Cloudflare's bounded free Workers AI allocation and does
not require Robert or the judges to buy OpenAI credits. OpenAI remains an
explicit optional rollback provider, not the R2 candidate default.

Core files:

```text
SarahModelConfig.java
ConnectedModelGateway.java
OpenAIClient.java
SarahBackendClient.java
SarahPromptBuilder.java
```

Recommended architecture:

```text
Sarah Android app
    → authenticated HTTPS with a revocable app token
Sarah protected per-candidate Worker
    → Workers AI conversation
    → protected current-source search when configured
    → protected ElevenLabs voice
```

Detailed provider-change instructions, including how to replace OpenAI with Claude:

```text
MODEL_PROVIDER_CONFIGURATION.md
```

## Connection behavior

Sarah uses Automatic mode by default.

| State | Behavior |
|---|---|
| Validated internet + protected Worker | Workers AI conversation; one short retry after a failed first attempt, then an explicit offline answer for that turn |
| Internet without an accepted Worker reply | saved/offline knowledge and local tools; no false web-search claim |
| Internet + ElevenLabs | Sarah Morgan premium voice |
| ElevenLabs unavailable or offline | Android speech fallback |
| No internet | Local Travel Brain, profiles, saved state, calm tools and offline games |
| Airplane mode | dedicated Flight Companion remains available through Android speech |
| Local only selected | no model or public lookup calls |

## Source structure

```text
.github/workflows/build-apk.yml
BUILD_VERSION.txt
README.md
OFFLINE_FLIGHT_COMPANION.md
ELEVENLABS_VOICE_SETUP.md
MODEL_PROVIDER_CONFIGURATION.md
SHARED_PHONE_PROFILES_AND_EVENT_DISCOVERY.md
MULTIMODAL_TRAVEL_AND_VISUAL_EXPLORER.md
EVENT_TRIPS_AND_BOOKING_IMPORTS.md
PUBLIC_WEB_FALLBACK.md
backend_examples/
Sarah_Morgan_Android_Phone_First_v3/
```

Main Android source:

```text
Sarah_Morgan_Android_Phone_First_v3/android-app/app/src/main/java/com/kiraworld/sarahtravel/
```

## Build and validation

The workflow must pass:

- OpenAI/public/Local routing tests;
- all four hackathon track capability tests;
- Offline Flight Companion, trivia, safety-language and public-domain-song tests;
- Travel Brain context and multimodal route tests;
- event-discovery and timed-trip tests;
- booking and agentic action tests;
- forbidden end-user credential-field scan;
- live ElevenLabs TTS validation when the secret is present;
- full Android compilation;
- APK artifact upload.

Workflow:

```text
.github/workflows/build-apk.yml
```

The final artifact is valid only after a green GitHub Actions run.

## Phone acceptance tests

Before a hackathon demonstration, test:

1. install over 2.0 without losing data;
2. profile switching and separate chat history;
3. a new adult and a new child profile;
4. a random real event and the follow-up `When is it?`;
5. `I am going to New York next week`;
6. a hotel comparison with the same dates across major and direct sources;
7. an imported booking screenshot;
8. an Amtrak or mixed-mode journey;
9. Uber, Lyft and return-to-hotel launch paths;
10. a road trip with gas or charging stops;
11. a filming-location or local-experience search;
12. accessibility and pace preferences;
13. a hotel request draft and status change;
14. Sarah's ElevenLabs voice with dates, prices and place names;
15. open the airplane icon and test takeoff, turbulence and landing support;
16. enable airplane mode and complete all six breathing cycles;
17. test adult and child trivia, noticing and alphabet games offline;
18. test every public-domain sing-along and the stop button;
19. confirm Android voice is used offline even when ElevenLabs is selected;
20. interrupt a long online reply with a newer reply;
21. test screen rotation and large text;
22. confirm no provider secrets are visible in the app or logs.

## Known boundaries

- Sarah cannot use full OpenAI conversation without the team model connection.
- ElevenLabs cannot speak without the team voice connection and available credits; Android fallback remains available.
- External hotel and travel links do not constitute live in-app inventory.
- Public event discovery may find a likely official page but cannot prove ownership from search alone.
- Public media may be a destination fallback rather than an event-specific image.
- Android background jobs are not exact alarms.
- The Offline Flight Companion cannot assess the aircraft, replace the crew, or provide medical diagnosis.
- Android TTS sing-along quality varies by device.
- A debug APK is not production-signed.
- A public release requires protected backends, authentication, privacy and deletion controls, child/guardian review, rate and spending limits, security review, accessibility testing, broader device testing and store compliance.
