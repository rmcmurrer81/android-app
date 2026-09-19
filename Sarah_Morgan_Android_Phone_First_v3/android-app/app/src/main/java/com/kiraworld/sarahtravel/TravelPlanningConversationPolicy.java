package com.kiraworld.sarahtravel;

import java.util.Locale;

/** Pure follow-up policy for useful travel planning without unsupported live claims. */
public final class TravelPlanningConversationPolicy {
    private TravelPlanningConversationPolicy() { }

    public static boolean asksForCurrentCost(String message) {
        String lower = clean(message);
        return containsAny(lower,
                "deal", "cheap flight", "cheap ticket", "airfare", "fare", "price drop",
                "flight price", "train price", "rail fare", "bus fare", "travel options",
                "cheapest", "lowest cost", "low-cost", "low cost", "least expensive", "budget");
    }

    public static boolean asksForShortTripRecommendation(String message) {
        String lower = clean(message);
        return containsAny(lower, "what do you recommend", "what would you recommend", "recommend for")
                && containsAny(lower, "week", "days", "short trip");
    }

    /** Strong wording that identifies a planned trip rather than a tentative idea. */
    public static boolean explicitlyPlansTrip(String message) {
        String lower = clean(message);
        return containsAny(lower,
                "i'm planning a trip to", "i am planning a trip to",
                "we're planning a trip to", "we are planning a trip to",
                "i'm planning to go to", "i am planning to go to",
                "we're planning to go to", "we are planning to go to",
                "i'm traveling to", "i am traveling to",
                "i'm travelling to", "i am travelling to",
                "we're traveling to", "we are traveling to",
                "we're travelling to", "we are travelling to");
    }

    public static String shortTripReply(String destination) {
        return "For about a week in " + destination
                + ", I would choose one main base, or at most two nearby stops, so most of the trip is not lost in transit. Tell me whether you want the lowest cost, a particular experience, or a relaxed pace, and I can narrow the comparison without pretending current prices are already verified.";
    }

    public static String currentCostReply(String destination) {
        return "I can compare ways to reach " + destination
                + ", but I have not created a permanent trip or background watch. Current prices require a connected travel source or a transparent browser handoff.";
    }

    private static String clean(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }
}
