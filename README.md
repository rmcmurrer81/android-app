# Sarah Morgan Android Companion

Sarah Morgan is a phone-first Android travel and general conversational companion. This repository is the authoritative hackathon source. It contains the Android project, tests, GitHub Actions workflow, model-provider instructions, public-source tools, visible media, travel planning, event monitoring, booking intake, shared-phone profiles, and generated APK artifacts.

Current Android version: **1.6-profiles-events**  
Private-test application ID: `com.kiraworld.sarahtravel.debug`

This is a development prototype, not a public app-store release.

## What 1.6 adds

Sarah 1.6 focuses on four failures visible during real phone testing:

1. unfamiliar comic conventions and events must not be mistaken for cities;
2. a direct city-and-time statement should produce useful ideas instead of a questionnaire;
3. interests must belong to the correct person using a shared phone;
4. chat history, memories, and trip participation must not silently merge between profiles.

New source areas:

```text
PersonProfileStore.java
ProfileButton.java
GenericEventReference.java
PublicEventDiscoveryGateway.java
TripWindowParser.java
CityVisitPlanner.java
TimedTripCoordinator.java
SHARED_PHONE_PROFILES_AND_EVENT_DISCOVERY.md
```

Expected GitHub Actions artifact:

```text
Sarah-Morgan-1.6-profiles-events
```

Expected APK inside the artifact:

```text
Sarah-Morgan-1.6-profiles-events.apk
```

## People who install Sarah do not configure the model

The person using the app is **not** asked to:

- choose OpenAI, Claude, or another provider;
- type a model name;
- paste an API key;
- configure an endpoint.

The hackathon source is configured for:

```text
Provider: OpenAI
Model: gpt-5.1
```

Team-controlled files:

```text
SarahModelConfig.java
ConnectedModelGateway.java
OpenAIClient.java
SarahBackendClient.java
SarahPromptBuilder.java
```

Detailed instructions for changing the OpenAI model, adding Claude, changing Sarah’s name, and using a protected team backend are in:

```text
MODEL_PROVIDER_CONFIGURATION.md
```

## Connection states

Sarah uses Automatic mode by default.

| Phone and build state | Sarah’s route |
|---|---|
| Internet + team OpenAI backend or private build key | Full OpenAI conversation, image understanding, and configured current-source tools |
| Internet but no team OpenAI connection in the APK | Public event discovery, official pages, public background references, maps, media, and routes, then Local fallback |
| No internet | Local Travel Brain, separate profile state, saved memories, trips, calm tools, and offline games |
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

Recommended:

```text
Sarah Android app
    -> authenticated HTTPS
Sarah team backend
    -> OpenAI Responses API
```

Reference Android client:

```text
SarahBackendClient.java
```

Reference FastAPI service:

```text
backend_examples/openai_proxy/
```

GitHub Actions repository secrets:

```text
SARAH_MODEL_BACKEND_URL
SARAH_MODEL_BACKEND_TOKEN
```

Private hackathon shortcut:

```text
SARAH_OPENAI_API_KEY
```

The direct-key shortcut places a credential in the APK and is not safe for public distribution. A protected backend is strongly preferred.

## Shared-phone profiles

The person-shaped icon in Sarah’s header opens the saved-profile switch.

A person can also identify themselves naturally:

```text
My name is Emma.
I am Daniel.
This is Maya.
```

Sarah compares the name against local profiles.

- Existing name: switch to that profile.
- New name: create a separate incomplete profile and ask age.
- Owner name: return to the owner profile.
- `I’m back` or `handing the phone back`: return to the owner.

The name parser rejects common non-name statements such as:

```text
I am tired.
I am hungry.
I am going.
```

so those statements do not create accidental profiles.

Core files:

```text
ProfileButton.java
SpeakerContext.java
PersonProfileStore.java
MainActivity.java
```

### Parent-to-child handoff

Examples:

```text
I’m handing the phone to my daughter Emma.
Here is my 11-year-old son Daniel.
Talk to my child Maya.
```

If age was not included, Sarah asks:

```text
How old are you?
```

Until age is known, Sarah uses family-friendly behavior.

