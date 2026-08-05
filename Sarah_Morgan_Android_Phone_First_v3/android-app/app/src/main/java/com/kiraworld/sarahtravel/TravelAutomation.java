package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Applies durable travel actions inferred from ordinary conversation. */
public final class TravelAutomation {
    public static final class Result {
        public final List<String> destinations;
        public final boolean createdDealWatch;
        public final boolean queuedKnowledge;
        public final boolean learnedFlexibleDates;

        Result(List<String> destinations, boolean createdDealWatch, boolean queuedKnowledge, boolean learnedFlexibleDates) {
            this.destinations = destinations;
            this.createdDealWatch = createdDealWatch;
            this.queuedKnowledge = queuedKnowledge;
            this.learnedFlexibleDates = learnedFlexibleDates;
        }
    }

    private TravelAutomation() { }

    public static Result apply(
            SarahDatabase db,
            Map<String, String> profile,
            String message,
            List<Map<String, String>> history) {

        String safe = message == null ? "" : message.trim();
        String lower = safe.toLowerCase(Locale.US);
        List<String> destinations = merge(
                DestinationParser.extractDestinations(safe),
                DestinationParser.extractFromHistory(history, 12));
        removeHome(destinations, profile);

        boolean planning = containsAny(lower,
                "thinking about going", "thinking of going", "planning on going", "planning to go",
                "planning a trip", "always wanted to visit", "always wanted to go",
                "want to visit", "want to go", "would love to travel", "would love to visit",
                "dream of visiting", "dreamed of visiting", "bucket list", "going to ");
        boolean dealRequest = containsAny(lower,
                "watch for deals", "notify me about deals", "alert me about deals",
                "deal watch", "price alert", "track prices", "look for deals",
                "keep an eye on fares", "keep an eye on prices", "from time to time");
        boolean wishImpliesWatch = containsAny(lower,
                "always wanted to visit", "always wanted to go", "dream of visiting",
                "dreamed of visiting", "bucket list");
        boolean flexible = isNoPreference(lower) && hasDealContext(history)
                || containsAny(lower,
                "flexible dates", "any dates", "any days", "whenever is cheapest",
                "don't care about dates", "do not care about dates", "don't care of dates");

        boolean queuedKnowledge = false;
        boolean createdWatch = false;
        String origin = profile.getOrDefault("hometown", "Home area").trim();

        if (planning || dealRequest || wishImpliesWatch) {
            for (String destination : destinations) {
                db.addWish(destination, focusNote(lower));
                db.queueKnowledgePack(destination);
                queuedKnowledge = true;
                if (dealRequest || wishImpliesWatch) {
                    createdWatch |= db.createDefaultDealWatch(origin, destination);
                    db.addMemory(
                            "deal_watch_request",
                            "Wants travel deal alerts for " + destination,
                            "Broad automatic watch from " + origin);
                }
            }
        }

        if (lower.contains("universal studios") || lower.contains("universal orlando")) {
            db.addMemory("trip_focus", "Orlando trip priority: Universal Studios", safe);
            if (destinations.isEmpty()) {
                destinations.add("Orlando");
                db.addWish("Orlando", "Universal Studios is the main reason for the trip");
                db.queueKnowledgePack("Orlando");
                queuedKnowledge = true;
            }
        }

        if (flexible) {
            db.addMemory("travel_preference", "Travel dates are flexible", safe);
            db.markDealWatchesFlexible(destinations);
        }

        return new Result(destinations, createdWatch, queuedKnowledge, flexible);
    }

    private static String focusNote(String lower) {
        if (lower.contains("universal studios") || lower.contains("universal orlando")) {
            return "Universal Studios is the main reason for the trip";
        }
        return "Added naturally from conversation";
    }

    private static boolean isNoPreference(String lower) {
        return lower.matches("^(i don'?t care|i do not care|anything|anytime|whenever|no preference|whatever)[.! ]*$");
    }

    private static boolean hasDealContext(List<Map<String, String>> history) {
        for (int i = Math.max(0, history.size() - 8); i < history.size(); i++) {
            String content = history.get(i).getOrDefault("content", "").toLowerCase(Locale.US);
            if (content.contains("deal") || content.contains("fare") || content.contains("price")) return true;
        }
        return false;
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
