/**
 * UI-neutral trip-map builder. It never fetches map tiles or social accounts.
 * A host supplies authorized social-interest results, confirmed trip facts,
 * candidate places, personal notes, and a map provider that permits offline use.
 */

export const OFFLINE_TRIP_MAP_SCHEMA = "sarah-offline-trip-map-v1";
export const SOCIAL_INTEREST_SCHEMA = "sarah-social-interest-v1";

export const SPONSOR_CATALOG = Object.freeze({
  stay22: {
    label: "Stay22",
    role: "User-initiated hotel discovery and review links",
    onlineRequired: true,
    cacheableSummary: true,
    mustNotClaim: "A search result, quoted price, or provider link is not a booking."
  },
  rove: {
    label: "Rove",
    role: "Rewards-aware flight and hotel handoff",
    onlineRequired: true,
    cacheableSummary: true,
    mustNotClaim: "Do not claim an undocumented booking or rewards-redemption API."
  },
  propellic: {
    label: "Propellic",
    role: "Destination-marketing and presentation insight",
    onlineRequired: false,
    cacheableSummary: true,
    mustNotClaim: "Do not claim a technical API unless one is actually supplied."
  },
  elevenlabs: {
    label: "ElevenLabs",
    role: "Generate an optional spoken trip briefing while online",
    onlineRequired: true,
    cacheableSummary: true,
    mustNotClaim: "Cached audio must follow the account, voice, and content terms."
  },
  lovable: {
    label: "Lovable",
    role: "Host or adapt the supplied web component in a Lovable/React UI",
    onlineRequired: false,
    cacheableSummary: false,
    mustNotClaim: "Lovable is the UI build surface, not a map-data provider."
  },
  tavily: {
    label: "Tavily",
    role: "Refresh source-backed destination and event discoveries before download",
    onlineRequired: true,
    cacheableSummary: true,
    mustNotClaim: "Cached results must show source and last-checked time."
  },
  aeroxplorer: {
    label: "AeroXplorer",
    role: "Airport, airline, aircraft, and aviation editorial context",
    onlineRequired: true,
    cacheableSummary: true,
    mustNotClaim: "Editorial context is not live aircraft telemetry or flight status."
  }
});

const ACTION_WEIGHTS = Object.freeze({
  EXPLICIT: 3.0,
  FOLLOW: 2.1,
  SAVE: 1.8,
  SHARE: 1.6,
  COMMENT: 1.4,
  POST: 1.3,
  LIKE: 1.0,
  VIEW: 0.35
});

export function socialInterestV1ToInterests(payload) {
  if (!payload || typeof payload !== "object") return [];
  const declaredSchema = clean(payload.schema || payload.contract || "");
  if (declaredSchema && declaredSchema !== SOCIAL_INTEREST_SCHEMA && declaredSchema !== "social-interest-v1") {
    throw new Error(`Unsupported social-interest schema: ${declaredSchema}`);
  }

  const learned = firstArray(
    payload.learned_interests,
    payload.interests,
    payload.result?.learned_interests,
    payload.result?.interests
  );
  if (learned.length) {
    return dedupeInterests(learned.map((row) => ({
      topic: clean(row?.topic || row?.name || row?.label),
      confidence: clampNumber(row?.confidence, 0, 0.95, 0.5),
      evidenceCount: toNonNegativeInt(row?.evidence_count ?? row?.evidenceCount, 1),
      sources: uniqueStrings(row?.sources || [row?.source || payload.platform || "social"]),
      origin: "social"
    })).filter((row) => row.topic));
  }

  const signals = firstArray(payload.signals, payload.result?.signals);
  const byTopic = new Map();
  for (const signal of signals) {
    if (!signal || signal.user_authorized === false || signal.userAuthorized === false) continue;
    const action = clean(signal.action || "VIEW").toUpperCase();
    const weight = ACTION_WEIGHTS[action] ?? ACTION_WEIGHTS.VIEW;
    const source = clean(signal.source || payload.platform || "social");
    for (const topic of uniqueStrings(signal.topics || signal.tags || [])) {
      const key = topic.toLowerCase();
      const current = byTopic.get(key) || { topic, score: 0, evidenceCount: 0, sources: [] };
      current.score += weight;
      current.evidenceCount += 1;
      current.sources = uniqueStrings([...current.sources, source]);
      byTopic.set(key, current);
    }
  }

  return [...byTopic.values()].map((row) => {
    const repetition = 1 - Math.exp(-row.score / 4);
    const evidenceBoost = Math.min(0.12, Math.max(0, row.evidenceCount - 1) * 0.02);
    return {
      topic: row.topic,
      confidence: round(Math.min(0.95, 0.2 + 0.7 * repetition + evidenceBoost)),
      evidenceCount: row.evidenceCount,
      sources: row.sources,
      origin: "social"
    };
  }).sort(compareInterest);
}

