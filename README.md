# Sarah Morgan Android Companion

Sarah Morgan is a phone-first Android travel and conversational companion. She can talk about ordinary subjects, remember approved details, help with first-flight anxiety, work locally when the internet disappears, research destinations when a connected model is available, keep reusable destination packs, and maintain broad travel-deal watches for a team-provided fare service.

Current Android version: **1.1-agentic-travel**  
Private-test application ID: `com.kiraworld.sarahtravel.debug`

This repository is a development prototype. It is not yet a public app-store release.

---

## 1. What version 1.1 changes

Version 1.1 changes Sarah from a question-heavy travel form into a more proactive companion.

Examples of intended behavior:

```text
Person: I am thinking about going to Orlando.
Sarah: Orlando is now on my planning list. I’ll build or refresh a local guide automatically...

Person: Universal Studios.
Sarah: Universal Studios is the main focus of the Orlando trip. I’ll prioritize that in the guide...

Person: That is it.
Sarah: Understood. I have enough for now, so I won’t keep asking questions.
```

For a dream destination:

```text
Person: I always wanted to visit China.
Sarah: China is now on my planning list. I’ll create a country-level knowledge pack and a broad deal watch using flexible dates, nearby airports, and several trip lengths.
```

Sarah now uses sensible defaults instead of demanding every detail first. The traveler can correct those defaults later.

### Default deal-watch assumptions

Unless the person says otherwise, a new broad watch uses:

- the saved hometown as the origin area;
- round trip;
- one traveler;
- carry-on travel;
- flexible dates;
- nearby origin and destination airports;
- trip lengths from 3 to 14 nights;
- a search horizon up to one year.

These are reversible development defaults, not universal travel advice.

---

## 2. Automatic connected and local behavior

Sarah uses **Automatic mode** by default.

| State | Route |
|---|---|
| Validated internet and a saved model key | Connected model |
| No validated internet | Local Travel Brain |
| Internet but no model key | Local Travel Brain |
| Connected request fails | Local Travel Brain for that message |
| Connection becomes usable again | Connected model on the next message |
| Local only selected | Never send conversation to a connected model |

Important files:

- `ConversationModePolicy.java` — route selection and status labels.
- `ConnectivityMonitor.java` — validated Android network monitoring.
- `ConnectedModelGateway.java` — provider-neutral connected-model entry point.
- `MainActivity.java` — chat, voice, photo input, route selection, action execution, and pack refresh.

The profile, memories, wishes, trips, destination packs, and deal watches remain local when the route changes.

---

## 3. Conversation and action architecture

```text
User message
    ↓
SpeakerContext
    ↓
AgenticTravelPlanner
    ├── public reply plan
    └── durable action list
            ↓
      AgenticActionExecutor
            ├── save wish
            ├── queue knowledge pack
            ├── save attraction/trip focus
            ├── create or update deal watch
            └── schedule background work
    ↓
Automatic connected/local response
    ├── connected model when available
    └── local response layers
            ├── DestinationPackResponder
            ├── TravelBrainCore
            └── DemoSarah
```

The spoken reply and the durable action are deliberately separate. Sarah must not claim that a watch or knowledge pack exists only because she said it does. `AgenticActionExecutor` must create the corresponding database record.

### Core files

- `AgenticTravelPlanner.java` — pure low-question planner and action generator.
- `AgenticActionExecutor.java` — applies planner actions to durable Android state.
- `DestinationParser.java` — extracts one or more destinations.
- `DestinationKnowledgeCoordinator.java` — builds source-aware connected packs.
- `DestinationPackResponder.java` — reads generated packs offline.
- `TravelBrainCore.java` — stable travel, comparison, airport, and first-flight logic.
- `DemoSarah.java` — lightweight ordinary conversation after structured travel layers.

Do not improve Sarah by adding hundreds of overlapping phrase checks to `DemoSarah.java`. Add a structured intent, action, knowledge field, or provider adapter to the proper layer.

---

## 4. Low-question policy

Sarah should ask only when a missing fact would materially change:

- a booking;
- a legal or entry requirement;
- accessibility planning;
- a safety decision;
- the person’s explicit goal.

Otherwise Sarah should:

1. acknowledge the stated goal;
2. use reversible defaults;
3. create the relevant durable work;
4. explain what she did;
5. allow corrections later.

If the person says **“that is it,” “nothing,” “I don’t care,” “whatever,”** or gives one attraction as the complete reason for the trip, Sarah accepts that and stops questioning them.

The GitHub regression suite covers:

- Orlando → Universal Studios → “that is it”;
- Austin automatic pack request;
- China dream destination → pack plus deal watch;
- “I don’t care” after fare context → flexible dates;
- generated Austin recommendations and events.

---

