package com.kiraworld.sarahtravel;

import java.util.List;

/** Pure-Java catalog used by documentation, tests, and hackathon track coverage checks. */
public final class TravelOsFeatureCatalog {
    public static final String AI_TRIP_PLANNING = "ai_trip_planning";
    public static final String HOTEL_HOSPITALITY_OPS = "hotel_hospitality_ops";
    public static final String LOCAL_EXPERIENCES = "local_experiences";
    public static final String SUSTAINABILITY_ACCESSIBILITY = "sustainability_accessibility";

    public static final class Feature {
        public final String track;
        public final String id;
        public final String title;

        Feature(String track, String id, String title) {
            this.track = track;
            this.id = id;
            this.title = title;
        }
    }

    private static final List<Feature> FEATURES = List.of(
            new Feature(AI_TRIP_PLANNING, "conversation_planning", "Conversational trip planning"),
            new Feature(AI_TRIP_PLANNING, "itinerary", "Editable itinerary"),
            new Feature(AI_TRIP_PLANNING, "budget", "Trip budget"),
            new Feature(AI_TRIP_PLANNING, "packing", "Packing and preparation"),
            new Feature(AI_TRIP_PLANNING, "hotel_search", "Hotel comparison"),
            new Feature(AI_TRIP_PLANNING, "multimodal_transport", "Air rail bus transit and driving"),
            new Feature(AI_TRIP_PLANNING, "loyalty", "Loyalty-aware value"),
            new Feature(HOTEL_HOSPITALITY_OPS, "stay_assistant", "Guest stay assistant"),
            new Feature(HOTEL_HOSPITALITY_OPS, "voice_concierge", "Supervised voice concierge"),
            new Feature(HOTEL_HOSPITALITY_OPS, "front_desk", "Front desk task routing"),
            new Feature(HOTEL_HOSPITALITY_OPS, "housekeeping", "Housekeeping task routing"),
            new Feature(HOTEL_HOSPITALITY_OPS, "maintenance", "Maintenance task routing"),
            new Feature(HOTEL_HOSPITALITY_OPS, "revenue", "Transparent relevant hotel offers"),
            new Feature(LOCAL_EXPERIENCES, "food", "Food discovery"),
            new Feature(LOCAL_EXPERIENCES, "events", "Current event discovery"),
            new Feature(LOCAL_EXPERIENCES, "attractions", "Attractions museums and history"),
            new Feature(LOCAL_EXPERIENCES, "media", "Maps public photos and videos"),
            new Feature(LOCAL_EXPERIENCES, "rides", "Uber Lyft taxi and transit handoff"),
            new Feature(LOCAL_EXPERIENCES, "roadside", "Roadside and scenic discovery"),
            new Feature(SUSTAINABILITY_ACCESSIBILITY, "active_profile_needs", "Per-person accessibility and pace"),
            new Feature(SUSTAINABILITY_ACCESSIBILITY, "rail_first", "Rail and transit comparison"),
            new Feature(SUSTAINABILITY_ACCESSIBILITY, "ev", "EV charging support"),
            new Feature(SUSTAINABILITY_ACCESSIBILITY, "walking_biking", "Walking and biking options"),
            new Feature(SUSTAINABILITY_ACCESSIBILITY, "sensory", "Sensory-aware planning"),
            new Feature(SUSTAINABILITY_ACCESSIBILITY, "complete_trip", "Door-to-door tradeoff comparison")
    );

    private TravelOsFeatureCatalog() { }

    public static List<Feature> all() {
        return FEATURES;
    }

    public static boolean has(String track, String featureId) {
        for (Feature feature : FEATURES) {
            if (feature.track.equals(track) && feature.id.equals(featureId)) return true;
        }
        return false;
    }

    public static int countTrack(String track) {
        int count = 0;
        for (Feature feature : FEATURES) if (feature.track.equals(track)) count++;
        return count;
    }
}
