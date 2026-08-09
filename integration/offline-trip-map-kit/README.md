# Sarah Offline Trip Map Kit

A portable, offline-first module for a Sarah-compatible travel UI. It combines:

- confirmed trip details such as flights, airports, hotels, transport, and tickets;
- recommendations ranked from the authorized social-interest scraper plus Sarah's general knowledge of the selected traveler;
- personal travel notes;
- an always-available schematic map;
- an adapter request for a full street-level offline map package;
- source freshness and offline-readiness labels;
- optional, truthful hackathon-sponsor enrichments.

The module is separate from Sarah's Android screen. The other team can use it in a Lovable/React interface, another web UI, Android, iOS, Windows, or a backend that emits the same JSON contract.

## What is already implemented

```text
social-interest-v1 result
        +
Sarah profile interests and explicit traveler interests
        +
confirmed trip facts (flight / hotel / ticket / transport)
        +
personal notes
        +
source-backed candidate places
        ↓
buildOfflineTripBundle(...)
        ↓
sarah-offline-trip-map-v1 bundle
        ├── ranked recommendations with "why"
        ├── map pins and route bounds
        ├── offline map download request
        ├── trip facts and notes
        ├── data freshness
        ├── readiness score
        └── sponsor enrichment plan
```

The supplied custom element renders the trip immediately as an offline schematic map. A full map provider can replace or overlay that surface after it downloads a lawful offline region.

## Files

- `src/offline-trip-map-kit.mjs` — merges interests, ranks places, creates map pins, computes bounds, produces the bundle, and prepares sponsor actions.
- `src/OfflineTripStore.mjs` — IndexedDB persistence for trip data, recommendations, notes, freshness, and map-package references.
- `src/map-adapter.mjs` — MapLibre/PMTiles request helpers, schematic projection, and a tile-policy guard.
- `ui/sarah-offline-trip-map.js` — dependency-free UI custom element.
- `ui/LovableOfflineTripMap.tsx` — thin React/Lovable wrapper.
- `contracts/offline-trip-map-v1.schema.json` — portable UI/backend contract.
- `examples/auckland-trip-input.json` — fictional example without a real booking code.
- `tests/offline-trip-map-kit.test.mjs` — no-dependency Node test.

## Run the test

```bash
cd integration/offline-trip-map-kit
npm test
```

## 1. Connect the social-interest scraper

The existing scraper module emits `sarah-social-interest-v1`. Pass its learned-interest result or authorized normalized signals into `socialInterestPayload`.

```js
import { buildOfflineTripBundle } from "./src/offline-trip-map-kit.mjs";

const bundle = buildOfflineTripBundle({
  personScopeId: activeProfile.id,
  trip: {
    id: trip.id,
    title: trip.title,
    destination: trip.destination,
    startDate: trip.startDate,
    endDate: trip.endDate,
    budgetLevel: activeProfile.budgetLevel,
    accessibilityNeeds: activeProfile.accessibilityNeeds
  },

  // What the traveler explicitly told Sarah.
  explicitInterests: activeProfile.explicitInterests,

  // Sarah's existing profile-specific learned/general interests.
  profileInterests: activeProfile.profileInterests,

  // Output from PR #24's social-interest module or the equivalent HTTP result.
  socialInterestPayload: socialInterestV1Result,

  tripFacts: confirmedFlightsHotelsTicketsAndTransport,
  notes: travelerNotes,
  candidatePlaces: currentSourceBackedPlaces
});
```

The map kit never receives Instagram passwords, OAuth refresh tokens, cookies, or raw private-account credentials. It receives only the normalized/correctable interest result.

Accepted social payload shapes include:

```json
{
  "schema": "sarah-social-interest-v1",
  "interests": [
    {
      "topic": "Power Rangers",
      "confidence": 0.94,
      "evidence_count": 14,
      "sources": ["instagram", "youtube"]
    }
  ]
}
```

or authorized normalized `signals` from the existing contract. A user's explicit interest or correction outranks a probabilistic social inference. `rejectedTopics` removes an incorrect learned interest before recommendation ranking.

## 2. Add plane tickets, hotels, tickets, and transport

Map each confirmed or traveler-reviewed item to `tripFacts`:

```js
const tripFacts = [
  {
    id: "arrival-airport",
    kind: "airport",
    title: "Auckland Airport",
    latitude: -37.0082,
    longitude: 174.785,
    startTime: "2026-09-21T08:30:00+12:00",
    confirmed: true,
    source: "traveler-reviewed booking import",
    summary: "Arrival point; recheck terminal before travel."
  },
  {
    id: "hotel",
    kind: "hotel",
    title: "Confirmed hotel",
    latitude: -36.849,
    longitude: 174.765,
    startTime: "2026-09-21T15:00:00+12:00",
    confirmed: true
  }
];
```