Child and teen profiles receive age-appropriate suggestions. Personal preference memory is off by default for a child profile. A future guardian-control screen can add explicit owner-managed permission.

### Adult memory permission without another setup form

Sarah does not ask every possible profile question immediately.

After an adult’s age is known, Sarah waits until the person shares something worth remembering:

```text
I love Doctor Who.
I like quiet museums.
I always travel with one small bag.
```

Then Sarah asks once whether to save that statement in the person’s separate profile. A yes saves the original interest or preference; a no keeps the conversation separate without creating a durable memory.

### Separate chat history and memory

Sarah 1.6 upgrades `sarah.db` to schema version 8. The `messages` table now includes `speaker_name`.

Existing messages migrate to the phone owner. New messages are stored and loaded only for the active profile.

Additional profiles use:

```text
sarah_people.db
```

Tables:

```text
people
person_memories
trip_participation
```

A non-owner profile does not receive:

- owner memories;
- owner wish-list places;
- owner deal watches;
- owner-private trip notes;
- another person’s chat history.

If a person is explicitly recorded as joining the current trip, Sarah may use the shared destination with that person’s own age, interests, pace, and needs. It does not copy the owner’s full profile.

Detailed profile and privacy architecture:

```text
SHARED_PHONE_PROFILES_AND_EVENT_DISCOVERY.md
```

## Asking whether a new person joins the planned trip

After age is known, Sarah checks for a current planned or upcoming owner trip.

Example:

```text
Sarah: Are you also going to Paris with Robert?
Emma: Yes.
```

Participation states:

```text
going
not_going
unknown
```

Sarah asks once and accepts the direct answer. It does not continue into a long questionnaire.

## Interests belong to the active profile

`MemoryExtractor.java` recognizes statements such as:

```text
I like Doctor Who.
I love Miraculous Ladybug.
I enjoy history documentaries.
I’m a fan of Spider-Man comics.
```

The memory is saved only when the active profile permits memory. It is never silently attached to the owner because someone else happens to be using the owner’s phone.

The Local reply names the profile where the interest was stored. Smart mode receives only the active profile’s memory context.

## `I am going to New York next week`

Sarah recognizes ordinary relative timing without making the person fill out a date form.

Supported phrases:

```text
next week
this weekend
next weekend
next month
tomorrow
```

`next week` means the next Monday through Sunday.

Example:

```text
I am going to New York next week.
```

Sarah should:

1. save a planned New York City trip with the interpreted dates;
2. give useful choices immediately;
3. separate free/inexpensive options from optional paid options;
4. adjust emphasis using the active profile’s interests and age;
5. avoid ending with another generic question;
6. use current sources for weather, closures, timed entry, dated events, and transit changes when available.

The built-in New York starter ideas include examples such as:

- free or inexpensive: Central Park, High Line, Grand Central Terminal, New York Public Library area, neighborhood walks, Staten Island Ferry;
- optional paid: one major museum, one observation deck, Broadway or off-Broadway, Statue of Liberty and Ellis Island.

These are background ideas, not proof of current access. Time-sensitive details require current sources.

Core files:

```text
TripWindowParser.java
CityVisitPlanner.java
AgenticTravelPlanner.java
AgenticActionExecutor.java
TimedTripCoordinator.java
```

## Random comic convention or event discovery

Sarah must not depend only on a short hard-coded list.

Known events still use direct official-source mappings, including Bell County Comic Con, PopCon Indy, CES, San Diego Comic-Con, and New York Comic Con.

Unfamiliar event-shaped names are handled by:

```text
GenericEventReference.java
PublicEventDiscoveryGateway.java
```

Examples:

```text
River City Collectors Con
North Shore Anime Convention
Mountain State Fan Expo
Future Mobility Conference
```

The parser recognizes the event but leaves its location blank until verified. It must not create a fake destination named after the event.

While online without OpenAI, Sarah performs a best-effort public discovery:

1. search for likely official event pages;
2. filter common social networks, resellers, and aggregators;
3. score title/domain matches;
4. read schema.org Event fields or visible public metadata;
5. save verified location, venue, date, and official URL when available;
6. leave uncertain fields blank;
7. label the page as likely official when discovered through search.

Short follow-ups retain the event:

