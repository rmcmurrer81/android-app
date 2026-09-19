package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure, bounded planner for profile-owned destination and nearby research. */
public final class AdaptiveResearchPlan {
    public static final class Query {
        public final String text;
        public final String category;

        Query(String text, String category) {
            this.text = text;
            this.category = category;
        }
    }

    private AdaptiveResearchPlan() { }

    public static List<Query> build(
            String destination,
            String interests,
            String approximateArea,
            boolean nearbyEnabled) {
        String place = clean(destination);
        String likes = clean(interests);
        String area = nearbyEnabled ? clean(approximateArea) : "";
        String focus = likes.isEmpty() ? "travel, history, food and local culture" : likes;
        List<Query> result = new ArrayList<>();

        boolean powerRangersNewZealand = contains(likes, "power rangers")
                && contains(place, "new zealand");
        if (!place.isEmpty()) {
            String text = powerRangersNewZealand
                    ? "Power Rangers filming locations in New Zealand official tourism and production sources"
                    : focus + " in " + place
                            + " museums filming locations official visitor information";
            result.add(new Query(text, "destination_interest"));
        }
        if (!area.isEmpty() && result.size() < BackgroundResearchPolicy.MAX_PACKS_PER_RUN) {
            result.add(new Query(
                    focus + " events appearances signings exhibitions near " + area
                            + " official event and ticket sources",
                    "nearby_interest"));
        }
        if (powerRangersNewZealand
                && area.isEmpty()
                && result.size() < BackgroundResearchPolicy.MAX_PACKS_PER_RUN) {
            result.add(new Query(
                    "New Zealand current visitor information and events official tourism sources",
                    "destination_current"));
        }
        return result;
    }

    private static boolean contains(String value, String expected) {
        return value.toLowerCase(Locale.US).contains(expected);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
