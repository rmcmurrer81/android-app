package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Higher-level offline travel dialogue. Returns null when the general local
 * companion should handle the message instead.
 */
public final class TravelBrainCore {
    private TravelBrainCore() { }

    public static String answer(
            String message,
            Map<String, String> profile,
            List<Map<String, String>> history,
            List<Map<String, String>> memories,
            List<Map<String, String>> trips,
            List<Map<String, String>> wishes) {

        String safe = message == null ? "" : message.trim();
        String lower = safe.toLowerCase(Locale.US);
        String prior = priorConversation(history, safe);
        boolean childSafe = isChildSafe(profile);
        List<String> currentDestinations = DestinationParser.extractDestinations(safe);
        List<String> contextDestinations = mergeDestinations(
                currentDestinations,
                DestinationParser.extractFromHistory(history, 12),
                destinationsFromRecords(trips, wishes));
        removeHomeDestination(contextDestinations, profile);

        if (isFrustratedCorrection(lower) && asksForDeals(lower)) {
            return dealCorrectionReply(profile, contextDestinations, memories);
        }

        if (asksForDealNotifications(lower)) {
            return dealWatchReply(profile, contextDestinations, memories);
        }

        if (asksForDeals(lower)) {
            return dealPlanningReply(profile, contextDestinations, memories, lower, prior);
        }

        if (currentDestinations.size() >= 2 && isDestinationChoice(lower)) {
            String first = currentDestinations.get(0);
            String second = currentDestinations.get(1);
            return TravelKnowledgeBase.compare(first, second, "overview", childSafe)
                    + "\n\nWould you like the next comparison to focus on history, transport, cost strategy, pace, or accessibility?";
        }

        String topic = detectTopic(lower, prior);
        if (!topic.isEmpty() && contextDestinations.size() >= 2) {
            return TravelKnowledgeBase.compare(
                    contextDestinations.get(0), contextDestinations.get(1), topic, childSafe)
                    + "\n\nWhich one feels closer to the trip you want?";
        }

        if (!topic.isEmpty() && contextDestinations.size() == 1) {
            return TravelKnowledgeBase.answer(contextDestinations.get(0), topic, childSafe);
        }

        if (asksGeneralDestinationQuestion(lower) && !contextDestinations.isEmpty()) {
            return TravelKnowledgeBase.answer(contextDestinations.get(0), "overview", childSafe);
        }

        if (mentionsFlightFear(lower)) {
            return firstFlightKnowledge(lower);
        }

        if (asksAboutAirportProcess(lower)) {
            return airportProcessKnowledge(lower);
        }

        if (asksAboutDealKnowledge(lower)) {
            return "For airfare, the cheapest headline price is not always the cheapest trip. Compare nearby airports, baggage, seat fees, cancellation rules, ground transportation, and whether an overnight schedule creates extra hotel or taxi costs. Flexible dates and light luggage can widen the useful options, but current prices always require a live source.";
        }

        return null;
    }

    private static String dealCorrectionReply(
            Map<String, String> profile,
            List<String> destinations,
            List<Map<String, String>> memories) {
        String route = route(profile, destinations);
        return "You’re right—I followed the movie topic after you had already moved on. You want deals, not more viewing suggestions. "
                + dealWatchStatus(route, memories);
    }

    private static String dealWatchReply(
            Map<String, String> profile,
            List<String> destinations,
            List<Map<String, String>> memories) {
        String route = route(profile, destinations);
        return "I understand: you want deal alerts for " + route + ". " + dealWatchStatus(route, memories);
    }

    private static String dealWatchStatus(String route, List<Map<String, String>> memories) {
        String preferences = preferenceSummary(memories);
        return "I can remember the watch request for " + route + preferences
                + ", but this build does not yet have a live airfare feed or background price-monitoring service. "
                + "I should not pretend that I am already watching prices. Once the team connects a fare provider or a protected backend, the saved request can become real notifications. For now, I can open live fare searches and help compare the results.";
    }