```text
Person: I am thinking about going to River City Collectors Con.
Person: When is it?
```

Sarah searches the same event rather than replying to the literal phrase `When is it?` as a new subject.

Search discovery is not proof. The official page must be reviewed before booking.

## Visible media

Sarah 1.6 fixes the layout that could clip an image into a thin button. The Explore panel now uses content height and can visibly display the public thumbnail.

The panel can show:

- inline Wikimedia Commons photo preview;
- active event or destination name;
- Map;
- more public Photos;
- Videos;
- Route and local transit;
- Official event page or public search;
- Live travel sources.

Media context is filtered by active profile. A child who receives the phone does not automatically inherit the owner’s previous event or destination in the media panel.

Core files:

```text
ExploreButton.java
PublicMediaGateway.java
TravelMediaHelper.java
TravelSearchHelper.java
TravelExplorerActivity.java
activity_main.xml
```

Public media and external services do not endorse Sarah. A picture is contextual and does not prove current appearance, access, hours, safety, or accessibility.

## General conversation outside travel

Sarah is allowed to talk about ordinary life, movies, television, books, comics, games, AI, computers, robotics, programming, emotions, and project ideas without forcing the subject back to travel.

OpenAI handles broad natural conversation when the team connection exists. `DemoSarah.java` is a structured fallback, not a complete language model.

Do not try to imitate a full model by adding hundreds of overlapping scripted phrase checks. Add a structured intent, a current-source tool, or a connected provider at the correct layer.

## Public factual lookup without OpenAI

While online, Sarah can use selected public sources even when the team OpenAI connection is absent.

Supported paths include:

- known official-event pages;
- best-effort unfamiliar event discovery;
- filming-location questions;
- selected `who is`, `what is`, `tell me about`, `explain`, and `where is` background questions;
- maps, public photos, videos, routes, and public search.

This is not an unrestricted autonomous web agent. Rapidly changing facts still require official current sources or the team backend.

## Travel methods

Sarah does not assume every journey is a flight.

Recognized method families:

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

Multimodal state:

```text
sarah_mobility.db
```

Detailed architecture:

```text
MULTIMODAL_TRAVEL_AND_VISUAL_EXPLORER.md
```

## Event-centered trips

Known or verified events can create monitored event trips without a form:

```text
I am going to Vegas for CES.
I am going to San Diego for Comic-Con.
I was thinking about taking metro to New York Comic Con.
I am thinking about going to Indy Pop Con.
```

Sarah can store:

- canonical event name and destination;
- venue and official URL;
- verified dates and hours;
- source-backed updates;
- nearby food and places;
- transportation and accessibility notes;
- monitoring status and check times.

Event and booking state:

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

- Expedia, Booking.com, airline, hotel, rail, car, or event-booking link;
- visible booking screenshot from the Gallery.

The app:

- stores the selected item as a pending import;
- does not sign into private accounts;
- does not reuse cookies or request a booking password;
- re-encodes screenshots so ordinary EXIF/GPS metadata is not copied;
- sends a screenshot only when an image-capable team model is available;
- marks extracted details `needs_confirmation`.

Model output is never proof that a booking exists.

## Memory and truth rules

Sarah separates:

1. conversational text;
2. active-person identity;
3. approved profile-specific memories;
4. trip and event planning state;
5. confirmed booking facts;
6. source-backed current information;
7. unverified suggestions or extracted candidates.

A spoken claim must not create a fake booking, notification, event monitor, knowledge pack, or deal watch. Durable actions pass through application stores and executors before Sarah may describe them as saved.

## Background monitoring

Android `JobScheduler` handles background work. Android may delay jobs for battery, standby, or connectivity reasons, so an interval is not an exact alarm.

Real airfare, Amtrak, bus, transit, driving, ferry, schedule, delay, and price notifications require lawful team-owned data sources or a protected backend. Sarah must not invent current prices or schedules.

## Main source structure

```text
Sarah_Morgan_Android_Phone_First_v3/
├── android-app/
│   └── app/src/main/java/com/kiraworld/sarahtravel/
├── tests/
└── ...

.github/workflows/build-apk.yml
MODEL_PROVIDER_CONFIGURATION.md
SHARED_PHONE_PROFILES_AND_EVENT_DISCOVERY.md
MULTIMODAL_TRAVEL_AND_VISUAL_EXPLORER.md
EVENT_TRIPS_AND_BOOKING_IMPORTS.md
PUBLIC_WEB_FALLBACK.md
backend_examples/openai_proxy/
```

