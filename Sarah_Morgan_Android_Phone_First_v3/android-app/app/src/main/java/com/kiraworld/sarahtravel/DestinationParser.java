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
            "(?i)\\b(?:visit|visiting|go to|going to|trip to|travel to|fly to|flights to|ride to|head to|heading to|take a train to|take the train to|planning on going to|thinking about going to)\\s+([A-Za-z][A-Za-z .,'-]{1,80})");

    static {
        KNOWN.put("Paris, Texas", new String[]{"paris texas", "paris, texas", "paris tx", "paris, tx"});
        KNOWN.put("Paris", new String[]{"paris france", "paris, france", "paris"});
        KNOWN.put("London", new String[]{"london"});
        KNOWN.put("New York City", new String[]{"new york city", "new york", "nyc", "manhattan"});
        KNOWN.put("California", new String[]{"california"});
        KNOWN.put("San Diego", new String[]{"san diego"});
        KNOWN.put("Los Angeles", new String[]{"los angeles", "la"});
        KNOWN.put("San Francisco", new String[]{"san francisco"});
        KNOWN.put("Sacramento", new String[]{"sacramento"});
        KNOWN.put("Anaheim", new String[]{"anaheim"});
        KNOWN.put("Las Vegas", new String[]{"las vegas", "vegas"});
        KNOWN.put("Austin", new String[]{"austin"});
        KNOWN.put("San Antonio", new String[]{"san antonio"});
        KNOWN.put("Seattle", new String[]{"seattle"});
        KNOWN.put("Portland", new String[]{"portland"});
        KNOWN.put("Denver", new String[]{"denver"});
        KNOWN.put("Philadelphia", new String[]{"philadelphia", "philly"});
        KNOWN.put("New Orleans", new String[]{"new orleans"});
        KNOWN.put("Rome", new String[]{"rome"});
        KNOWN.put("Tokyo", new String[]{"tokyo"});
        KNOWN.put("Washington, D.C.", new String[]{"washington dc", "washington, d.c.", "washington d.c."});
        KNOWN.put("Chicago", new String[]{"chicago"});
        KNOWN.put("Boston", new String[]{"boston"});
        KNOWN.put("Salem", new String[]{"salem"});
        KNOWN.put("Charleston", new String[]{"charleston"});
        KNOWN.put("Miami", new String[]{"miami"});
        KNOWN.put("Newark", new String[]{"newark"});
        KNOWN.put("Orlando", new String[]{"orlando"});
        KNOWN.put("China", new String[]{"china"});
        KNOWN.put("Beijing", new String[]{"beijing"});
        KNOWN.put("Shanghai", new String[]{"shanghai"});
        KNOWN.put("Hong Kong", new String[]{"hong kong"});
    }

    private DestinationParser() { }

    public static List<String> extractDestinations(String text) {
        List<String> result = new ArrayList<>();
        String lower = normalize(text);
        if (lower.isEmpty()) return result;
        boolean parisTexasNamed = containsParisTexas(lower);

        for (Map.Entry<String, String[]> entry : KNOWN.entrySet()) {
            if (parisTexasNamed && "Paris".equals(entry.getKey())) continue;
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
                if (!candidate.isEmpty()) addUnique(result, canonicalizePlace(candidate));
            }
        }
        return result;
    }

    /** Returns only the most recent relevant user turn rather than every old place. */
    public static List<String> extractFromHistory(List<Map<String, String>> history, int maxMessages) {
        int userMessagesSeen = 0;
        for (int i = history.size() - 1; i >= 0 && userMessagesSeen < Math.max(1, maxMessages); i--) {
            Map<String, String> row = history.get(i);
            if (!"user".equalsIgnoreCase(row.getOrDefault("role", "user"))) continue;
            userMessagesSeen++;
            List<String> found = extractDestinations(row.getOrDefault("content", ""));
            if (!found.isEmpty()) return found;
        }
        return List.of();
    }

    public static String join(List<String> destinations) {
        if (destinations == null || destinations.isEmpty()) return "";
        if (destinations.size() == 1) return destinations.get(0);
        if (destinations.size() == 2) return destinations.get(0) + " and " + destinations.get(1);
        return String.join(", ", destinations.subList(0, destinations.size() - 1))
                + ", and " + destinations.get(destinations.size() - 1);
    }

    public static String canonicalizePlace(String value) {
        if (value == null) return "";
        String cleaned = cleanGenericCandidate(value);
        if (cleaned.isEmpty()) return "";
        String lower = normalize(cleaned);
        for (Map.Entry<String, String[]> entry : KNOWN.entrySet()) {
            for (String alias : entry.getValue()) {
                if (lower.equals(alias)) return entry.getKey();
            }
        }
        String[] words = cleaned.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            if (word.length() <= 3 && word.equals(word.toUpperCase(Locale.US))) out.append(word);
            else {
                out.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) out.append(word.substring(1).toLowerCase(Locale.US));
            }
        }
        return out.toString();
    }

    private static String cleanGenericCandidate(String value) {
        if (value == null) return "";
        String cleaned = value.trim();
        cleaned = cleaned.replaceAll(
                "(?i)\\b(?:or|and|for|from|during|next|this|with|because|but|when|while|if|to see|to visit|looking|just|by|using|on)\\b.*$",
                "").trim();
        cleaned = cleaned.replaceAll("[?.!,]+$", "").trim();
        if (cleaned.length() < 2 || cleaned.length() > 50) return "";
        return cleaned;
    }

    private static boolean containsParisTexas(String text) {
        return containsWholePhrase(text, "paris texas")
                || containsWholePhrase(text, "paris, texas")
                || containsWholePhrase(text, "paris tx")
                || containsWholePhrase(text, "paris, tx");
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
