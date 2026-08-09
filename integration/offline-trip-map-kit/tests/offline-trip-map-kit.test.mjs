import assert from "node:assert/strict";
import {
  OFFLINE_TRIP_MAP_SCHEMA,
  buildOfflineTripBundle,
  mergeInterestProfile,
  rankCandidatePlaces,
  socialInterestV1ToInterests
} from "../src/offline-trip-map-kit.mjs";
import { OfflineTripStore } from "../src/OfflineTripStore.mjs";
import { assertOfflineTilePolicy, buildMapLibreRegionDefinition } from "../src/map-adapter.mjs";

const social = {
  schema: "sarah-social-interest-v1",
  result: {
    signals: [
      { source: "instagram", action: "SAVE", topics: ["Power Rangers", "filming locations"], user_authorized: true },
      { source: "youtube", action: "FOLLOW", topics: ["Power Rangers"], user_authorized: true },
      { source: "instagram", action: "LIKE", topics: ["baseball"], user_authorized: true }
    ]
  }
};
const learned = socialInterestV1ToInterests(social);
assert.equal(learned[0].topic, "Power Rangers");
assert.ok(learned[0].confidence > learned.find((row) => row.topic === "baseball").confidence);

const interests = mergeInterestProfile({
  explicitInterests: ["movie locations"],
  profileInterests: ["history"],
  socialInterestPayload: social,
  rejectedTopics: ["baseball"]
});
assert.ok(interests.some((row) => row.topic === "Power Rangers"));
assert.ok(!interests.some((row) => row.topic === "baseball"));
assert.equal(interests.find((row) => row.topic === "movie locations").confidence, 1);

const anchors = [
  { id: "hotel", kind: "hotel", title: "Hotel", latitude: -36.85, longitude: 174.76, confirmed: true, sensitive: true }
];
const places = rankCandidatePlaces({
  interests,
  anchors,
  trip: { budgetLevel: 2 },
  candidates: [
    { id: "power", title: "Filming stop", latitude: -36.851, longitude: 174.761, tags: ["Power Rangers", "movie locations"], openDuringTrip: true, priceLevel: 1 },
    { id: "random", title: "Random field", latitude: -37.2, longitude: 175.2, tags: ["baseball"], openDuringTrip: true, priceLevel: 1 }
  ]
});
assert.equal(places[0].id, "power");
assert.ok(places[0].score > places[1].score);

const bundle = buildOfflineTripBundle({
  personScopeId: "person-1",
  trip: { id: "trip-1", title: "Auckland", destination: "Auckland", startDate: "2026-09-21", endDate: "2026-09-27", budgetLevel: 2 },
  explicitInterests: ["Power Rangers"],
  socialInterestPayload: social,
  tripFacts: [
    { id: "airport", kind: "airport", title: "Airport", latitude: -37.0082, longitude: 174.785, confirmed: true },
    { id: "hotel", kind: "hotel", title: "Hotel", latitude: -36.85, longitude: 174.76, confirmed: true, confirmationCode: "SHOULD_NOT_BE_STORED" }
  ],
  notes: [{ body: "Ask about luggage storage", latitude: -36.85, longitude: 174.76 }],
  candidatePlaces: places,
  nowMs: Date.parse("2026-09-20T12:00:00Z")
});
assert.equal(bundle.schema, OFFLINE_TRIP_MAP_SCHEMA);
assert.ok(bundle.mapPackageRequest.bounds);
assert.ok(bundle.sponsorEnrichmentPlan.some((row) => row.sponsorId === "stay22"));
assert.ok(bundle.sponsorEnrichmentPlan.some((row) => row.sponsorId === "tavily"));
assert.equal(bundle.tripFacts.find((row) => row.id === "hotel").reference, "");
assert.ok(bundle.readiness.score >= 80);

const mapLibre = buildMapLibreRegionDefinition(bundle, { styleUrl: "https://maps.example/style.json" });
assert.equal(mapLibre.metadata.tripId, "trip-1");
assert.equal(mapLibre.bounds.north, bundle.mapPackageRequest.bounds.north);

assert.throws(() => assertOfflineTilePolicy({
  tileSource: "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
  offlineAllowed: true
}), /must not be bulk-downloaded/);
assert.equal(assertOfflineTilePolicy({ tileSource: "https://maps.example/tiles", offlineAllowed: true }), true);

const store = new OfflineTripStore({ indexedDBImpl: null });
await store.saveBundle(bundle);
const loaded = await store.loadBundle("person-1", "trip-1");
assert.equal(loaded.tripId, "trip-1");
const withNote = await store.addNote("person-1", "trip-1", { body: "Buy a transit card" });
assert.equal(withNote.personalNotes.length, 2);

console.log("offline-trip-map-kit tests passed");
