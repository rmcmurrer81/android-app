package com.kiraworld.sarahtravel;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Higher-level offline travel dialogue after the agentic action planner. */
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
        if (TravelContextResolver.clearsTravelContext(lower)) {
            return "That’s fine. We can leave the destination undecided and talk about something else.";
        }

        JourneyIntentParser.JourneyIntent journey = JourneyIntentParser.parse(safe, profile, history);
        if (journey.found()) return JourneyPlannerCore.answer(journey);

        String prior = priorConversation(history, safe);
        boolean childSafe = isChildSafe(profile);
        List<String> currentDestinations = DestinationParser.extractDestinations(safe);
        List<String> contextDestinations = TravelContextResolver.resolveDestinations(safe, history);
        removeHomeDestination(contextDestinations, profile);

        if (isFrustratedCorrection(lower) && asksForDeals(lower)) {
            return "You’re right—I followed the wrong subject. I’ll use the active route and compare relevant air, rail, bus, driving, ferry, and local-transit options instead of returning to movies or an older destination.";
        }

        if (asksForDealNotifications(lower) || asksForDeals(lower)) {
            if (contextDestinations.isEmpty()) {
                return "I can compare travel options once a destination is named. I won’t guess or pull an old destination into this request.";
            }
            String destination = contextDestinations.get(contextDestinations.size() - 1);
            return "I’ll treat this as a multimodal watch from " + home(profile) + " to " + destination
                    + ". The default comparison includes air, Amtrak or rail, and intercity bus where available, plus the local connection at both ends. Flexible dates and sensible defaults avoid a long questionnaire. Real notifications require an available live monitoring connection.";
        }

        if (currentDestinations.size() >= 2 && isDestinationChoice(lower)) {
            String first = currentDestinations.get(0);
            String second = currentDestinations.get(1);
            return TravelKnowledgeBase.compare(first, second, "overview", childSafe);
        }

        String topic = detectTopic(lower, prior);
        if (!topic.isEmpty() && contextDestinations.size() >= 2 && isDestinationChoice(lower + " " + prior)) {
            return TravelKnowledgeBase.compare(
                    contextDestinations.get(0), contextDestinations.get(1), topic, childSafe);
        }

        if (!topic.isEmpty() && !contextDestinations.isEmpty()) {
            return TravelKnowledgeBase.answer(
                    contextDestinations.get(contextDestinations.size() - 1), topic, childSafe);
        }

        if (asksGeneralDestinationQuestion(lower) && !contextDestinations.isEmpty()) {
            return TravelKnowledgeBase.answer(
                    contextDestinations.get(contextDestinations.size() - 1), "overview", childSafe);
        }

        if (mentionsFlightFear(lower)) return firstFlightKnowledge(lower);
        if (asksAboutAirportProcess(lower)) return airportProcessKnowledge(lower);

        if (asksAboutDealKnowledge(lower)) {
            return "The best travel option is not always a flight or the cheapest headline price. Compare air, Amtrak or rail, intercity bus, driving, ferry, local transit, baggage, transfers, station or airport access, accessibility, weather, and total door-to-door time. Current prices and service conditions require live sources.";
        }

        return null;
    }

    private static String firstFlightKnowledge(String lower) {
        if (lower.contains("turbulence") || lower.contains("shaking")) {
            return "Turbulence can feel alarming. Keep your seat belt fastened, follow crew instructions, and avoid standing until they say it is okay. I can guide breathing, grounding, or personalized trivia. If there is smoke, injury, an evacuation order, or a serious physical symptom, get the crew’s attention instead of continuing a game.";
        }
        return "A first flight is easier when it is broken into stages: arrival, security, gate, boarding, takeoff, cruise, descent, landing, and ground transportation. I can explain the stage you are at without overwhelming you with the whole trip.";
    }

    private static String airportProcessKnowledge(String lower) {
        if (lower.contains("security")) {
            return "At airport security, keep identification and boarding information easy to reach, follow the officer’s instructions, and allow extra time. Rules can change, so current official guidance should be checked before the trip.";
        }
        if (lower.contains("boarding")) {
            return "Boarding usually happens by groups or zones. Check the gate display and airline app because gates can change, and keep essentials where you can reach them after larger bags are stowed.";
        }
        if (lower.contains("takeoff")) {
            return "During takeoff, engine noise rises, the aircraft accelerates, and the angle changes as it climbs. Keep the belt fastened, let your shoulders drop, and make the exhale a little longer than the inhale.";
        }
        return "I can explain check-in, security, the gate, boarding, takeoff, turbulence, landing, baggage claim, or a connection—whichever part is useful now.";
    }

    private static boolean isDestinationChoice(String lower) {
        return lower.contains("either ") || lower.contains(" or ")
                || lower.contains("which should i choose") || lower.contains("deciding between")
                || lower.contains("compare");
    }

    private static boolean asksForDeals(String lower) {
        return containsAny(lower,
                "deal", "cheap flight", "cheap ticket", "airfare", "fare", "price drop",
                "flight price", "train fare", "rail fare", "bus fare", "travel options");
    }

    private static boolean asksForDealNotifications(String lower) {
        return containsAny(lower, "notify me", "alert me", "deal alert", "price alert", "watch prices", "track prices")
                && asksForDeals(lower);
    }

    private static boolean isFrustratedCorrection(String lower) {
        return containsAny(lower,
                "not asking about", "just looking for deals", "just notify me",
                "stop talking about", "why keep asking", "that is not what i said");
    }

    private static boolean asksGeneralDestinationQuestion(String lower) {
        return containsAny(lower,
                "tell me about", "what is it like", "what's it like", "describe",
                "things to do", "places to visit", "what should i see");
    }

    private static boolean mentionsFlightFear(String lower) {
        return containsAny(lower,
                "never flown", "first flight", "scared to fly", "afraid to fly",
                "turbulence", "plane is shaking");
    }

    private static boolean asksAboutAirportProcess(String lower) {
        return containsAny(lower,
                "airport security", "boarding", "takeoff", "landing",
                "baggage claim", "connecting flight", "airport process");
    }

    private static boolean asksAboutDealKnowledge(String lower) {
        return containsAny(lower,
                "how do i find cheap", "how to find cheap", "best way to find",
                "how airfare works", "compare transportation");
    }

    private static String detectTopic(String lower, String prior) {
        if (containsAny(lower, "history", "historical", "past", "revolution", "royal", "monarchy")) return "history";
        if (containsAny(lower, "movie", "movies", "show", "shows", "book", "books", "watch", "read")) return "media";
        if (containsAny(lower, "transport", "getting around", "subway", "metro", "train", "bus")) return "transport";
        if (containsAny(lower, "first visit", "plan", "itinerary", "how many days", "week", "two weeks")) return "first visit";
        if (lower.matches("^(the )?history[.! ]*$") && prior.contains("paris") && prior.contains("london")) return "history";
        return "";
    }

    private static void removeHomeDestination(List<String> destinations, Map<String, String> profile) {
        String home = profile.getOrDefault("hometown", "").toLowerCase(Locale.US);
        destinations.removeIf(destination -> home.contains(destination.toLowerCase(Locale.US)));
    }

    private static String home(Map<String, String> profile) {
        String home = profile.getOrDefault("hometown", "your home area").trim();
        return home.isEmpty() ? "your home area" : home;
    }

    private static boolean isChildSafe(Map<String, String> profile) {
        return MaturityAccessPolicy.requiresNonAdultSafeContent(profile);
    }

    private static String priorConversation(List<Map<String, String>> history, String current) {
        StringBuilder out = new StringBuilder();
        int userSeen = 0;
        for (int i = history.size() - 1; i >= 0 && userSeen < 6; i--) {
            Map<String, String> row = history.get(i);
            if (!"user".equalsIgnoreCase(row.getOrDefault("role", "user"))) continue;
            String content = row.getOrDefault("content", "");
            if (content.equals(current)) continue;
            userSeen++;
            if (!content.isEmpty()) out.append(' ').append(content.toLowerCase(Locale.US));
        }
        return out.toString();
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }
}
