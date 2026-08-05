package com.kiraworld.sarahtravel;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads generated destination packs without needing a model connection. */
public final class DestinationPackResponder {
    private DestinationPackResponder() { }

    public static String answer(
            String message,
            List<Map<String, String>> history,
            List<Map<String, String>> packs) {
        if (packs == null || packs.isEmpty()) return null;
        String safe = message == null ? "" : message.trim();
        String lower = safe.toLowerCase(Locale.US);
        List<String> destinations = DestinationParser.extractDestinations(safe);
        if (destinations.isEmpty()) destinations = DestinationParser.extractFromHistory(history, 12);
        if (destinations.isEmpty()) return null;
        Map<String, String> pack = find(packs, destinations.get(0));
        if (pack == null || !"ready".equalsIgnoreCase(pack.getOrDefault("status", ""))) return null;

        if (containsAny(lower, "event", "festival", "concert", "what is happening", "what's happening")) {
            return join(
                    pack.getOrDefault("events", ""),
                    prefix("Seasonal context: ", pack.getOrDefault("seasonal", "")),
                    pack.getOrDefault("source_note", ""));
        }
        if (containsAny(lower, "transport", "getting around", "train", "bus", "subway", "metro", "airport")) {
            return join(
                    pack.getOrDefault("transport", ""),
                    prefix("Accessibility and sensory notes: ", pack.getOrDefault("accessibility", "")));
        }
        if (containsAny(lower, "accessible", "accessibility", "sensory", "wheelchair", "stairs", "walking")) {
            return join(
                    pack.getOrDefault("accessibility", ""),
                    prefix("Transport context: ", pack.getOrDefault("transport", "")));
        }
        if (containsAny(lower, "things to do", "places", "recommend", "what should i see", "tell me about", "planning")) {
            return join(
                    pack.getOrDefault("overview", ""),
                    prefix("Good starting points: ", pack.getOrDefault("recommendations", "")),
                    prefix("Seasonal context: ", pack.getOrDefault("seasonal", "")));
        }
        return null;
    }

    private static Map<String, String> find(List<Map<String, String>> packs, String destination) {
        for (Map<String, String> pack : packs) {
            if (destination.equalsIgnoreCase(pack.getOrDefault("destination", ""))) return pack;
        }
        return null;
    }

    private static String prefix(String prefix, String value) {
        return value == null || value.trim().isEmpty() ? "" : prefix + value.trim();
    }

    private static String join(String... parts) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            if (text.length() > 0) text.append("\n\n");
            text.append(part.trim());
        }
        return text.length() == 0 ? null : text.toString();
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }
}