export function mergeInterestProfile({
  explicitInterests = [],
  socialInterestPayload = null,
  profileInterests = [],
  rejectedTopics = []
} = {}) {
  const rejected = new Set(uniqueStrings(rejectedTopics).map((value) => value.toLowerCase()));
  const merged = new Map();

  const put = (row) => {
    const topic = clean(typeof row === "string" ? row : row?.topic || row?.name || row?.label);
    if (!topic || rejected.has(topic.toLowerCase())) return;
    const key = topic.toLowerCase();
    const origin = clean(typeof row === "string" ? "profile" : row.origin || row.source || "profile");
    const confidence = typeof row === "string"
      ? 0.82
      : clampNumber(row.confidence, 0, 1, origin === "explicit" ? 1 : 0.82);
    const existing = merged.get(key);
    if (!existing) {
      merged.set(key, {
        topic,
        confidence: round(confidence),
        evidenceCount: toNonNegativeInt(row?.evidenceCount ?? row?.evidence_count, 1),
        sources: uniqueStrings(row?.sources || [origin]),
        origins: uniqueStrings([origin])
      });
      return;
    }
    existing.confidence = round(Math.max(existing.confidence, confidence));
    existing.evidenceCount += toNonNegativeInt(row?.evidenceCount ?? row?.evidence_count, 1);
    existing.sources = uniqueStrings([...existing.sources, ...(row?.sources || [origin])]);
    existing.origins = uniqueStrings([...existing.origins, origin]);
  };

  for (const value of profileInterests) put(typeof value === "string" ? { topic: value, origin: "profile", confidence: 0.82 } : { ...value, origin: value.origin || "profile" });
  for (const value of socialInterestV1ToInterests(socialInterestPayload)) put(value);
  for (const value of explicitInterests) put(typeof value === "string" ? { topic: value, origin: "explicit", confidence: 1 } : { ...value, origin: "explicit", confidence: value.confidence ?? 1 });

  return [...merged.values()].sort(compareInterest);
}

