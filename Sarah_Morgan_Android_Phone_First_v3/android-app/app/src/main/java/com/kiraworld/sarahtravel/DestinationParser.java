package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts one or more travel destinations from natural language. */
public final class DestinationParser {
    private static final Map<String, String[]> KNOWN = new LinkedHashMap<>();
    private static final Pattern AFTER_TRAVEL_VERB = Pattern.compile(
            "(?i)\\b(?:visit|visiting|go to|going to|trip to|travel to|fly to|flights to)\\s+([A-Za-z][A-Za-z .'-]{1,60})");

    static {
        KNOWN.put("Paris", new String[]{"paris"});
        KNOWN.put("London", new String[]{"london"});
        KNOWN.put("New York City", new String[]{"new york city", "new york", "nyc"});
        KNOWN.put("Rome", new String[]{"rome"});
        KNOWN.put("Tokyo", new String[]{"tokyo"});
        KNOWN.put("Washington, D.C.", new String[]{"washington dc", "washington, d.c.", "washington d.c."});
        KNOWN.put("Chicago", new String[]{"chicago"});
        KNOWN.put("Los Angeles", new String[]{"los angeles"});
        KNOWN.put("Boston", new String[]{"boston"});
        KNOWN.put("Salem", new String[]{"salem"});
        KNOWN.put("Charleston", new String[]{"charleston"});
        KNOWN.put("San Francisco", new String[]{"san francisco"});
        KNOWN.put("Las Vegas", new String[]{"las vegas", "vegas"});
        KNOWN.put("Miami", new String[]{"miami"});
        KNOWN.put("Newark", new String[]{"newark"});
    }

    private DestinationParser() { }

    public static List<String> extractDestinations(String text) {
        List<String> result = new ArrayList<>();
        String lower = normalize(text);
        if (lower.isEmpty()) return result;

        for (Map.Entry<String, String[]> entry : KNOWN.entrySet()) {
            for (String alias : entry.getValue()) {
                if (containsWholePhrase(lower, alias)) {
                    addUnique(result, entry.getKey());
                    break;
                }
            }
        }

        if (result.isEmpty()) {
            Matcher matcher = AFTER_TRAVEL_VERB.matcher(text == null ? "" : text);
            while (matcher.find()) {
                String candidate = cleanGenericCandidate(matcher.group(1));
                if (!candidate.isEmpty()) addUnique(result, canonicalize(candidate));
            }
        }
        return result;
    }

    public static List<String> extractFromHistory(List<Map<String, String>> history, int maxMessages) {
        List<String> result = new ArrayList<>();
        int start = Math.max(0, history.size() - Math.max(1, maxMessages));
        for (int i = start; i < history.size(); i++) {
            Map<String, String> row = history.get(i);
            if (!"user".equalsIgnoreCase(row.getOrDefault("role", "user"))) continue;
            for (String destination : extractDestinations(row.getOrDefault("content", ""))) {
                addUnique(result, destination);
            }
        }
        return result;
    }

    public static String join(List<String> destinations) {
        if (destinations == null || destinations.isEmpty()) return "";
        if (destinations.size() == 1) return destinations.get(0);
        if (destinations.size() == 2) return destinations.get(0) + " and " + destinations.get(1);
        return String.join(", ", destinations.subList(0, destinations.size() - 1))
                + ", and " + destinations.get(destinations.size() - 1);
    }

    private static String cleanGenericCandidate(String value) {
        if (value == null) return "";
        String cleaned = value.trim();
        cleaned = cleaned.replaceAll(
                "(?i)\\b(?:or|and|for|from|during|next|this|with|because|but|when|while|if|to see|to visit|looking|just)\\b.*$",
                "").trim();
        cleaned = cleaned.replaceAll("[?.!,]+$", "").trim();
        if (cleaned.length() < 2 || cleaned.length() > 50) return "";
        return cleaned;
    }

    private static String canonicalize(String value) {
        String lower = normalize(value);
        for (Map.Entry<String, String[]> entry : KNOWN.entrySet()) {
            for (String alias : entry.getValue()) {
                if (lower.equals(alias)) return entry.getKey();
            }
        }
        String[] words = value.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) out.append(word.substring(1).toLowerCase(Locale.US));
        }
        return out.toString();
    }

    private static boolean containsWholePhrase(String text, String phrase) {
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(phrase) + "(?![a-z0-9])", Pattern.CASE_INSENSITIVE)
                .matcher(text).find();
    }

    private static void addUnique(List<String> result, String value) {
        if (value == null || value.trim().isEmpty()) return;
        for (String existing : result) {
            if (existing.equalsIgnoreCase(value)) return;
        }
        result.add(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
    }
}
