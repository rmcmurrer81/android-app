package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves the destination context for one turn without combining every saved
 * wish, old trip, and earlier conversation into a single unrelated topic.
 */
public final class TravelContextResolver {
    private TravelContextResolver() { }

    public static List<String> resolveDestinations(
            String message,
            List<Map<String, String>> history) {
        String safe = message == null ? "" : message.trim();
        String lower = safe.toLowerCase(Locale.US);

        if (clearsTravelContext(lower)) return List.of();

        List<String> current = DestinationParser.extractDestinations(safe);
        if (!current.isEmpty()) return unique(current);

        int userMessagesSeen = 0;
        for (int i = history.size() - 1; i >= 0 && userMessagesSeen < 6; i--) {
            Map<String, String> row = history.get(i);
            if (!"user".equalsIgnoreCase(row.getOrDefault("role", "user"))) continue;
            String content = row.getOrDefault("content", "").trim();
            if (content.equals(safe)) continue;
            userMessagesSeen++;
            String priorLower = content.toLowerCase(Locale.US);
            if (clearsTravelContext(priorLower) || startsNewTopic(priorLower)) break;
            List<String> found = DestinationParser.extractDestinations(content);
            if (!found.isEmpty()) return unique(found);
        }
        return List.of();
    }

    public static String primaryDestination(
            String message,
            List<Map<String, String>> history) {
        List<String> destinations = resolveDestinations(message, history);
        return destinations.isEmpty() ? "" : destinations.get(destinations.size() - 1);
    }

    public static boolean clearsTravelContext(String lower) {
        if (lower == null) return false;
        String safe = lower.toLowerCase(Locale.US).trim();
        if (safe.matches("^(i don'?t know( yet)?|not sure( yet)?|nothing yet|no destination yet|undecided|i have no idea)[.! ]*$")) {
            return true;
        }
        if (safe.contains(" but ") || safe.contains(" instead ")) return false;
        return safe.matches("^(?:i am|i'm|im|we are|we're) not (?:going|traveling|travelling|flying|driving)(?: to)? .+[.! ]*$")
                || safe.matches("^not (?:going|traveling|travelling|flying|driving)(?: to)? (?:there|.+)[.! ]*$")
                || safe.matches("^(?:cancel|forget|drop|clear) (?:(?:that|this|the) )?(?:trip|destination|travel plan|travel context)[.! ]*$");
    }

    private static boolean startsNewTopic(String lower) {
        return lower.startsWith("new topic")
                || lower.startsWith("something else")
                || lower.startsWith("change the subject")
                || lower.startsWith("forget that trip");
    }

    private static List<String> unique(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            boolean exists = false;
            for (String saved : result) {
                if (saved.equalsIgnoreCase(value)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) result.add(value);
        }
        return result;
    }
}