## 5. Destination knowledge packs

When a person naturally mentions a possible destination, Sarah can queue a reusable knowledge pack.

Examples:

```text
I am thinking about going to Austin.
I am planning a trip to Orlando.
I always wanted to visit China.
```

`AgenticTravelPlanner` emits a `QUEUE_KNOWLEDGE_PACK` action. `AgenticActionExecutor` writes a pending row to `destination_knowledge`. When internet, connected research, and a model key are available, `DestinationKnowledgeCoordinator` requests a structured pack through `ConnectedModelGateway`.

### Stored pack fields

- destination;
- status;
- overview;
- recommendations;
- transportation;
- accessibility and sensory notes;
- seasonal context;
- current or upcoming events;
- source/verification note;
- refresh time;
- expiration time.

`DestinationPackResponder` can answer later questions from the saved pack while offline.

### Research rules

A generated pack must:

- separate stable background from current facts;
- include practical starting points and places;
- include transport, walking, sensory, and accessibility concerns;
- include seasonal conditions;
- include dated events only when verified;
- never invent events, opening hours, ticket availability, prices, entry rules, closures, or forecasts;
- treat a country such as China as a country-level plan with useful gateway regions, not as one city;
- preserve a source and verification note.

The current implementation expires a whole generated pack after seven days. A later backend may use separate lifetimes for stable background and volatile events.

### Built-in offline knowledge

`TravelKnowledgeBase.java` remains the fail-safe for stable destinations. Add new aliases to `DestinationParser.java` and stable entries to `TravelKnowledgeBase.java`. Arbitrary places are handled by generated packs.

---

## 6. Deal watches

A watch is created when the person asks Sarah to monitor deals or describes a destination as a long-term dream.

Examples:

```text
Watch for deals to Austin.
Notify me about cheap flights to Paris.
I always wanted to visit China.
```

The watch is stored locally in `deal_watches`. It is a real persistent record, but it cannot produce real fares until a travel backend is configured.

### Background schedule

`DealWatchScheduler.java` uses Android platform `JobScheduler`.

- periodic job: approximately every 12 hours;
- immediate job: queued after new work or settings changes;
- network required;
- periodic job persisted across reboot;
- Android may delay work because of battery, standby, network, or system limits.

This is not an exact clock-time alarm.

`DealWatchWorker.java` performs two jobs:

1. refresh pending destination packs when research is enabled and a model key is available;
2. call the configured deal backend for active watches when deal alerts are enabled.

Turning both automatic research and deal alerts off cancels scheduled Sarah travel jobs.

---

## 7. Travel backend contract

The Android app does not scrape airlines and does not contain a commercial airfare feed. The team must provide an authenticated HTTPS endpoint and configure it in Sarah’s settings.

### Request

```json
{
  "watch_id": 12,
  "origin": "Newark, New Jersey",
  "destination": "China",
  "trip_type": "round_trip",
  "travelers": 1,
  "bag_mode": "carry_on",
  "flexible_dates": true,
  "nearby_airports": true,
  "min_trip_days": 3,
  "max_trip_days": 14,
  "horizon_days": 365,
  "last_notified_price": 0,
  "currency": "USD",
  "include_weather_context": true
}
```

The backend should resolve cities, countries, and origin areas into appropriate airport sets. For a country-level watch such as China, it may compare multiple gateways and date windows.

### Response

```json
{
  "found": true,
  "is_deal": true,
  "total_price": 684.20,
  "currency": "USD",
  "origin_airport": "EWR",
  "destination_airport": "PVG",
  "depart_date": "2027-02-03",
  "return_date": "2027-02-13",
  "booking_url": "https://example.com/booking-result",
  "weather_note": "Cold conditions are common, with a possibility of snow.",
  "weather_basis": "climate",
  "provider_note": "Fare includes one carry-on; verify final checkout total."
}
```

### Weather-basis rules

`weather_basis` should be:

- `forecast` — dates are within a trustworthy forecast window and a real forecast source was used;
- `climate` — seasonal or historical context for dates too far away for a forecast;
- `unknown` — no reliable weather context is available.

The app phrases them differently:

```text
Forecast: Snow is currently expected...
Typical conditions: That period is often cold and snowy...
Weather context: Reliable information is not available yet.
```

Never describe long-range seasonal conditions as a confirmed forecast.

### What counts as a deal

The backend, not the language model, should decide `is_deal`. A serious implementation should consider:

- historical route prices;
- total fare including bags and seats;
- airport ground-transport cost;
- layovers and overnight costs;
- refund/change rules;
- traveler count;
- currency conversion;
- duplicate alerts;
- prior notified price;
- actual bookability.

The phone currently suppresses a repeated positive-price notification unless the new total is about two percent below the last notified fare.

