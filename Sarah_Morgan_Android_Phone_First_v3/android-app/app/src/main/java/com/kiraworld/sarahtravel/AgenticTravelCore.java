package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handles high-level travel intent before the older fallback rules run.
 * The goal is to do useful work with sensible defaults instead of asking a
 * chain of questions.
 */
public final class AgenticTravelCore {
    private AgenticTravelCore() { }

    public static String answer(
            String message,
            Map<String, String> profile,
            List<Map<String, String>> history,
            List<Map<String, String>> memories,
            List<Map<String, String>> wishes,
            List<Map<String, String>> knowledgePacks,
            List<Map<String, String>> dealWatches) {

        String safe = message == null ? "" : message.trim();
        String lower = safe.toLowerCase(Locale.US);
        List<String> destinations = merge(
                DestinationParser.extractDestinations(safe),
                DestinationParser.extractFromHistory(history, 12),
                destinationsFromWishes(wishes));
        removeHome(destinations, profile);
        String destination = destinations.isEmpty() ? "" : destinations.get(0);
        String focus = focusFrom(safe, history);

        if (isNoMoreDetail(lower)) {
            if (!destination.isEmpty() && !focus.isEmpty()) {
                return "Got it. I’ll keep " + destination + " as the destination and " + focus
                        + " as the main reason for the trip. I won’t keep questioning you; I’ll build around that and only ask when a missing fact would change a booking or safety decision.";
            }
            if (!destination.isEmpty()) {
                return "Got it. I’ll keep " + destination
                        + " as the plan and stop asking follow-up questions unless something would materially change the trip.";
            }
            return "Okay. I won’t keep pushing for more detail.";
        }

        if (isNoPreference(lower) && hasDealContext(history)) {
            return "I’ll treat the dates as flexible and search broadly instead of asking for a travel window. "
                    + "My default watch will compare round trips for one traveler, roughly 3–14 nights, nearby airports, and carry-on travel unless you tell me otherwise.";
        }

        if (mentionsUniversal(lower) && (destination.isEmpty() || destination.equalsIgnoreCase("Orlando"))) {
            return "Universal is enough to shape the Orlando trip. I’d treat it as the priority, allow about two or three park days, leave one lower-pressure day for CityWalk, the hotel, or rest, and compare on-site benefits against cheaper off-site lodging and transportation. I can build the rest of the Orlando knowledge pack around that without making you answer a long questionnaire.";
        }

        if (isPlanningStatement(lower) && !destination.isEmpty()) {
            String pack = packSummary(destination, knowledgePacks);
            if (!pack.isEmpty()) return pack;
            return builtInPlan(destination);
        }

        if (asksForDealWatch(lower)) {
            String route = route(profile, destinations);
            boolean active = hasWatch(dealWatches, destination);
            if (active) {
                return "I already have a deal watch for " + route
                        + ". I’ll use flexible date ranges, several trip lengths, and nearby airports. When the connected fare service finds a meaningful option, the notification can include the departure and return dates, total price, airports, baggage assumptions, and weather context.";
            }
            return "I’ll save a broad deal watch for " + route
                    + " instead of asking a string of questions. The default is round-trip, one traveler, flexible dates, 3–14 nights, carry-on travel, and nearby airports. A real price notification requires the connected fare service; until that is configured, the watch stays queued rather than pretending prices are being monitored.";
        }

        if (!destination.isEmpty() && asksPlacesOrEvents(lower)) {
            String pack = packSummary(destination, knowledgePacks);
            if (!pack.isEmpty()) return pack;
            return builtInPlan(destination);
        }

        return null;
    }

    private static String builtInPlan(String destination) {
        if (destination.equalsIgnoreCase("Orlando")) {
            return "For Orlando, I’d organize the trip around the main attraction instead of asking you to define everything first. Universal can support two or three park days, with a lighter day for CityWalk, rest, or the hotel. I’d compare on-site hotel benefits, off-site savings, airport transfers, heat and storm season, and current park events when Smart mode is available.";
        }
        if (destination.equalsIgnoreCase("Austin")) {
            return "For Austin, I’d build a starter pack around live music, Texas history, food, neighborhoods, outdoor spaces, transportation, heat, and whatever events fall inside the likely travel window. A practical first structure is downtown or South Congress, one music-focused evening, one history or museum stop, and a weather-aware outdoor backup. Smart mode can refresh current festivals, venue schedules, and closures automatically.";
        }
        if (destination.equalsIgnoreCase("China")) {
            return "China is too large to treat as one city, so I would create a country-level pack first: entry requirements that need official verification, major gateway airports, rail travel, weather regions, payment and connectivity preparation, and possible city clusters such as Beijing, Shanghai, Xi’an, Chengdu, or Hong Kong. I can begin a flexible deal watch across several gateways without making you choose a city immediately.";
        }
        return "I’ve saved " + destination
                + " as a destination to research. When Smart mode is connected, I’ll build a current knowledge pack with recommended places, transportation, accessibility and sensory notes, seasonal conditions, and upcoming events. I won’t make you answer a long form first.";
    }

