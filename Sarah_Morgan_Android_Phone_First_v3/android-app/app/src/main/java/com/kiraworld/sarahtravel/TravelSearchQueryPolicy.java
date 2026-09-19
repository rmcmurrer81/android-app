package com.kiraworld.sarahtravel;

import java.util.List;
import java.util.Map;

/** Builds bounded, explicit search context without sending Sarah's full prompt. */
public final class TravelSearchQueryPolicy {
    private static final int MAX_QUERY_CHARS = 1_200;

    private TravelSearchQueryPolicy() { }

    public static String build(
            String message,
            List<Map<String, String>> history,
            Map<String, String> profile,
            List<Map<String, String>> trips) {
        StringBuilder out = new StringBuilder("Current request: ").append(clean(message));
        String area = profile == null ? "" : clean(profile.get("runtime_current_area"));
        if (!area.isEmpty() && CurrentLocationPolicy.asksForCurrentArea(message)) {
            String source = profile == null
                    ? CurrentLocationPolicy.SOURCE_UNKNOWN
                    : clean(profile.get("runtime_current_area_source"));
            if (CurrentLocationPolicy.SOURCE_DEVICE_RESOLVED.equals(source)) {
                out.append(". Device-resolved approximate current area: ").append(area);
            } else if (CurrentLocationPolicy.SOURCE_MANUAL.equals(source)) {
                out.append(". Owner-entered area for nearby search: ").append(area);
            } else {
                out.append(". Saved approximate area with unrecorded source: ").append(area);
            }
        }

        int added = 0;
        if (history != null) {
            for (int index = history.size() - 1; index >= 0 && added < 3; index--) {
                Map<String, String> row = history.get(index);
                if (!"user".equals(row.getOrDefault("role", ""))) continue;
                String prior = clean(row.get("content"));
                if (prior.isEmpty() || prior.equals(clean(message))) continue;
                out.append(". Recent user context: ").append(prior);
                added++;
            }
        }

        if (trips != null) {
            int destinations = 0;
            for (Map<String, String> trip : trips) {
                String destination = clean(trip.get("destination"));
                if (destination.isEmpty()) continue;
                out.append(". Confirmed trip destination: ").append(destination);
                if (++destinations >= 2) break;
            }
        }
        String result = out.toString().replaceAll("\\s+", " ").trim();
        return result.length() <= MAX_QUERY_CHARS ? result : result.substring(0, MAX_QUERY_CHARS);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