Supported `kind` values:

```text
flight, airport, hotel, ticket, transport, place,
recommendation, note, emergency, other
```

By default the builder does **not** copy a confirmation code into the portable offline bundle. Set `includeSensitiveOffline: true` only after the host has an encrypted, profile-isolated storage design and the traveler has chosen that behavior.

Opening or importing a booking link is not proof that a booking exists. Only traveler-reviewed or authenticated provider data should be marked `confirmed: true`.

## 3. Rank recommendations using social interests and Sarah's knowledge

Candidate places can come from Tavily-backed research, official tourism pages, the traveler, or another lawful provider.

```js
const candidatePlaces = [
  {
    id: "place-123",
    title: "Possible filming-location stop",
    latitude: -36.86,
    longitude: 174.77,
    tags: ["Power Rangers", "filming locations"],
    summary: "Verify public access and current conditions before visiting.",
    openDuringTrip: true,
    priceLevel: 1,
    accessibilityTags: ["rest breaks"],
    source: "official or current-source discovery",
    sourceUrl: "https://example.com/source",
    sourceVerified: true,
    checkedAt: "2026-09-20T12:00:00Z"
  }
];
```

Ranking considers:

- matching explicit, profile, and social-learned interests;
- proximity to confirmed trip anchors;
- trip-date fit;
- budget fit;
- accessibility needs;
- whether the traveler personally saved the place;
- whether a source was verified.

The result includes `score`, `matchedInterests`, and human-readable `reasons`, so the UI can show **Why Sarah recommended this**.

## 4. Add personal travel notes

The UI emits `sarah-note-added`. Persist it with `OfflineTripStore`:

```js
import { OfflineTripStore } from "./src/OfflineTripStore.mjs";

const store = new OfflineTripStore();
await store.saveBundle(bundle);

mapElement.addEventListener("sarah-note-added", async (event) => {
  const updated = await store.addNote(
    event.detail.personScopeId,
    event.detail.tripId,
    event.detail.note
  );
  mapElement.bundle = updated;
});
```

A note may be attached to coordinates, a day, or a time, or remain an ordinary trip note without coordinates.

## 5. Add the UI

### Plain web UI

```html
<script type="module" src="./ui/sarah-offline-trip-map.js"></script>
<sarah-offline-trip-map id="trip-map"></sarah-offline-trip-map>
<script type="module">
  const element = document.querySelector("#trip-map");
  element.bundle = bundle;
</script>
```

### Lovable / React

Copy `ui/LovableOfflineTripMap.tsx` and the custom-element file into the project:

```tsx
<LovableOfflineTripMap
  bundle={bundle}
  onMapDownloadRequested={({ request }) => mapProvider.downloadRegion(request)}
  onNoteAdded={saveNote}
  onPlaceSelected={openPlaceDetails}
/>
```

The custom element is dependency-free. It renders an SVG itinerary map from the stored coordinates, bookings, recommendations, and notes, so the traveler still has a useful map when no tile package is installed.

## 6. Connect a full offline street map

The kit deliberately does not bulk-download tiles from an arbitrary public map server. The host must use a source that expressly permits offline packages and preserve required attribution.

### Android: MapLibre Native

`buildMapLibreRegionDefinition(bundle, { styleUrl })` returns the style, bounds, zoom range, pixel ratio, and metadata needed by a MapLibre Native offline-region adapter. The Android host can use MapLibre's `OfflineManager` and an `OfflineTilePyramidRegionDefinition` or geometry region.

```js
import { buildMapLibreRegionDefinition } from "./src/map-adapter.mjs";

const definition = buildMapLibreRegionDefinition(bundle, {
  styleUrl: approvedOfflineStyleUrl
});
```

This is a request definition, not a downloader hidden inside the UI. Implement the actual Android/iOS SDK call in the platform adapter and update:

```text
mapPackageRequest.state
mapPackageRequest.packageId
mapPackageRequest.downloadedAt
mapPackageRequest.estimatedBytes
```

### Web / Lovable: MapLibre GL + PMTiles

For a browser UI, use a local or licensed PMTiles archive and MapLibre GL, then store the selected archive/style through the browser's supported offline storage approach. `buildPmtilesManifest(...)` creates the trip-specific manifest.

