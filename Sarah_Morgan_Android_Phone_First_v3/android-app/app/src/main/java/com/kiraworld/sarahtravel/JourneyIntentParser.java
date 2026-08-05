package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses origin, destination, event purpose, and transport methods. */
public final class JourneyIntentParser {
    public static final String AIR = "air";
    public static final String RAIL = "rail";
    public static final String TRANSIT = "local_transit";
    public static final String BUS = "intercity_bus";
    public static final String DRIVE = "drive";
    public static final String FERRY = "ferry";
    public static final String BIKE = "bike";
    public static final String WALK = "walk";

    public static final class JourneyIntent {
        public final String origin;
        public final String destination;
        public final String eventName;
        public final List<String> modes;
        public final boolean monitorRequested;
        public final boolean crossCountry;

        JourneyIntent(
                String origin,
                String destination,
                String eventName,
                List<String> modes,
                boolean monitorRequested,
                boolean crossCountry) {
            this.origin = clean(origin);
            this.destination = clean(destination);
            this.eventName = clean(eventName);
            this.modes = modes == null ? List.of() : List.copyOf(modes);
            this.monitorRequested = monitorRequested;
            this.crossCountry = crossCountry;
        }

        public boolean found() {
            return !destination.isEmpty() && !modes.isEmpty();
        }

        public String modeCsv() {
            return String.join(",", modes);
        }
    }

    private static final Pattern FROM_TO = Pattern.compile(
            "(?i)\\bfrom\\s+([A-Za-z][A-Za-z .,'-]{1,60}?)\\s+to\\s+([A-Za-z][A-Za-z .,'-]{1,60})(?:[.!?]|$|\\s+(?:by|using|on|for|with))");
    private static final Pattern TO_FROM = Pattern.compile(
            "(?i)\\bto\\s+([A-Za-z][A-Za-z .,'-]{1,60}?)\\s+from\\s+([A-Za-z][A-Za-z .,'-]{1,60})(?:[.!?]|$|\\s+(?:by|using|on|for|with))");

    private JourneyIntentParser() { }

    public static JourneyIntent parse(
            String message,
            Map<String, String> profile,
            List<Map<String, String>> history) {
        String safe = message == null ? "" : message.trim();
        String lower = safe.toLowerCase(Locale.US);
        List<String> modes = detectModes(lower);
        boolean monitor = containsAny(lower,
                "monitor", "notify me", "alert me", "watch", "track", "deal",
                "cheapest", "price", "fare", "service change", "delay");
        boolean crossCountry = containsAny(lower, "cross country", "cross-country", "coast to coast");

        EventTripIntentParser.EventIntent event = EventTripIntentParser.parse(safe);
        String origin = profile == null ? "" : profile.getOrDefault("hometown", "");
        String destination = event.found() ? event.destination : "";
        String eventName = event.found() ? event.eventName : "";

        Matcher fromTo = FROM_TO.matcher(safe);
        if (fromTo.find()) {
            origin = canonical(fromTo.group(1));
            destination = canonical(fromTo.group(2));
        } else {
            Matcher toFrom = TO_FROM.matcher(safe);
            if (toFrom.find()) {
                destination = canonical(toFrom.group(1));
                origin = canonical(toFrom.group(2));
            }
        }

        List<String> current = DestinationParser.extractDestinations(safe);
        if (destination.isEmpty()) {
            if (current.size() >= 2 && lower.contains("from ") && lower.contains(" to ")) {
                origin = current.get(0);
                destination = current.get(current.size() - 1);
            } else if (!current.isEmpty()) {
                destination = current.get(current.size() - 1);
            }
        }

        if (destination.isEmpty() && !modes.isEmpty()) {
            List<String> context = TravelContextResolver.resolveDestinations(safe, history);
            if (!context.isEmpty()) destination = context.get(context.size() - 1);
        }

        if (modes.isEmpty() && event.found() && containsAny(lower, "get there", "getting there", "transport", "travel to")) {
            modes.add(TRANSIT);
        }

        if (modes.isEmpty() && monitor && !destination.isEmpty()) {
            addMode(modes, AIR);
            addMode(modes, RAIL);
            addMode(modes, BUS);
        }

        if (origin == null || origin.trim().isEmpty()) origin = "Home area";
        return new JourneyIntent(origin, destination, eventName, modes, monitor, crossCountry);
    }

    private static List<String> detectModes(String lower) {
        List<String> modes = new ArrayList<>();
        if (containsAny(lower, "flight", "fly", "flying", "airfare", "airline", "plane")) addMode(modes, AIR);
        if (containsAny(lower, "amtrak", "train", "rail", "sleeper car", "coach seat")) addMode(modes, RAIL);
        if (containsAny(lower, "metro", "subway", "local transit", "public transit", "path train", "nj transit", "light rail")) addMode(modes, TRANSIT);
        if (containsAny(lower, "greyhound", "flixbus", "megabus", "intercity bus", "bus trip", "by bus")) addMode(modes, BUS);
        if (containsAny(lower, "drive", "driving", "road trip", "rental car", "by car")) addMode(modes, DRIVE);
        if (lower.contains("ferry")) addMode(modes, FERRY);
        if (containsAny(lower, "bike", "bicycle", "cycling")) addMode(modes, BIKE);
        if (containsAny(lower, "walk", "walking")) addMode(modes, WALK);
        return modes;
    }

    private static String canonical(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replaceAll(
                "(?i)\\b(?:for|by|using|on|with|during|next|this)\\b.*$", "").trim();
        return DestinationParser.canonicalizePlace(value);
    }

    private static void addMode(List<String> modes, String mode) {
        if (!modes.contains(mode)) modes.add(mode);
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