export function rankCandidatePlaces({
  candidates = [],
  interests = [],
  anchors = [],
  trip = {},
  limit = 20
} = {}) {
  const normalizedInterests = dedupeInterests(interests);
  const confirmedAnchors = anchors.filter(hasCoordinates);
  const needs = uniqueStrings(trip.accessibilityNeeds || trip.accessibility_needs || []);
  const budgetLevel = clampNumber(trip.budgetLevel ?? trip.budget_level, 0, 4, 2);

  const ranked = candidates.map((candidate, index) => {
    const tags = uniqueStrings([
      ...(candidate.tags || []),
      ...(candidate.categories || []),
      ...(candidate.interests || [])
    ]);
    const matched = [];
    let interestScore = 0;
    for (const interest of normalizedInterests) {
      if (!topicMatches(interest.topic, tags, candidate.title, candidate.summary)) continue;
      matched.push(interest.topic);
      interestScore += 0.13 + 0.16 * clampNumber(interest.confidence, 0, 1, 0.5);
    }
    interestScore = Math.min(0.55, interestScore);

    const distanceKm = nearestDistanceKm(candidate, confirmedAnchors);
    let proximityScore = 0;
    if (Number.isFinite(distanceKm)) {
      if (distanceKm <= 1) proximityScore = 0.20;
      else if (distanceKm <= 5) proximityScore = 0.15;
      else if (distanceKm <= 15) proximityScore = 0.08;
      else if (distanceKm <= 50) proximityScore = 0.03;
    }

    const scheduleScore = candidate.openDuringTrip === true ? 0.10 : candidate.openDuringTrip === false ? -0.20 : 0;
    const candidatePrice = clampNumber(candidate.priceLevel ?? candidate.price_level, 0, 4, budgetLevel);
    const budgetScore = candidatePrice <= budgetLevel ? 0.08 : candidatePrice === budgetLevel + 1 ? 0.03 : -0.08;
    const accessTags = uniqueStrings(candidate.accessibilityTags || candidate.accessibility_tags || []);
    const unmetNeeds = needs.filter((need) => !topicMatches(need, accessTags, candidate.summary));
    const accessibilityScore = needs.length === 0 ? 0.03 : unmetNeeds.length === 0 ? 0.07 : -0.10;
    const savedScore = candidate.userSaved === true || candidate.user_saved === true ? 0.10 : 0;
    const sourceScore = candidate.sourceVerified === true || candidate.source_verified === true ? 0.04 : 0;

    const rawScore = 0.08 + interestScore + proximityScore + scheduleScore + budgetScore + accessibilityScore + savedScore + sourceScore;
    const score = round(clampNumber(rawScore, 0, 1, 0));
    const reasons = [];
    if (matched.length) reasons.push(`Matches ${matched.slice(0, 3).join(", ")}`);
    if (Number.isFinite(distanceKm)) reasons.push(`${formatDistance(distanceKm)} from a trip anchor`);
    if (candidate.userSaved === true || candidate.user_saved === true) reasons.push("You saved this place");
    if (candidate.openDuringTrip === true) reasons.push("Reported to fit the trip dates; recheck before visiting");
    if (unmetNeeds.length === 0 && needs.length) reasons.push("Matches the saved accessibility needs");
    if (!reasons.length) reasons.push("General destination match; verify before adding");

    return {
      ...candidate,
      id: clean(candidate.id) || `recommendation-${index + 1}`,
      title: clean(candidate.title) || "Possible place",
      score,
      matchedInterests: uniqueStrings(matched),
      reasons,
      nearestAnchorDistanceKm: Number.isFinite(distanceKm) ? round(distanceKm) : null,
      checkedAt: clean(candidate.checkedAt || candidate.checked_at || ""),
      source: clean(candidate.source || ""),
      sourceUrl: safeHttps(candidate.sourceUrl || candidate.source_url || "")
    };
  });

  ranked.sort((a, b) => b.score - a.score || a.title.localeCompare(b.title));
  return ranked.slice(0, Math.max(0, limit));
}

export function buildOfflineTripBundle(input = {}) {
  const trip = input.trip || {};
  const tripId = clean(trip.id || input.tripId || input.trip_id);
  const personScopeId = clean(input.personScopeId || input.person_scope_id || trip.personScopeId || trip.person_scope_id);
  if (!tripId) throw new Error("trip.id is required");
  if (!personScopeId) throw new Error("personScopeId is required");

  const interests = mergeInterestProfile({
    explicitInterests: input.explicitInterests || input.explicit_interests || [],
    socialInterestPayload: input.socialInterestPayload || input.social_interest_payload || null,
    profileInterests: input.profileInterests || input.profile_interests || [],
    rejectedTopics: input.rejectedTopics || input.rejected_topics || []
  });

  const factAnchors = tripFactsToAnchors(input.tripFacts || input.trip_facts || input.bookings || [], input.includeSensitiveOffline === true);
  const noteAnchors = notesToAnchors(input.notes || [], personScopeId);
  const baseAnchors = [...factAnchors, ...noteAnchors];
  const rankedRecommendations = rankCandidatePlaces({
    candidates: input.candidatePlaces || input.candidate_places || [],
    interests,
    anchors: baseAnchors,
    trip,
    limit: input.recommendationLimit || input.recommendation_limit || 20
  });
  const recommendationAnchors = rankedRecommendations.map(recommendationToAnchor);
  const anchors = [...baseAnchors, ...recommendationAnchors];
  const mapPackageRequest = planOfflineRegion(anchors, {
    destinationCenter: trip.destinationCenter || trip.destination_center,
    minZoom: input.minZoom ?? input.min_zoom,
    maxZoom: input.maxZoom ?? input.max_zoom,
    paddingRatio: input.paddingRatio ?? input.padding_ratio,
    styleUrl: input.styleUrl || input.style_url || ""
  });

  const bundle = {
    schema: OFFLINE_TRIP_MAP_SCHEMA,
    version: 1,
    tripId,
    personScopeId,
    title: clean(trip.title) || clean(trip.destination) || "Trip",
    destination: clean(trip.destination),
    startDate: clean(trip.startDate || trip.start_date),
    endDate: clean(trip.endDate || trip.end_date),
    timezone: clean(trip.timezone),
    generatedAt: new Date(input.nowMs ?? Date.now()).toISOString(),
    privacy: {
      profileIsolated: true,
      containsSensitiveOfflineFields: input.includeSensitiveOffline === true,
      socialCredentialsIncluded: false
    },
    interests,
    tripFacts: factAnchors,
    personalNotes: noteAnchors,
    recommendations: rankedRecommendations,
    anchors,
    mapPackageRequest,
    sponsorEnrichmentPlan: [],
    freshness: buildFreshness(input, rankedRecommendations),
    readiness: null
  };
  bundle.sponsorEnrichmentPlan = buildSponsorEnrichmentPlan(bundle, input.sponsors || Object.keys(SPONSOR_CATALOG));
  bundle.readiness = calculateOfflineReadiness(bundle);
  return bundle;
}

