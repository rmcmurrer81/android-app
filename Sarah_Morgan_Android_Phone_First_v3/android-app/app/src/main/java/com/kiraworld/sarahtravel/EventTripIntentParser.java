package com.kiraworld.sarahtravel;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts an event and destination from natural travel statements. */
public final class EventTripIntentParser {
    public static final class EventIntent {
        public final String eventName;
        public final String destination;
        public final boolean monitoringRequested;

        EventIntent(String eventName, String destination, boolean monitoringRequested) {
            this.eventName = eventName == null ? "" : eventName.trim();
            this.destination = destination == null ? "" : destination.trim();
            this.monitoringRequested = monitoringRequested;
        }

        public boolean recognized() {
            return !eventName.isEmpty();
        }

        public boolean found() {
            return recognized() && !destination.isEmpty();
        }
    }

    private static final Pattern CITY_FOR_EVENT = Pattern.compile(
            "(?i)\\b(?:going|traveling|travelling|flying|heading|taking (?:the )?(?:train|metro|subway|bus)|planning to go|planning on going)\\s+to\\s+([A-Za-z .'-]{2,50})\\s+for\\s+([^.!?]{2,90})");
    private static final Pattern EVENT_IN_CITY = Pattern.compile(
            "(?i)\\b(?:attending|going to|visiting|taking (?:the )?(?:train|metro|subway|bus) to)\\s+([^.!?]{2,80}?)\\s+(?:in|at)\\s+([A-Za-z .'-]{2,50})(?:[.!?]|$)");

    private EventTripIntentParser() { }

    public static EventIntent parse(String message) {
        String safe = message == null ? "" : message.trim();
        String lower = safe.toLowerCase(Locale.US);
        boolean monitor = containsAny(lower,
                "monitor", "watch for updates", "keep me updated", "new details",
                "notify me", "track the event", "follow the event");

        KnownEventCatalog.Entry known = KnownEventCatalog.find(safe);
        if (known != null) {
            return new EventIntent(known.eventName, known.destination, true);
        }

        if (containsAny(lower, "new york comic con", "nycc")) {
            return new EventIntent("New York Comic Con", "New York City", true);
        }
        if (containsAny(lower, "ces", "consumer electronics show")) {
            return new EventIntent("CES", "Las Vegas", true);
        }
        if (containsAny(lower,
                "san diego comic-con", "san diego comic con", "comic-con international",
                "comic con international", "sdcc")) {
            return new EventIntent("San Diego Comic-Con", "San Diego", true);
        }
        if (lower.contains("comic con") || lower.contains("comic-con")) {
            String city = lower.contains("san diego") ? "San Diego"
                    : lower.contains("new york") ? "New York City"
                    : cityBeforeFor(safe);
            if (!city.isEmpty()) return new EventIntent("Comic-Con", city, true);
        }

        Matcher cityForEvent = CITY_FOR_EVENT.matcher(safe);
        if (cityForEvent.find()) {
            String city = cleanCity(cityForEvent.group(1));
            String event = cleanEvent(cityForEvent.group(2));
            if (!city.isEmpty() && !event.isEmpty()) {
                return new EventIntent(event, city, monitor || looksLikeNamedEvent(event));
            }
        }

        Matcher eventInCity = EVENT_IN_CITY.matcher(safe);
        if (eventInCity.find()) {
            String event = cleanEvent(eventInCity.group(1));
            String city = cleanCity(eventInCity.group(2));
            if (!city.isEmpty() && !event.isEmpty()) {
                return new EventIntent(event, city, monitor || looksLikeNamedEvent(event));
            }
        }

        String unfamiliarEvent = GenericEventReference.extract(safe);
        if (!unfamiliarEvent.isEmpty()) {
            return new EventIntent(unfamiliarEvent, "", true);
        }
        return new EventIntent("", "", false);
    }

    private static String cityBeforeFor(String value) {
        Matcher matcher = CITY_FOR_EVENT.matcher(value == null ? "" : value);
        return matcher.find() ? cleanCity(matcher.group(1)) : "";
    }

    private static String cleanCity(String value) {
        if (value == null) return "";
        String cleaned = value.trim().replaceAll("(?i)\\b(?:the|a|an)\\b", "").trim();
        cleaned = cleaned.replaceAll("[,.!?]+$", "").trim();
        return DestinationParser.canonicalizePlace(cleaned);
    }

    private static String cleanEvent(String value) {
        if (value == null) return "";
        String cleaned = value.trim();
        cleaned = cleaned.replaceAll(
                "(?i)\\b(?:and|but|because|while|then|next|this year|next year|with my|with a)\\b.*$",
                "").trim();
        cleaned = cleaned.replaceAll("[,.!?]+$", "").trim();
        if (cleaned.equalsIgnoreCase("ces")) return "CES";
        if (cleaned.equalsIgnoreCase("nycc")) return "New York Comic Con";
        if (cleaned.equalsIgnoreCase("comic con") || cleaned.equalsIgnoreCase("comic-con")) return "Comic-Con";
        return titleCase(cleaned);
    }

    private static boolean looksLikeNamedEvent(String event) {
        String lower = event.toLowerCase(Locale.US);
        return GenericEventReference.looksLikeEvent(event) || containsAny(lower,
                "conference", "convention", "expo", "festival", "summit", "congress",
                "comic", "show", "meetup", "hackathon", "concert", "championship");
    }

    private static String titleCase(String value) {
        String[] words = value.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            if (word.length() <= 4 && word.equals(word.toUpperCase(Locale.US))) out.append(word);
            else out.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.length() > 1 ? word.substring(1).toLowerCase(Locale.US) : "");
        }
        return out.toString();
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }
}