Important classes:

| Class | Role |
|---|---|
| `MainActivity` | Profile-filtered chat, voice, photo input, and model/public/local routing |
| `PersonProfileStore` | Separate people, memories, and trip participation |
| `SpeakerContext` | Natural handoff, age, consent, and trip-participation dialogue |
| `ProfileButton` | Visible profile switch |
| `SarahDatabase` | Owner state and speaker-bound message history |
| `SarahModelConfig` | Team-owned OpenAI provider and model configuration |
| `ConnectedModelGateway` | Provider routing point |
| `OpenAIClient` | Direct Responses API implementation |
| `SarahBackendClient` | Protected provider-router client |
| `SarahPromptBuilder` | Identity, privacy, truth, and conversation instructions |
| `GenericEventReference` | Unfamiliar event-name and follow-up context |
| `PublicEventDiscoveryGateway` | Likely-official public event discovery |
| `TripWindowParser` | Relative trip dates |
| `CityVisitPlanner` | Useful city ideas before questions |
| `AgenticTravelPlanner` | Natural statement to durable action plan |
| `AgenticActionExecutor` | Applies actions to real stores |
| `ExploreButton` | Visible inline public image and media tools |
| `TravelNotebookActivity` | Evidence of memories, trips, events, bookings, and watches |

## Renaming Sarah

Search for:

```text
Sarah
Sarah Morgan
sarahtravel
SarahMorgan
```

Review the Android label, icon, onboarding, greetings, prompt identity, Local identity, voice text, notifications, documentation, workflow artifact names, backend names, database names, preferences, and Android Keystore aliases.

Changing the application ID, database names, preferences, Keystore aliases, or notification-channel IDs requires a migration plan.

## Building and testing

Workflow:

```text
.github/workflows/build-apk.yml
```

Expected artifact:

```text
Sarah-Morgan-1.6-profiles-events
```

Expected APK:

```text
Sarah-Morgan-1.6-profiles-events.apk
```

Verify in Settings:

```text
Build 1.6-profiles-events
```

Automated tests include:

- automatic OpenAI/public/local routing;
- active travel context;
- multimodal route parsing;
- event and booking planning;
- known event aliases;
- unfamiliar random event preservation;
- short event follow-ups;
- New York next-week date interpretation;
- free/inexpensive and optional paid city suggestions;
- Android compilation;
- APK creation.

Suggested physical-phone test:

1. install as an update over 1.5;
2. verify the person icon is visible;
3. pick a public event not in `KnownEventCatalog.java`;
4. confirm Sarah calls it an event, not a city;
5. ask `When is it?` and confirm context remains;
6. verify the inline photo panel has visible image height;
7. say `I am going to New York next week`;
8. confirm free/inexpensive and optional paid ideas appear without a form;
9. say `I like [movie/show]` and confirm the active profile receives the memory;
10. say `My name is Emma` or use the profile icon;
11. confirm Sarah asks age for a new person;
12. if a planned trip exists, confirm Sarah asks once whether Emma joins it;
13. switch profiles and confirm chat histories do not mix;
14. test internet loss and recovery;
15. test large text, rotation, voice, and photo selection.

## Known boundaries

- Full OpenAI conversation requires a team backend or private build credential.
- Public lookup is narrower than a complete language model.
- Public event discovery cannot guarantee that a search result is official; source review is required.
- Public HTML and search formats can change.
- Child memory remains off by default; a full guardian-consent screen is future work.
- Profiles are local to this installation and do not sync across devices.
- Name switching is a convenience boundary, not biometric identity verification.
- Wikimedia may not have an event-specific image; the preview may fall back to the destination.
- External sites may work better in the browser than an embedded WebView.
- Android background work is not exact.
- A debug APK is not production-signed.
- A public release requires authentication, child/privacy review, deletion/export controls, source documentation, billing/rate controls, security review, accessibility testing, broader device testing, and store compliance.