export function planOfflineRegion(anchors = [], options = {}) {
  const points = anchors.filter(hasCoordinates).map((anchor) => ({
    latitude: Number(anchor.latitude),
    longitude: Number(anchor.longitude)
  }));
  const center = options.destinationCenter;
  if (!points.length && center && Number.isFinite(Number(center.latitude)) && Number.isFinite(Number(center.longitude))) {
    points.push({ latitude: Number(center.latitude), longitude: Number(center.longitude) });
  }
  const bounds = computeBounds(points, clampNumber(options.paddingRatio, 0.02, 1, 0.18));
  return {
    provider: "host-selected-offline-map-provider",
    packageId: "",
    state: bounds ? "ready_to_download" : "needs_coordinates",
    bounds,
    minZoom: clampNumber(options.minZoom, 0, 22, 7),
    maxZoom: clampNumber(options.maxZoom, 0, 22, 15),
    pixelRatio: 1,
    styleUrl: clean(options.styleUrl),
    includeStyle: true,
    includeGlyphs: true,
    includeSprites: true,
    attributionRequired: true,
    offlineTilePermissionRequired: true,
    downloadedAt: "",
    estimatedBytes: null
  };
}

export function computeBounds(points = [], paddingRatio = 0.18) {
  const valid = points.filter((point) => Number.isFinite(Number(point.latitude)) && Number.isFinite(Number(point.longitude)));
  if (!valid.length) return null;
  let north = -90, south = 90, east = -180, west = 180;
  for (const point of valid) {
    const lat = Number(point.latitude);
    const lng = Number(point.longitude);
    north = Math.max(north, lat);
    south = Math.min(south, lat);
    east = Math.max(east, lng);
    west = Math.min(west, lng);
  }
  const latSpan = Math.max(0.02, north - south);
  const lngSpan = Math.max(0.02, east - west);
  const latPad = latSpan * paddingRatio;
  const lngPad = lngSpan * paddingRatio;
  return {
    north: round(Math.min(90, north + latPad)),
    south: round(Math.max(-90, south - latPad)),
    east: round(Math.min(180, east + lngPad)),
    west: round(Math.max(-180, west - lngPad))
  };
}

