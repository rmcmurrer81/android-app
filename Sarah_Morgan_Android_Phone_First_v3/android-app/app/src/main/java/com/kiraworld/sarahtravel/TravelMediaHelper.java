package com.kiraworld.sarahtravel;

import java.util.List;
import java.util.Map;

/** Resolves the place or route used by Sarah's in-app visual travel tools. */
public final class TravelMediaHelper {
    public static final class Tools {
        public final String query;
        public final String origin;
        public final String destination;
        public final String mode;
        public final boolean available;

        Tools(String query, String origin, String destination, String mode, boolean available) {
            this.query = value(query);
            this.origin = value(origin);
            this.destination = value(destination);
            this.mode = value(mode);
            this.available = available;
        }
    }

    private TravelMediaHelper() { }

    public static Tools resolve(
            String message,
            Map<String, String> profile,
            List<Map<String, String>> history) {
        if (TravelContextResolver.clearsTravelContext(
                message == null ? "" : message.toLowerCase())) {
            return new Tools("", "", "", "", false);
        }

        EventTripIntentParser.EventIntent event = EventTripIntentParser.parse(message);
        JourneyIntentParser.JourneyIntent journey = JourneyIntentParser.parse(message, profile, history);
        if (event.found()) {
            String mode = journey.modes.isEmpty() ? JourneyIntentParser.TRANSIT : journey.modes.get(0);
            String origin = journey.origin.isEmpty() ? profile.getOrDefault("hometown", "") : journey.origin;
            return new Tools(
                    event.eventName + " " + event.destination,
                    origin,
                    event.destination,
                    mode,
                    true);
        }
        if (journey.found()) {
            return new Tools(
                    journey.destination,
                    journey.origin,
                    journey.destination,
                    journey.modes.get(0),
                    true);
        }

        List<String> destinations = TravelContextResolver.resolveDestinations(message, history);
        if (destinations.isEmpty()) return new Tools("", "", "", "", false);
        String destination = destinations.get(destinations.size() - 1);
        return new Tools(
                destination,
                profile.getOrDefault("hometown", ""),
                destination,
                "",
                true);
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