Do not point a “Download city” button at `tile.openstreetmap.org` or `vector.openstreetmap.org`; those public community servers prohibit bulk/offline tile downloading. Use self-hosted lawful tiles or a provider whose terms explicitly allow offline packages.

### Mapbox option

A host may instead implement Mapbox's supported offline region/style-pack APIs and translate `mapPackageRequest` into that SDK's request. Keep Mapbox credentials in the host's protected configuration and follow its current pricing and offline limits.

## 7. Offline data design

`OfflineTripStore` puts the JSON bundle in IndexedDB. Map SDK tiles stay in the map SDK/provider's own storage.

Recommended offline package:

```text
Trip JSON bundle
├── itinerary and confirmed facts
├── hotel / airport / station / ticket pins
├── recommendation explanations
├── personal notes
├── cached source summaries and URLs
├── last-checked timestamps
├── map package ID/status
└── optional cached spoken briefing

Map provider storage
├── style
├── permitted vector/raster tiles
├── glyphs
└── sprites
```

Every current-data card should display one of:

```text
LIVE
CACHED — checked <timestamp>
STALE — refresh recommended
OFFLINE-ONLY PERSONAL NOTE
```

The traveler must still be able to read the trip facts, notes, recommendation explanations, and schematic map if the full tile package fails.

## 8. Hackathon sponsor and credit-sponsor opportunities

The current event page identifies Stay22, Rove, and Propellic as sponsors, and ElevenLabs, Lovable, Tavily, and AeroXplorer as credit sponsors. The bundle includes a `sponsorEnrichmentPlan` for all seven.

### Stay22

Use Sarah's existing user-initiated Stay22 search path to find hotel candidates or compare nearby stays. Put selected candidates on the map, cache only the permitted summary fields and timestamp, and keep the review/booking link online-only. Never label a listing or quote as a booking.

### Rove

Show a **Check rewards value with Rove** action from flight/hotel cards. Cache the user's note or a comparison summary, but use an official online handoff unless a documented API is supplied. Do not invent a redemption API.

### Propellic

Use the top interest themes and destination to improve the demo's destination story and explain which traveler segment the personalized map serves. The current kit treats this as presentation/marketing insight, not a claimed technical API.

### ElevenLabs

Generate an optional spoken **Today / Trip briefing** while online. If the active account and content terms allow the audio to be stored, save the returned audio asset with the trip package for later offline playback. Keep local device TTS as the offline fallback.

### Lovable

Use the supplied `LovableOfflineTripMap.tsx` wrapper in the other team's Lovable-generated React interface. Lovable is the natural place to assemble the cards, download status, notes, and map provider adapter.

### Tavily

Before the traveler downloads the trip, refresh recommendations, current events, official pages, opening hours, airport guidance, and neighborhood context. Cache a concise summary, source URL, and `checkedAt`; never present cached data as live after it becomes stale.

### AeroXplorer

Add sourced aviation context to airport/flight pins: airport background, airline or aircraft explainers, and travel-industry stories. It must not be treated as live aircraft telemetry or authoritative operational flight status.

## 9. Events emitted by the custom element

```text
sarah-map-download-requested
  detail: { tripId, personScopeId, request }

sarah-place-selected
  detail: { anchor }

sarah-note-added
  detail: { tripId, personScopeId, note }
```

The host remains responsible for authorization, persistence, map download progress, current-source refresh, and booking actions.

## 10. Privacy and truth boundaries

- Keep every bundle isolated by `personScopeId` and `tripId`.
- Never put social passwords, cookies, OAuth refresh tokens, map-provider secrets, or payment-card data in the bundle.
- Encrypt sensitive offline facts at rest if the host permits them at all.
- Let the traveler remove or correct social-inferred interests.
- Do not show a recommendation as confirmed itinerary until the traveler adds it.
- Show source and last-checked time for cached current information.
- Opening a provider page is not a booking, redemption, reservation, call, or completed task.
- Preserve map-data attribution and the selected provider's offline-use terms.

## Minimal integration checklist

1. Read `sarah-social-interest-v1` from PR #24.
2. Load the active Sarah/person profile only.
3. Load traveler-reviewed flight, hotel, transport, and ticket facts.
4. Search for candidate places while online and preserve sources/timestamps.
5. Call `buildOfflineTripBundle`.
6. Save it through `OfflineTripStore`.
7. Render `sarah-offline-trip-map` or `LovableOfflineTripMap`.
8. Connect `sarah-map-download-requested` to MapLibre, PMTiles, Mapbox, or another lawful offline-map adapter.
9. Refresh stale current data before departure.
10. Test airplane mode with the tile package unavailable as well as available.
