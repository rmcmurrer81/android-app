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

These are editable development defaults, not universal travel advice.

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
- `MainActivity.java` — chat, voice, photo input, and primary route selection.
- `SarahRuntimeServices.java` — connects the local dialogue layer to persistent travel services.

The profile, memories, wishes, trips, destination packs, and deal watches stay local when the route changes.

---

## 3. Conversation architecture

The local response path is layered in this order:

1. `AgenticTravelPlanner.java`
   - recognizes planning statements;
   - accepts short closures such as “that is it”;
   - interprets “I don’t care” as flexible dates when fare context is active;
   - chooses useful defaults;
   - avoids repeated follow-up questions.

2. `TravelAutomation.java`
   - saves destination wishes;
   - queues destination research;
   - creates broad deal watches;
   - records trip focus such as Universal Studios;
   - marks dates flexible when appropriate.

3. `AgenticTravelCore.java`
   - uses generated knowledge packs and active watch state;
   - explains what Sarah has already done;
   - handles Orlando, Austin, and country-level China planning without a long questionnaire.

4. `DestinationPackResponder.java`
   - reads a previously generated destination pack while offline.

5. `TravelBrainCore.java`
   - provides structured stable travel knowledge, destination comparisons, airport-process help, and first-flight support.

6. `DemoSarah.java`
   - handles lightweight non-travel conversation and honest local fallback.

Do not improve Sarah by adding hundreds of overlapping phrase checks to `DemoSarah.java`. Add a structured intent, action, or knowledge source to the correct layer.

---

## 4. Destination knowledge packs

When a person naturally mentions a possible destination, Sarah can queue a reusable knowledge pack.

Examples:

```text
I am thinking about going to Austin.
I am planning a trip to Orlando.
I always wanted to visit China.
```

`TravelAutomation.java` stores a pending request in the `destination_knowledge` table. `DealWatchScheduler.java` schedules background work. When the phone has internet and a connected-model key, `DestinationKnowledgeCoordinator.java` requests a source-aware pack through `ConnectedModelGateway.java`.

### Required pack fields

A completed pack contains:

- destination;
- overview;
- recommended starting points;
- transportation;
- accessibility and sensory notes;
- seasonal context;
- current or upcoming events;
- source and verification note;
- refresh and expiration times.

Current-event information expires after seven days and is refreshed later. Stable information may be reused, but the current implementation refreshes the whole pack together for simplicity.

### Knowledge boundaries

A generated pack must not invent:

- events;
- opening hours;
- ticket availability;
- prices;
- visa or entry rules;
- weather forecasts;
- transit disruptions.

If connected research is unavailable, the pack remains visibly marked **pending**. Sarah must not pretend that research completed.

### Adding another pack provider

`DestinationKnowledgeCoordinator.java` currently uses the configured connected-model provider through `ConnectedModelGateway.java`. A team can replace this with:

- a travel-content backend;
- a places API;
- an events API;
- a tourism-board data source;
- a retrieval system with approved sources.

Preserve the same stored fields so the offline reader does not need to change.

---

## 5. Travel-deal watches

A watch is created when the person asks Sarah to monitor deals or describes a destination as a long-term dream.

Examples:

```text
Watch for deals to Austin.
Notify me about cheap flights to Paris.
I always wanted to visit China.
```

The watch is stored locally in `deal_watches`. It is a real persistent record, but it cannot produce real fares until a travel backend is configured.

### Background schedule

`DealWatchScheduler.java` uses Android platform `JobScheduler`, not AndroidX WorkManager.

- periodic job: approximately every 12 hours;
- immediate job: queued shortly after a new pack or watch is created;
- network is required;
- the periodic job is persisted across reboot;
- Android may delay background jobs because of battery, standby, network, and system limits.

Background work is not guaranteed to run at an exact clock time.

`DealWatchWorker.java` performs two jobs:

1. refresh pending destination packs when a model key is available;
2. call the configured deal backend for active watches.

---

## 6. Travel backend contract

The Android app does not scrape airlines and does not contain a commercial airfare feed. The team must provide an HTTPS endpoint and configure it in Sarah’s settings.

`TravelDealGateway.java` sends a POST request similar to:

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