export function buildSponsorEnrichmentPlan(bundle, enabledSponsors = Object.keys(SPONSOR_CATALOG)) {
  const enabled = new Set(uniqueStrings(enabledSponsors).map((value) => value.toLowerCase()));
  const destination = clean(bundle?.destination);
  const dates = [bundle?.startDate, bundle?.endDate].filter(Boolean).join(" to ");
  const topInterests = (bundle?.interests || []).slice(0, 5).map((row) => row.topic);
  const plan = [];

  const add = (id, action, query, details = {}) => {
    if (!enabled.has(id)) return;
    const sponsor = SPONSOR_CATALOG[id];
    plan.push({
      sponsorId: id,
      sponsorLabel: sponsor.label,
      action,
      query: clean(query),
      onlineRequired: sponsor.onlineRequired,
      userInitiated: details.userInitiated === true,
      cacheForOffline: sponsor.cacheableSummary,
      offlineFields: details.offlineFields || ["title", "summary", "sourceUrl", "checkedAt"],
      truthBoundary: sponsor.mustNotClaim
    });
  };

  add("stay22", "hotel_search", `${destination} ${dates}`.trim(), {
    userInitiated: true,
    offlineFields: ["hotelName", "provider", "quotedTotal", "currency", "reviewUrl", "checkedAt"]
  });
  add("rove", "rewards_handoff", `${destination} flights hotels rewards`.trim(), { userInitiated: true });
  add("propellic", "destination_story", `${destination} destination themes ${topInterests.join(" ")}`.trim());
  add("elevenlabs", "spoken_offline_briefing", `${bundle?.title || destination} trip briefing`, {
    userInitiated: true,
    offlineFields: ["audioAssetId", "format", "generatedAt", "voiceLabel"]
  });
  add("lovable", "ui_component", "Embed SarahOfflineTripMap custom element or adapt it to React/Lovable");
  add("tavily", "source_refresh", `${destination} ${topInterests.join(" ")} current travel events opening hours`.trim(), {
    offlineFields: ["title", "summary", "sourceUrl", "checkedAt", "expiresAt"]
  });
  add("aeroxplorer", "aviation_context", `${destination} airport airline aviation context`.trim(), {
    offlineFields: ["title", "summary", "sourceUrl", "checkedAt"]
  });
  return plan;
}

export function calculateOfflineReadiness(bundle) {
  const anchors = bundle?.anchors || [];
  const checks = [
    check("trip_dates", Boolean(bundle?.startDate && bundle?.endDate), "Trip dates"),
    check("map_bounds", Boolean(bundle?.mapPackageRequest?.bounds), "Map area"),
    check("hotel", anchors.some((row) => row.kind === "hotel"), "Hotel details"),
    check("flight_or_transport", anchors.some((row) => ["flight", "airport", "transport"].includes(row.kind)), "Arrival/transport details"),
    check("recommendations", (bundle?.recommendations || []).length > 0, "Personalized recommendations"),
    check("notes", (bundle?.personalNotes || []).length > 0, "Personal travel notes", true),
    check("freshness", !bundle?.freshness?.hasExpiredItems, "Freshness review")
  ];
  const required = checks.filter((row) => !row.optional);
  const completed = required.filter((row) => row.ready).length;
  return {
    score: required.length ? Math.round((completed / required.length) * 100) : 0,
    state: completed === required.length ? "ready" : completed >= Math.ceil(required.length * 0.7) ? "mostly_ready" : "needs_attention",
    checks
  };
}

export function addPersonalNote(bundle, note = {}) {
  if (!bundle || bundle.schema !== OFFLINE_TRIP_MAP_SCHEMA) throw new Error("A valid offline trip bundle is required");
  const anchor = noteToAnchor(note, bundle.personScopeId, (bundle.personalNotes || []).length);
  const next = structuredCloneSafe(bundle);
  next.personalNotes = [...(next.personalNotes || []), anchor];
  next.anchors = [...(next.anchors || []), anchor];
  next.mapPackageRequest = planOfflineRegion(next.anchors, next.mapPackageRequest || {});
  next.readiness = calculateOfflineReadiness(next);
  return next;
}

export function haversineKm(a, b) {
  if (!hasCoordinates(a) || !hasCoordinates(b)) return Number.POSITIVE_INFINITY;
  const radius = 6371;
  const lat1 = toRadians(Number(a.latitude));
  const lat2 = toRadians(Number(b.latitude));
  const dLat = toRadians(Number(b.latitude) - Number(a.latitude));
  const dLng = toRadians(Number(b.longitude) - Number(a.longitude));
  const sinLat = Math.sin(dLat / 2);
  const sinLng = Math.sin(dLng / 2);
  const h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLng * sinLng;
  return radius * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}