    private static String dealPlanningReply(
            Map<String, String> profile,
            List<String> destinations,
            List<Map<String, String>> memories,
            String lower,
            String prior) {
        if (destinations.isEmpty()) {
            return "What destination should I compare from " + home(profile) + "?";
        }
        String route = route(profile, destinations);
        boolean flexible = hasMemory(memories, "dates are flexible") || containsAny(lower,
                "flexible", "any dates", "any days", "don't care about dates", "do not care about dates");
        boolean light = hasMemory(memories, "travels light") || containsAny(lower,
                "travel light", "carry-on", "carry on", "no checked bag");
        boolean roundTripKnown = containsAny(lower + " " + prior, "round trip", "round-trip", "one way", "one-way");
        boolean travelerKnown = containsAny(lower + " " + prior, "one traveler", "two travelers", "just me", "traveling alone", "travelling alone");

        if (!flexible) return "For " + route + ", are your dates flexible or do you have a travel window?";
        if (!light) return "For " + route + ", will you travel with only a carry-on or check a bag?";
        if (!roundTripKnown) return "For " + route + ", should I treat this as round-trip or one-way?";
        if (!travelerKnown) return "For " + route + ", how many travelers should I plan for?";
        return "I have the route and the main preferences. Say “open the live search” when you want the fare sites, or ask me to summarize the watch request first.";
    }

    private static String firstFlightKnowledge(String lower) {
        if (lower.contains("turbulence") || lower.contains("shaking")) {
            return "Turbulence can feel alarming even when the crew is following normal procedures. Keep your seat belt fastened, follow crew instructions, and avoid standing until they say it is okay. For distraction, I can guide breathing, grounding, or personalized trivia. If there is smoke, injury, an evacuation order, or a serious physical symptom, get the crew’s attention instead of continuing a game.";
        }
        return "A first flight is easier when it is broken into stages: arrive, check bags if needed, pass security, find the gate, board when your group is called, stow belongings, fasten the seat belt, take off, cruise, descend, land, and follow signs to baggage claim or ground transportation. Tell me which stage you want explained in detail.";
    }

    private static String airportProcessKnowledge(String lower) {
        if (lower.contains("security")) {
            return "At airport security, keep identification and boarding information easy to reach, follow the officer’s instructions, and allow extra time rather than rushing. Rules about liquids, electronics, shoes, and medical items can change by country and program, so Sarah should use a current official source before the trip.";
        }
        if (lower.contains("boarding")) {
            return "Boarding usually happens by groups or zones. The boarding pass and gate display show the current gate and group. Gates can change, so check the airport display and airline app instead of relying only on an old screenshot.";
        }
        if (lower.contains("takeoff")) {
            return "During takeoff, engine noise rises, the aircraft accelerates, and the angle changes as it climbs. Those sensations can be strong on a first flight. Keep the belt fastened, let your shoulders drop, and focus on a longer exhale.";
        }
        return "Which part do you want: check-in, security, finding the gate, boarding, takeoff, turbulence, landing, baggage claim, or making a connection?";
    }

    private static boolean isDestinationChoice(String lower) {
        return lower.contains("either ") || lower.contains(" or ") || lower.contains("which should i choose")
                || lower.contains("deciding between") || lower.contains("compare");
    }

    private static boolean asksForDeals(String lower) {
        return containsAny(lower, "deal", "cheap flight", "cheap ticket", "airfare", "fare", "price drop", "flight price");
    }

    private static boolean asksForDealNotifications(String lower) {
        return containsAny(lower, "notify me", "alert me", "deal alert", "price alert", "watch prices", "track prices")
                && asksForDeals(lower);
    }

    private static boolean isFrustratedCorrection(String lower) {
        return containsAny(lower, "i don't care about watching", "i dont care about watching",
                "not asking about movies", "just looking for deals", "just notify me", "ok????", "stop talking about");
    }

    private static boolean asksGeneralDestinationQuestion(String lower) {
        return containsAny(lower, "tell me about", "what is it like", "what's it like", "describe", "things to do", "places to visit", "what should i see");
    }

