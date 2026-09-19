package com.kiraworld.sarahtravel;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Stable local starter ideas; current details still require live sources. */
public final class CityVisitPlanner {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    private CityVisitPlanner() { }

    public static String answer(
            TripWindowParser.TripWindow trip,
            Map<String, String> profile,
            List<Map<String, String>> memories) {
        if (trip == null || !trip.found()) return null;
        String lower = trip.destination.toLowerCase(Locale.US);
        String interests = combinedInterests(profile, memories).toLowerCase(Locale.US);
        String ageGroup = MaturityAccessPolicy.ageGroup(profile);

        StringBuilder reply = new StringBuilder();
        reply.append("I saved ").append(trip.destination).append(" as a planned ")
                .append(trip.label).append(" trip, roughly ")
                .append(DATE.format(trip.startDate)).append("–").append(DATE.format(trip.endDate))
                .append(". You already gave me enough to start, so I won’t make you fill out a form.\n\n");

        if (lower.contains("new york")) {
            reply.append("Free or inexpensive starting points: Central Park, the High Line, Grand Central Terminal, the New York Public Library area, a neighborhood walk, and the Staten Island Ferry for harbor views.\n\n");
            reply.append("If you have extra money and time: choose one major museum, one observation deck, a Broadway or off-Broadway show, or a Statue of Liberty and Ellis Island visit rather than trying to buy everything at once.\n\n");
            if (interests.contains("history")) {
                reply.append("Because you like history, I would give extra weight to Ellis Island, the Tenement Museum area, Lower Manhattan, and a historical neighborhood walk. ");
            }
            if (containsAny(interests, "movie", "movies", "show", "shows", "television", "comic")) {
                reply.append("Because you like movies or shows, filming-location walks, the Museum of the Moving Image, and event listings may fit you better than a generic landmark checklist. ");
            }
            if (containsAny(interests, "ai", "technology", "computer", "robot")) {
                reply.append("For technology interests, current exhibitions, public talks, maker events, and science museums are useful categories; exact listings require a verified current source. ");
            }
            if (!"adult".equals(ageGroup)) {
                reply.append("This list stays age-appropriate and prioritizes interactive museums, parks, comics, games, and daytime activities. ");
            }
            reply.append("What is actually open during those dates, current weather, timed-entry rules, transit changes, and events next week all require a verified online source for this turn. The media panel can show a map and public photos now.");
            return reply.toString();
        }

        reply.append("A balanced first pass is: one walkable neighborhood, one free public space or viewpoint, one local-history or culture stop, one food area, and one optional paid attraction. That gives you useful choices without assuming a large budget.\n\n");
        if (!interests.isEmpty()) {
            reply.append("I used your saved interests—").append(shorten(interests, 100))
                    .append("—to rank museums, events, stores, tours, food, and entertainment instead of giving everyone the same list. ");
        }
        reply.append("Current events, weather, hours, closures, reservations, and local transportation for those exact dates require a verified online source. The media panel can show the place, public photos, videos, and a route.");
        return reply.toString();
    }

    private static String combinedInterests(
            Map<String, String> profile,
            List<Map<String, String>> memories) {
        StringBuilder out = new StringBuilder(profile.getOrDefault("interests", ""));
        String speakerMemories = profile.getOrDefault("speaker_memories", "");
        if (!speakerMemories.isEmpty()) {
            if (out.length() > 0) out.append("; ");
            out.append(speakerMemories);
        }
        if ("yes".equals(profile.getOrDefault("active_speaker_is_owner", "yes"))) {
            for (Map<String, String> memory : memories) {
                String category = memory.getOrDefault("category", "");
                if (!category.contains("interest") && !category.equals("preference")) continue;
                String summary = memory.getOrDefault("summary", "");
                if (summary.isEmpty()) continue;
                if (out.length() > 0) out.append("; ");
                out.append(summary);
                if (out.length() > 300) break;
            }
        }
        return out.toString().trim();
    }

    private static boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private static String shorten(String value, int maximum) {
        String clean = value.replaceAll("(?i)enjoys\\s+", "").replaceAll("\\s+", " ").trim();
        return clean.length() <= maximum ? clean : clean.substring(0, maximum - 1).trim() + "…";
    }
}