### Expected response

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
  "provider_note": "Fare checked at 2026-08-05T21:00:00Z"
}
```

### Weather basis rules

`weather_basis` must be one of:

- `forecast` — dates are close enough for a real forecast from a recognized weather provider;
- `climate` — dates are too far away, so this is seasonal or historical context;
- `unknown` — no reliable weather context is available.

The app phrases them differently:

```text
Forecast: Snow is currently expected...
Typical conditions: That period is often cold and snowy...
Weather context: Reliable weather information is not available yet.
```

Never describe a long-range seasonal pattern as a confirmed forecast.

### What counts as a deal

The backend, not the Android app, should decide `is_deal`. A serious implementation should consider:

- historical route prices;
- total fare including baggage and seat fees;
- airport ground-transport cost;
- overnight layovers;
- refund and change rules;
- traveler count;
- currency conversion;
- duplicate alerts;
- previous notified price;
- whether the itinerary is actually bookable.

The app currently suppresses a repeated notification unless the new total is at least about 2 percent lower than the last notified fare.

---

## 7. Notifications

`DealNotificationManager.java` creates the `sarah_travel_deals` Android notification channel.

A deal notification can say:

```text
China fare: USD 684
Leave February 3, return February 13. EWR → PVG.
Typical conditions: Cold conditions are common, with a possibility of snow.
```

Android 13 and later require the user to grant notification permission. Sarah requests this when deal alerts are enabled and settings are saved.

If permission is denied, watches and backend checks may still exist, but Android notifications are not shown.

---

## 8. Settings

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

A public version should normally use a protected backend and user authentication rather than giving every phone direct provider credentials.

---

## 9. Changing the model

### Change the model on the existing provider

Open Sarah settings and change the model ID. No Java change is needed if the model works with the existing OpenAI Responses adapter and supports the requested features.

Text, image input, tool use, and web research are separate capabilities. Test all required features after changing the model.

### Add Claude or another provider

Claude is not included yet. The extension point is `ConnectedModelGateway.java`.

Recommended process:

1. Create `ClaudeClient.java`.
2. Give it a method with the same information used by the gateway:

```java
public static String respond(
        String apiKey,
        String model,
        String systemPrompt,
        List<Map<String, String>> history,
        String message,
        boolean webSearch,
        byte[] imageJpeg) throws Exception
```

3. Translate Sarah’s system prompt, conversation history, current message, and optional photo to the provider’s current documented format.
4. Add a provider branch to `ConnectedModelGateway.java`.
5. Add a stable provider ID to settings, such as `anthropic`.
6. Store separate provider credentials or route all providers through the team backend.
7. Test text, multiple turns, photos, timeouts, local fallback, and reconnection.
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

## 10. Renaming Sarah

A partial rename is not enough. Change all user-visible identity surfaces together.

Search the repository for:

```text
Sarah
Sarah Morgan
sarahtravel
SarahMorgan
```

Review at least:

- `AndroidManifest.xml` application label;
- onboarding and chat layout titles;
- greetings in `OnboardingActivity.java` and `MainActivity.java`;
- identity prompt in `SarahPromptBuilder.java`;
- fallback identity in `DemoSarah.java`;
- settings text;
- voice instructions;
- notification channel names;
- README and artifact names;
- GitHub workflow artifact names;
- icon artwork if it contains an initial.

Internal Java class names can remain unchanged temporarily, but public-facing identity strings must agree.

Changing these technical identifiers requires a migration plan:

- application ID;
- SQLite database filename;
- SharedPreferences names;
- Android Keystore aliases;
- notification channel IDs;
- backend user/device identifiers.

Changing the application ID makes Android treat the result as a different app and will not automatically preserve the installed app’s data.

---

## 11. Database tables

Main tables:

- `profile`
- `messages`
- `memories`
- `trips`
- `wish_list`
- `photos`
- `destination_knowledge`
- `deal_watches`

The Travel Notebook displays generated packs, active watches, trip records, wishes, and approved memories.

---

## 12. Building the APK

The workflow is `.github/workflows/build-apk.yml`.

It performs:

1. Java 17 setup;
2. Smart/Local routing tests;
3. Travel Brain regression tests;
4. memory extraction tests;
5. autonomous no-question-loop tests;
6. Android debug compilation;
7. APK rename and artifact upload.

Expected artifact:

```text
Sarah-Morgan-1.1-agentic-travel
```

Expected APK inside the artifact ZIP:

```text
Sarah-Morgan-1.1-agentic-travel.apk
```

Verify in Settings:

```text
Build 1.1-agentic-travel
```

---

## 13. Required testing before a demo

Test on a physical Android phone:

- conversational onboarding;
- automatic online/local transition;
- Orlando → Universal Studios → “that is it” sequence;
- Austin pack creation and refresh;
- China dream-trip watch creation;
- “I don’t care” interpreted as flexible dates after fare context;
- background scheduling after app restart;
- notification permission accepted and denied;
- backend-not-configured status;
- test backend deal response;
- forecast versus climate wording;
- duplicate price suppression;
- photo selection and metadata-cleaned copy;
- text-to-speech and microphone;
- database upgrade from earlier Sarah builds;
- uninstall/reinstall behavior;
- accessibility, large text, rotation, and battery restrictions.

---

## 14. Known boundaries

- Local conversation is structured and inspectable, but it is not a complete language model.
- Destination packs require a connected model or another research provider.
- Current events expire and require refresh.
- Real airfare alerts require a lawful fare backend.
- Background jobs are controlled by Android and are not exact alarms.
- Long-range weather must be labeled climate context, not forecast.
- The debug APK is not production signed.
- A public release needs authentication, privacy documentation, billing controls, abuse protections, broader testing, and store compliance.