function tripFactsToAnchors(facts, includeSensitiveOffline) {
  const out = [];
  let counter = 0;
  for (const fact of facts || []) {
    if (!fact || typeof fact !== "object") continue;
    const locations = Array.isArray(fact.locations) && fact.locations.length ? fact.locations : [fact];
    for (const location of locations) {
      const kind = normalizeKind(location.kind || fact.kind || fact.type || "other");
      const title = clean(location.title || location.name || fact.title || fact.name || kindLabel(kind));
      if (!title) continue;
      counter += 1;
      const sensitive = fact.sensitive === true || location.sensitive === true || ["flight", "hotel", "ticket"].includes(kind);
      out.push({
        id: clean(location.id || fact.id) || `trip-fact-${counter}`,
        kind,
        title,
        subtitle: clean(location.subtitle || fact.subtitle || fact.provider || ""),
        latitude: finiteOrNull(location.latitude ?? location.lat ?? fact.latitude ?? fact.lat),
        longitude: finiteOrNull(location.longitude ?? location.lng ?? location.lon ?? fact.longitude ?? fact.lng ?? fact.lon),
        startTime: clean(location.startTime || location.start_time || fact.startTime || fact.start_time || fact.departureTime || fact.departure_time),
        endTime: clean(location.endTime || location.end_time || fact.endTime || fact.end_time || fact.arrivalTime || fact.arrival_time),
        dayIndex: toNonNegativeInt(location.dayIndex ?? location.day_index ?? fact.dayIndex ?? fact.day_index, 0),
        confirmed: fact.confirmed !== false,
        sensitive,
        offlineBody: clean(location.offlineBody || location.offline_body || fact.offlineBody || fact.offline_body || fact.summary || ""),
        reference: includeSensitiveOffline ? clean(fact.confirmationCode || fact.confirmation_code || fact.reference || "") : "",
        source: clean(fact.source || "traveler-provided"),
        sourceUrl: safeHttps(fact.sourceUrl || fact.source_url || ""),
        tags: uniqueStrings([...(fact.tags || []), kind]),
        checkedAt: clean(fact.checkedAt || fact.checked_at || "")
      });
    }
  }
  return out;
}

function notesToAnchors(notes, personScopeId) {
  return (notes || []).map((note, index) => noteToAnchor(note, personScopeId, index)).filter(Boolean);
}

function noteToAnchor(note, personScopeId, index) {
  const body = clean(typeof note === "string" ? note : note?.body || note?.text || note?.note);
  if (!body) throw new Error("Personal note text is required");
  return {
    id: clean(note?.id) || `personal-note-${index + 1}`,
    kind: "note",
    title: clean(note?.title) || "Personal note",
    subtitle: "Saved by traveler",
    latitude: finiteOrNull(note?.latitude ?? note?.lat),
    longitude: finiteOrNull(note?.longitude ?? note?.lng ?? note?.lon),
    startTime: clean(note?.startTime || note?.start_time || ""),
    endTime: "",
    dayIndex: toNonNegativeInt(note?.dayIndex ?? note?.day_index, 0),
    confirmed: true,
    sensitive: true,
    offlineBody: body,
    reference: "",
    source: "personal-note",
    sourceUrl: "",
    tags: uniqueStrings(note?.tags || []),
    checkedAt: "",
    personScopeId
  };
}

function recommendationToAnchor(place) {
  return {
    id: `place-${place.id}`,
    kind: "recommendation",
    title: place.title,
    subtitle: place.reasons?.[0] || "Personalized suggestion",
    latitude: finiteOrNull(place.latitude ?? place.lat),
    longitude: finiteOrNull(place.longitude ?? place.lng ?? place.lon),
    startTime: "",
    endTime: "",
    dayIndex: toNonNegativeInt(place.dayIndex ?? place.day_index, 0),
    confirmed: false,
    sensitive: false,
    offlineBody: clean(place.offlineSummary || place.offline_summary || place.summary || ""),
    reference: "",
    source: clean(place.source),
    sourceUrl: safeHttps(place.sourceUrl),
    tags: uniqueStrings(place.tags || place.categories || []),
    checkedAt: clean(place.checkedAt),
    recommendation: {
      score: place.score,
      matchedInterests: place.matchedInterests || [],
      reasons: place.reasons || []
    }
  };
}

function buildFreshness(input, recommendations) {
  const nowMs = input.nowMs ?? Date.now();
  const defaultMaxAgeMs = input.defaultMaxAgeMs ?? input.default_max_age_ms ?? 86_400_000;
  const rows = recommendations.map((row) => {
    const checkedMs = Date.parse(row.checkedAt || "");
    const stale = Number.isFinite(checkedMs) ? nowMs - checkedMs > defaultMaxAgeMs : true;
    return { id: row.id, title: row.title, checkedAt: row.checkedAt || "", stale };
  });
  return {
    lastBuiltAt: new Date(nowMs).toISOString(),
    hasExpiredItems: rows.some((row) => row.stale),
    items: rows
  };
}