    private static String packSummary(String destination, List<Map<String, String>> packs) {
        for (Map<String, String> pack : packs) {
            if (!destination.equalsIgnoreCase(pack.getOrDefault("destination", ""))) continue;
            String overview = pack.getOrDefault("overview", "").trim();
            String recommendations = pack.getOrDefault("recommendations", "").trim();
            String events = pack.getOrDefault("events", "").trim();
            StringBuilder text = new StringBuilder();
            if (!overview.isEmpty()) text.append(overview);
            if (!recommendations.isEmpty()) {
                if (text.length() > 0) text.append("\n\n");
                text.append("Good starting points: ").append(recommendations);
            }
            if (!events.isEmpty()) {
                if (text.length() > 0) text.append("\n\n");
                text.append("Current or upcoming events to verify: ").append(events);
            }
            return text.toString();
        }
        return "";
    }

    private static String focusFrom(String current, List<Map<String, String>> history) {
        String lower = current.toLowerCase(Locale.US);
        if (mentionsUniversal(lower)) return "Universal Studios";
        for (int i = history.size() - 1; i >= 0; i--) {
            String content = history.get(i).getOrDefault("content", "").toLowerCase(Locale.US);
            if (mentionsUniversal(content)) return "Universal Studios";
        }
        return "";
    }

    private static boolean mentionsUniversal(String lower) {
        return lower.contains("universal studios") || lower.contains("universal orlando");
    }

    private static boolean isNoMoreDetail(String lower) {
        return lower.matches("^(that is it|that's it|that’s it|nothing|no more|just that)[.! ]*$");
    }

    private static boolean isNoPreference(String lower) {
        return lower.matches("^(i don'?t care|i do not care|anything|anytime|whenever|no preference)[.! ]*$");
    }

    private static boolean isPlanningStatement(String lower) {
        return containsAny(lower,
                "thinking about going", "planning on going", "planning a trip",
                "always wanted to visit", "want to visit", "would love to travel", "going to ");
    }

    private static boolean asksForDealWatch(String lower) {
        return containsAny(lower,
                "watch for deals", "notify me about deals", "alert me about deals",
                "deal watch", "price alert", "track prices", "look for deals");
    }

    private static boolean asksPlacesOrEvents(String lower) {
        return containsAny(lower,
                "places to recommend", "places do you recommend", "what should i do",
                "things to do", "events", "knowledge pack", "tell me about");
    }

    private static boolean hasDealContext(List<Map<String, String>> history) {
        for (int i = Math.max(0, history.size() - 8); i < history.size(); i++) {
            String content = history.get(i).getOrDefault("content", "").toLowerCase(Locale.US);
            if (content.contains("deal") || content.contains("fare") || content.contains("price")) return true;
        }
        return false;
    }

    private static boolean hasWatch(List<Map<String, String>> watches, String destination) {
        if (destination.isEmpty()) return false;
        for (Map<String, String> watch : watches) {
            if (destination.equalsIgnoreCase(watch.getOrDefault("destination", ""))
                    && "1".equals(watch.getOrDefault("active", "1"))) return true;
        }
        return false;
    }

    private static String route(Map<String, String> profile, List<String> destinations) {
        String home = profile.getOrDefault("hometown", "your home airport");
        return home + " to " + (destinations.isEmpty() ? "the destination" : DestinationParser.join(destinations));
    }

    private static List<String> destinationsFromWishes(List<Map<String, String>> wishes) {
        List<String> result = new ArrayList<>();
        for (Map<String, String> wish : wishes) {
            result.addAll(DestinationParser.extractDestinations(wish.getOrDefault("destination", "")));
        }
        return result;
    }

    @SafeVarargs
    private static List<String> merge(List<String>... sources) {
        List<String> result = new ArrayList<>();
        for (List<String> source : sources) {
            for (String destination : source) {
                boolean exists = false;
                for (String saved : result) {
                    if (saved.equalsIgnoreCase(destination)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) result.add(destination);
            }
        }
        return result;
    }

    private static void removeHome(List<String> destinations, Map<String, String> profile) {
        String home = profile.getOrDefault("hometown", "").toLowerCase(Locale.US);
        destinations.removeIf(destination -> home.contains(destination.toLowerCase(Locale.US)));
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }
}