---

## 8. Notifications

`DealNotificationManager.java` creates the `sarah_travel_deals` channel.

A notification can include:

```text
China fare: USD 684
Leave February 3, return February 13. EWR → PVG.
Typical conditions: Cold conditions are common, with a possibility of snow.
```

Android may require notification permission. If permission is denied, the watch remains stored and background status remains visible, but the phone cannot show the alert.

A booking link is an inspection path, not proof of a booking, endorsement, or guarantee.

---

## 9. Settings

The settings screen includes:

- Automatic, Smart preferred, or Local only;
- connected-model API key;
- model name;
- web research permission;
- automatic destination research;
- travel-backend URL;
- encrypted travel-backend token;
- background deal alerts;
- voice mode and speech rate;
- memory permission.

Secrets are stored through `SecureStore.java` using Android Keystore-backed AES/GCM encryption.

A public version should normally use a protected backend and user authentication instead of giving every phone direct provider credentials.

---

## 10. Changing the model

### Change model within the existing provider

Open Sarah settings and change the model ID. No Java change is needed when the model works with the current adapter and supports the required capabilities.

Text, images, tool use, and web research are separate capabilities. Test all required features after changing the model.

### Add Claude or another provider

Claude is not included yet. The extension point is `ConnectedModelGateway.java`.

1. Create `ClaudeClient.java`.
2. Give it a method with the same logical inputs as `OpenAIClient.respond`.
3. Translate Sarah’s system prompt, conversation history, current message, and optional photo to the provider’s documented format.
4. Add a provider branch in `ConnectedModelGateway`.
5. Add a stable provider ID such as `anthropic` to settings.
6. Store separate provider credentials or route all providers through a protected backend.
7. Test text, multiple turns, images, timeouts, automatic fallback, and Local-only privacy.
8. Do not claim web research unless a real tool or backend supplies it.

Recommended public architecture:

```text
Android app
    ↓ authenticated HTTPS
Sarah provider router
    ├── OpenAI adapter
    ├── Anthropic / Claude adapter
    ├── Amazon Bedrock adapter
    ├── destination research sources
    └── fare and weather sources
```

---

## 11. Renaming Sarah

A partial rename is not enough. Change all user-visible identity surfaces together.

Search for:

```text
Sarah
Sarah Morgan
sarahtravel
SarahMorgan
```

Review at least:

- `AndroidManifest.xml` application label;
- launcher icon artwork;
- onboarding and chat titles;
- greetings;
- `SarahPromptBuilder.java` identity;
- `DemoSarah.java` identity;
- settings text;
- voice instructions;
- notification channel names;
- README and artifact names;
- workflow artifact names.

Internal class names may remain unchanged for a cosmetic rename. Changing the application ID, database filename, preferences, Keystore aliases, notification channel IDs, or backend identifiers requires a migration plan and may prevent old data from being read.

---

## 12. Database tables

- `profile`
- `messages`
- `memories`
- `trips`
- `wish_list`
- `photos`
- `destination_knowledge`
- `deal_watches`

The Travel Notebook shows generated packs, watch assumptions and backend status, trip records, wishes, and approved memories.

---

## 13. Build and testing

The GitHub workflow runs on pull requests and pushes to `main`.

It tests:

- automatic connected/local routing;
- Travel Brain conversation and memory;
- Orlando/Universal no-question closure;
- Austin pack actions;
- China dream-destination watch actions;
- “I don’t care” as flexible dates in fare context;
- saved-pack recommendations and events;
- Android compilation.

Expected artifact:

```text
Sarah-Morgan-1.1-agentic-travel
```

Expected APK:

```text
Sarah-Morgan-1.1-agentic-travel.apk
```

Verify in Settings:

```text
Build 1.1-agentic-travel
```

Before a demo, test on a physical phone:

- onboarding;
- online/local transition;
- Orlando → Universal Studios → “that is it”;
- Austin pack creation and refresh;
- China watch creation;
- background scheduling after restart;
- notification permission accepted and denied;
- backend-not-configured state;
- a controlled test-backend deal result;
- forecast versus climate wording;
- duplicate alert suppression;
- photos, voice, microphone, rotation, large text, and accessibility;
- database upgrade from earlier Sarah builds.

---

## 14. Known boundaries

- Local conversation is structured and inspectable, but it is not a complete language model.
- Destination packs require a connected model or another research provider.
- Current events expire and require refresh.
- Real airfare alerts require a lawful fare backend.
- Background jobs are controlled by Android and are not exact alarms.
- Long-range weather must be labeled climate context, not forecast.
- The debug APK is not production signed.
- A public release needs authentication, privacy/deletion controls, documented sources, billing/rate controls, broader testing, and store compliance.
