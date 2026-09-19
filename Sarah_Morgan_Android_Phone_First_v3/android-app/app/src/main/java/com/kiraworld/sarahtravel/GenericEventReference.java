package com.kiraworld.sarahtravel;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts unfamiliar event names without pretending the event is a city. */
public final class GenericEventReference {
    private static final Pattern ATTEND = Pattern.compile(
            "(?i)\\b(?:going to|attending|visiting|thinking about going to|thinking of going to|planning to go to|planning on going to|want to go to)\\s+([^.!?]{3,120})");

    private GenericEventReference() { }

    public static String extract(String message) {
        String safe = message == null ? "" : message.trim();
        KnownEventCatalog.Entry known = KnownEventCatalog.find(safe);
        if (known != null) return known.eventName;
        Matcher matcher = ATTEND.matcher(safe);
        if (!matcher.find()) return "";
        String candidate = matcher.group(1).trim();
        candidate = candidate.replaceAll(
                "(?i)\\b(?:next week|next month|this weekend|next weekend|tomorrow|with my family|with friends|by train|by bus|by car|by plane)\\b.*$",
                "").trim();
        candidate = candidate.replaceAll("[,.!?:;]+$", "").trim();
        if (!looksLikeEvent(candidate)) return "";
        return titleCase(candidate);
    }

    public static String recentEvent(List<Map<String, String>> history, String currentMessage) {
        String current = extract(currentMessage);
        if (!current.isEmpty()) return current;
        if (!isFollowUp(currentMessage) || history == null) return "";
        int checked = 0;
        for (int i = history.size() - 1; i >= 0 && checked < 16; i--, checked++) {
            Map<String, String> row = history.get(i);
            if (!"user".equalsIgnoreCase(row.getOrDefault("role", "user"))) continue;
            String content = row.getOrDefault("content", "");
            if (content.equals(currentMessage)) continue;
            String event = extract(content);
            if (!event.isEmpty()) return event;
            KnownEventCatalog.Entry known = KnownEventCatalog.find(content);
            if (known != null) return known.eventName;
        }
        return "";
    }

    public static boolean isFollowUp(String message) {
        String lower = normalize(message);
        if (lower.isEmpty()
                || KnownEventCatalog.find(message) != null
                || !extract(message).isEmpty()
                || !DestinationParser.extractDestinations(message).isEmpty()
                || TravelContextResolver.clearsTravelContext(lower)) {
            return false;
        }
        return lower.matches("^(?:and\\s+)?(?:when is it|where is it|what are the dates|what date is it|which dates|how much is it|how much are (?:the )?tickets|who will be there|what(?:'s| is) the venue|what(?:'s| is) the address|what(?:'s| are) the hours|what(?:'s| is) the schedule|where can i park|show me the official page|open the official page|what(?:'s| is) the official (?:site|page))[?.! ]*$")
                || lower.matches(".*\\b(?:for it|for that event|at the event|at that event|near the event|to the event)\\b.*");
    }

    public static boolean looksLikeEvent(String value) {
        String lower = normalize(value);
        return containsAny(lower,
                "comic con", "comicon", "convention", "conference", "expo", "festival",
                "summit", "hackathon", "meetup", "concert", "show", "fair", "parade",
                "championship", "tournament", "premiere", "fan fest", "fan expo",
                "book fair", "film festival", "gaming convention", "anime con",
                "collectors con", "pop culture con");
    }

    private static String titleCase(String value) {
        StringBuilder out = new StringBuilder();
        for (String word : value.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            if (word.length() <= 5 && word.equals(word.toUpperCase(Locale.US))) out.append(word);
            else {
                out.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) out.append(word.substring(1));
            }
        }
        return out.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }
}