function nearestDistanceKm(candidate, anchors) {
  if (!hasCoordinates(candidate) || !anchors.length) return Number.POSITIVE_INFINITY;
  let best = Number.POSITIVE_INFINITY;
  for (const anchor of anchors) best = Math.min(best, haversineKm(candidate, anchor));
  return best;
}

function topicMatches(topic, tags = [], ...texts) {
  const needle = clean(topic).toLowerCase();
  if (!needle) return false;
  const haystacks = [...tags, ...texts].map((value) => clean(value).toLowerCase()).filter(Boolean);
  return haystacks.some((value) => value === needle || value.includes(needle) || needle.includes(value));
}

function hasCoordinates(value) {
  return Number.isFinite(Number(value?.latitude ?? value?.lat))
    && Number.isFinite(Number(value?.longitude ?? value?.lng ?? value?.lon));
}

function normalizeKind(value) {
  const kind = clean(value).toLowerCase().replaceAll(" ", "_");
  const allowed = new Set(["flight", "airport", "hotel", "ticket", "transport", "place", "recommendation", "note", "emergency", "other"]);
  return allowed.has(kind) ? kind : "other";
}

function kindLabel(kind) {
  return kind === "flight" ? "Flight" : kind === "hotel" ? "Hotel" : kind === "ticket" ? "Ticket" : kind === "transport" ? "Transport" : "Trip item";
}

function check(id, ready, label, optional = false) {
  return { id, label, ready: Boolean(ready), optional };
}

function compareInterest(a, b) {
  return (b.confidence || 0) - (a.confidence || 0)
    || (b.evidenceCount || 0) - (a.evidenceCount || 0)
    || clean(a.topic).localeCompare(clean(b.topic));
}

function dedupeInterests(interests) {
  const map = new Map();
  for (const row of interests || []) {
    const topic = clean(typeof row === "string" ? row : row?.topic || row?.name);
    if (!topic) continue;
    const key = topic.toLowerCase();
    const confidence = typeof row === "string" ? 0.5 : clampNumber(row.confidence, 0, 1, 0.5);
    const current = map.get(key);
    if (!current) {
      map.set(key, {
        topic,
        confidence: round(confidence),
        evidenceCount: toNonNegativeInt(row?.evidenceCount ?? row?.evidence_count, 1),
        sources: uniqueStrings(row?.sources || [row?.source || row?.origin || "unknown"]),
        origin: clean(row?.origin || "")
      });
    } else {
      current.confidence = round(Math.max(current.confidence, confidence));
      current.evidenceCount += toNonNegativeInt(row?.evidenceCount ?? row?.evidence_count, 1);
      current.sources = uniqueStrings([...current.sources, ...(row?.sources || [row?.source || row?.origin || "unknown"])]);
    }
  }
  return [...map.values()].sort(compareInterest);
}

function firstArray(...values) {
  for (const value of values) if (Array.isArray(value)) return value;
  return [];
}

function uniqueStrings(values) {
  const seen = new Set();
  const out = [];
  for (const value of Array.isArray(values) ? values : [values]) {
    const cleaned = clean(value);
    const key = cleaned.toLowerCase();
    if (!cleaned || seen.has(key)) continue;
    seen.add(key);
    out.push(cleaned);
  }
  return out;
}

function safeHttps(value) {
  const candidate = clean(value);
  if (!candidate) return "";
  try {
    const parsed = new URL(candidate);
    return parsed.protocol === "https:" ? candidate : "";
  } catch {
    return "";
  }
}

function formatDistance(km) {
  if (km < 1) return `${Math.max(1, Math.round(km * 1000))} m`;
  return `${round(km)} km`;
}

function toRadians(value) {
  return value * Math.PI / 180;
}

function toNonNegativeInt(value, fallback = 0) {
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback;
}

function finiteOrNull(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function clampNumber(value, min, max, fallback) {
  const number = Number(value);
  if (!Number.isFinite(number)) return fallback;
  return Math.min(max, Math.max(min, number));
}

function round(value) {
  return Math.round(value * 100) / 100;
}

function clean(value) {
  return value == null ? "" : String(value).trim().replace(/\s+/g, " ");
}

function structuredCloneSafe(value) {
  return typeof structuredClone === "function"
    ? structuredClone(value)
    : JSON.parse(JSON.stringify(value));
}
