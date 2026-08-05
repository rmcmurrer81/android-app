package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure, inspectable planning layer that turns ordinary travel statements into
 * useful background actions without interrogating the traveler.
 */
public final class AgenticTravelPlanner {
    public static final String QUEUE_KNOWLEDGE_PACK = "queue_knowledge_pack";
    public static final String SAVE_WISH = "save_wish";
    public static final String CREATE_DEAL_WATCH = "create_deal_watch";
    public static final String UPDATE_DESTINATION_FOCUS = "update_destination_focus";
    public static final String SET_FLEXIBLE_DATES = "set_flexible_dates";

    public static final class Action {
        public final String type;
        public final String destination;
        public final String detail;

        public Action(String type, String destination, String detail) {
            this.type = type;
            this.destination = destination == null ? "" : destination;
            this.detail = detail == null ? "" : detail;
        }
    }

    public static final class Plan {
        public final String reply;
        public final List<Action> actions;

        public Plan(String reply, List<Action> actions) {
            this.reply = reply;
            this.actions = actions;
        }

        public boolean handled() {
            return reply != null && !reply.isEmpty();
        }
    }

    private AgenticTravelPlanner() { }

    public static Plan plan(
            String message,
            Map<String, String> profile,
            List<Map<String, String>> history,
            List<Map<String, String>> memories) {
        String safe = message == null ? "" : message.trim();
        String lower = safe.toLowerCase(Locale.US);
        String prior = priorConversation(history, safe);
        List<String> current = DestinationParser.extractDestinations(safe);
        List<String> context = merge(current, DestinationParser.extractFromHistory(history, 12));
        removeHome(context, profile);
        List<Action> actions = new ArrayList<>();

        if (isConversationClosure(lower)) {
            if (containsAny(prior, "date", "fare", "deal", "price", "flight")) {
                for (String destination : context) {
                    actions.add(new Action(SET_FLEXIBLE_DATES, destination, "Traveler does not care which dates are used"));
                }
                return new Plan(
                        "Understood. I’ll treat the dates as flexible and stop asking. I’ll use the destination, your saved travel preferences, nearby airports, and several trip lengths when the deal checker runs.",
                        actions);
            }
            return new Plan("Understood. I have enough for now, so I won’t keep asking questions.", actions);
        }

        String focus = attractionFocus(lower);
        if (!focus.isEmpty() && !context.isEmpty()) {
            String destination = context.get(0);
            actions.add(new Action(UPDATE_DESTINATION_FOCUS, destination, focus));
            actions.add(new Action(QUEUE_KNOWLEDGE_PACK, destination, focus));
            return new Plan(
                    focus + " is the main focus of the " + destination + " trip. I’ll prioritize that in the guide, including realistic pacing, transportation, nearby places, weather concerns, and current events when online. You do not need to add anything else unless you want to change the plan.",
                    actions);
        }

        if (isPlanningStatement(lower) && !current.isEmpty()) {
            boolean dream = containsAny(lower, "always wanted", "dream of", "dreamed of", "bucket list");
            boolean asksDeals = asksForDeals(lower) || dream;
            for (String destination : current) {
                actions.add(new Action(QUEUE_KNOWLEDGE_PACK, destination, "Automatic destination research"));
                actions.add(new Action(SAVE_WISH, destination, dream ? "Long-term dream destination" : "Possible trip"));
                if (asksDeals) {
                    actions.add(new Action(CREATE_DEAL_WATCH, destination, "Flexible dates; nearby airports; multiple trip lengths"));
                }
            }
            String destinations = DestinationParser.join(current);
            StringBuilder reply = new StringBuilder();
            reply.append(destinations).append(" is now on my planning list. I’ll build or refresh a local guide automatically with recommended areas, places, transport, practical concerns, and current events when the connected research service is available. ");
            if (asksDeals) {
                reply.append("I’ll also create a broad deal watch using ")
                        .append(home(profile))
                        .append(" as the starting area, flexible dates, nearby airports, and several trip lengths. ");
            }
            reply.append("I’ll use sensible defaults instead of asking a long series of questions; you can correct any detail later.");
            return new Plan(reply.toString(), actions);
        }

        if ((asksForDeals(lower) || asksForNotifications(lower)) && !context.isEmpty()) {
            for (String destination : context) {
                actions.add(new Action(CREATE_DEAL_WATCH, destination, "Flexible dates; nearby airports; multiple trip lengths"));
            }
            return new Plan(
                    "I’ll save a broad deal watch from " + home(profile) + " to " + DestinationParser.join(context)
                            + " using flexible dates, nearby airports, and several trip lengths. I won’t keep questioning you. The app can schedule checks now, but real fare notifications require the team’s travel-data backend; until that is connected, the watch will remain clearly marked as waiting for live prices.",
                    actions);
        }

        if (lower.equals("i don't care") || lower.equals("i dont care") || lower.equals("any time") || lower.equals("whenever")) {
            for (String destination : context) {
                actions.add(new Action(SET_FLEXIBLE_DATES, destination, "Traveler accepts any dates"));
            }
            return new Plan("That works. I’ll treat the dates as flexible and make the comparison myself instead of asking again.", actions);
        }

        return new Plan(null, actions);
    }

    private static boolean isPlanningStatement(String lower) {
        return containsAny(lower,
                "thinking about going", "thinking of going", "planning on going", "planning to go",
                "want to go", "want to visit", "would love to travel", "would love to visit",
                "always wanted to visit", "always wanted to go", "dream of visiting",
                "dreamed of visiting", "bucket list");
    }

    private static boolean asksForDeals(String lower) {
        return containsAny(lower, "deal", "cheap flight", "cheap ticket", "airfare", "fare", "price drop", "flight price", "watch prices", "track prices");
    }

    private static boolean asksForNotifications(String lower) {
        return containsAny(lower, "notify me", "alert me", "watch for deals", "keep an eye on", "from time to time");
    }

    private static boolean isConversationClosure(String lower) {
        return lower.matches("^(that is it|that's it|thats it|nothing|no|nope|i don't care|i dont care|whatever)[.! ]*$");
    }

    private static String attractionFocus(String lower) {
        if (lower.contains("universal studios")) return "Universal Studios";
        if (lower.contains("disney world") || lower.contains("walt disney world")) return "Walt Disney World";
        if (lower.contains("disneyland")) return "Disneyland";
        if (lower.contains("six flags")) return "Six Flags";
        return "";
    }

    private static List<String> merge(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>();
        for (String value : first) addUnique(result, value);
        for (String value : second) addUnique(result, value);
        return result;
    }

    private static void addUnique(List<String> values, String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) return;
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) return;
        }
        values.add(candidate);
    }

    private static void removeHome(List<String> destinations, Map<String, String> profile) {
        String home = profile.getOrDefault("hometown", "").toLowerCase(Locale.US);
        destinations.removeIf(destination -> home.contains(destination.toLowerCase(Locale.US)));
    }

    private static String home(Map<String, String> profile) {
        String home = profile.getOrDefault("hometown", "your home area").trim();
        return home.isEmpty() ? "your home area" : home;
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