    private static boolean mentionsFlightFear(String lower) {
        return containsAny(lower, "never flown", "first flight", "scared to fly", "afraid to fly", "turbulence", "plane is shaking");
    }

    private static boolean asksAboutAirportProcess(String lower) {
        return containsAny(lower, "airport security", "boarding", "takeoff", "landing", "baggage claim", "connecting flight", "airport process");
    }

    private static boolean asksAboutDealKnowledge(String lower) {
        return containsAny(lower, "how do i find cheap", "how to find cheap", "best way to find flights", "how airfare works");
    }

    private static String detectTopic(String lower, String prior) {
        if (containsAny(lower, "history", "historical", "past", "revolution", "royal", "monarchy")) return "history";
        if (containsAny(lower, "movie", "movies", "show", "shows", "book", "books", "watch", "read")) return "media";
        if (containsAny(lower, "transport", "getting around", "subway", "metro", "train", "bus")) return "transport";
        if (containsAny(lower, "first visit", "plan", "itinerary", "how many days", "week", "two weeks")) return "first visit";
        if (lower.matches("^(the )?history[.! ]*$") && prior.contains("paris") && prior.contains("london")) return "history";
        return "";
    }

    @SafeVarargs
    private static List<String> mergeDestinations(List<String>... sources) {
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

    private static List<String> destinationsFromRecords(
            List<Map<String, String>> trips,
            List<Map<String, String>> wishes) {
        List<String> result = new ArrayList<>();
        for (Map<String, String> row : trips) {
            result.addAll(DestinationParser.extractDestinations(row.getOrDefault("destination", "")));
        }
        for (Map<String, String> row : wishes) {
            result.addAll(DestinationParser.extractDestinations(row.getOrDefault("destination", "")));
        }
        return result;
    }

    private static void removeHomeDestination(List<String> destinations, Map<String, String> profile) {
        String home = profile.getOrDefault("hometown", "").toLowerCase(Locale.US);
        destinations.removeIf(destination -> home.contains(destination.toLowerCase(Locale.US)));
    }

    private static String route(Map<String, String> profile, List<String> destinations) {
        String destinationText = destinations.isEmpty() ? "the destination you choose" : DestinationParser.join(destinations);
        return home(profile) + " to " + destinationText;
    }

    private static String home(Map<String, String> profile) {
        String home = profile.getOrDefault("hometown", "your home airport").trim();
        return home.isEmpty() ? "your home airport" : home;
    }

    private static String preferenceSummary(List<Map<String, String>> memories) {
        List<String> parts = new ArrayList<>();
        if (hasMemory(memories, "dates are flexible")) parts.add("flexible dates");
        if (hasMemory(memories, "travels light")) parts.add("light luggage");
        if (hasMemory(memories, "one traveler")) parts.add("one traveler");
        if (parts.isEmpty()) return "";
        return " with " + String.join(", ", parts);
    }

    private static boolean hasMemory(List<Map<String, String>> memories, String phrase) {
        String target = phrase.toLowerCase(Locale.US);
        for (Map<String, String> memory : memories) {
            if (memory.getOrDefault("summary", "").toLowerCase(Locale.US).contains(target)) return true;
        }
        return false;
    }

    private static boolean isChildSafe(Map<String, String> profile) {
        String group = profile.getOrDefault("age_group", "adult");
        return "child".equals(group) || "unknown_use_child_safe_mode".equals(group);
    }

    private static String priorConversation(List<Map<String, String>> history, String current) {
        StringBuilder out = new StringBuilder();
        int start = Math.max(0, history.size() - 12);
        for (int i = start; i < history.size(); i++) {
            String content = history.get(i).getOrDefault("content", "");
            boolean duplicate = i == history.size() - 1 && content.equals(current);
            if (!duplicate && !content.isEmpty()) out.append(' ').append(content.toLowerCase(Locale.US));
        }
        return out.toString();
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }
}
