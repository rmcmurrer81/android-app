package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure planning layer that turns ordinary travel statements into useful work
 * without interrogating the traveler or dragging old destinations forward.
 */
public final class AgenticTravelPlanner {
    public static final String QUEUE_KNOWLEDGE_PACK = "queue_knowledge_pack";
    public static final String SAVE_WISH = "save_wish";
    public static final String CREATE_DEAL_WATCH = "create_deal_watch";
    public static final String UPDATE_DESTINATION_FOCUS = "update_destination_focus";
    public static final String SET_FLEXIBLE_DATES = "set_flexible_dates";
    public static final String CREATE_EVENT_TRIP = "create_event_trip";
    public static final String CANCEL_EVENT_MONITOR = "cancel_event_monitor";
    public static final String SAVE_BOOKING_LINK = "save_booking_link";
    public static final String SAVE_JOURNEY_PLAN = "save_journey_plan";
    public static final String CREATE_MOBILITY_WATCH = "create_mobility_watch";
    public static final String SAVE_PLANNED_TRIP = "save_planned_trip";

    public static final class Action {
        public final String type;
        public final String destination;
        public final String detail;
        public final boolean monitoringRequested;

        public Action(String type, String destination, String detail) {
            this(type, destination, detail, false);
        }

        public Action(
                String type,
                String destination,
                String detail,
                boolean monitoringRequested) {
            this.type = type;
            this.destination = destination == null ? "" : destination;
            this.detail = detail == null ? "" : detail;
            this.monitoringRequested = monitoringRequested;
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
        List<String> context = new ArrayList<>(
                TravelContextResolver.resolveDestinations(safe, history));
        removeHome(context, profile);
        List<Action> actions = new ArrayList<>();

        BookingLinkParser.BookingLink bookingLink = BookingLinkParser.parse(safe);
        if (bookingLink.found()) {
            actions.add(new Action(
                    SAVE_BOOKING_LINK,
                    bookingLink.url,
                    bookingLink.provider + "|" + bookingLink.bookingType));
            return new Plan(
                    "I saved the " + bookingLink.provider + " link as a pending "
                            + bookingLink.bookingType + " booking import. I will not treat a private booking page as verified just because I have its link. If details are hidden behind a login, share a visible screenshot and review the extracted copy before it becomes a trip fact.",
                    actions);
        }

        if (TravelContextResolver.clearsTravelContext(lower)) {
            return new Plan(
                    "That’s okay. We can leave the destination or plan undecided for now. I won’t keep asking or pull an older trip back into this conversation.",
                    actions);
        }

        EventTripIntentParser.EventIntent eventIntent = EventTripIntentParser.parse(safe);
        JourneyIntentParser.JourneyIntent journey = JourneyIntentParser.parse(safe, profile, history);
        if (eventIntent.monitoringCancellationRequested && eventIntent.recognized()) {
            actions.add(new Action(
                    CANCEL_EVENT_MONITOR,
                    eventIntent.destination,
                    eventIntent.eventName));
            return new Plan(
                    "I will resolve that exact event against this active profile and turn off only one unambiguous monitor. The saved event trip will remain.",
                    actions);
        }
        if (eventIntent.found()) {
            boolean memoryConsent = "yes".equals(
                    profile.getOrDefault("memory_consent", "no"));
            if (eventIntent.monitoringCancellationRequested) {
                actions.add(new Action(
                        CANCEL_EVENT_MONITOR,
                        eventIntent.destination,
                        eventIntent.eventName));
                return new Plan(
                        "I’ll turn off the exact " + eventIntent.eventName
                                + " monitor for this active profile without deleting the saved event trip.",
                        actions);
            }
            actions.add(new Action(
                    CREATE_EVENT_TRIP,
                    eventIntent.destination,
                    eventIntent.eventName,
                    eventIntent.monitoringRequested));
            actions.add(new Action(
                    QUEUE_KNOWLEDGE_PACK,
                    eventIntent.destination,
                    eventIntent.eventName + " event-centered trip"));
            if (memoryConsent) {
                actions.add(new Action(
                        SAVE_WISH,
                        eventIntent.destination,
                        "Trip centered on " + eventIntent.eventName));
            }
            if (journey.found()) {
                actions.add(new Action(
                        SAVE_JOURNEY_PLAN,
                        journey.destination,
                        journeyDetail(journey, "Event journey")));
                if (journey.monitorRequested) {
                    actions.add(new Action(
                            CREATE_MOBILITY_WATCH,
                            journey.destination,
                            journeyDetail(journey, "event_transport")));
                }
            }
            StringBuilder reply = new StringBuilder();
            reply.append("I’ll treat ").append(eventIntent.eventName).append(" as the center of the ")
                    .append(eventIntent.destination).append(" trip. ");
            if (eventIntent.monitoringRequested) {
                reply.append("You explicitly asked for monitoring, so I’ll enable it only if the owner opt-in and source-route gates pass. ");
            } else {
                reply.append("I’ll save the event details without silently turning on background monitoring. ");
            }
            reply.append("I’ll also build a nearby list for food and places worth checking out around the event area.");
            if (journey.found()) {
                reply.append(" I’ll save ").append(modeLabel(journey.modes))
                        .append(" from ").append(journey.origin).append(" as the travel method instead of assuming you are flying.");
            }
            reply.append(" I won’t make you answer a long form first.");
            return new Plan(reply.toString(), actions);
        }

        if (eventIntent.recognized()) {
            return new Plan(
                    "I recognize “" + eventIntent.eventName
                            + "” as an event, not a city. I’ll look for a likely official page, verify its location and dates when public lookup is available, and only then save the verified event trip. I won’t create a fake destination record while those details are unknown.",
                    actions);
        }

        TripWindowParser.TripWindow timedTrip = TripWindowParser.parse(safe);
        if (timedTrip.found()) {
            actions.add(new Action(
                    SAVE_PLANNED_TRIP,
                    timedTrip.destination,
                    timedTrip.encodedDates()));
            actions.add(new Action(
                    QUEUE_KNOWLEDGE_PACK,
                    timedTrip.destination,
                    "Timed trip research for " + timedTrip.startDate + " through " + timedTrip.endDate));
            return new Plan(CityVisitPlanner.answer(timedTrip, profile, memories), actions);
        }

        if (journey.found()) {
            actions.add(new Action(
                    SAVE_JOURNEY_PLAN,
                    journey.destination,
                    journeyDetail(journey, "Journey from conversation")));
            actions.add(new Action(
                    QUEUE_KNOWLEDGE_PACK,
                    journey.destination,
                    "Journey destination research"));
            if (journey.monitorRequested) {
                actions.add(new Action(
                        CREATE_MOBILITY_WATCH,
                        journey.destination,
                        journeyDetail(journey, "options")));
            }
            String reply = JourneyPlannerCore.answer(journey);
            if (journey.monitorRequested) {
                reply += " You explicitly requested monitoring. The app will create a multimodal watch only if a real travel provider is configured; otherwise no active watch is created and the interface will say setup is required.";
            }
            return new Plan(reply, actions);
        }

        if (isConversationClosure(lower)) {
            if (containsAny(prior, "date", "fare", "deal", "price", "flight", "train", "rail", "bus")) {
                for (String destination : context) {
                    actions.add(new Action(SET_FLEXIBLE_DATES, destination, "Traveler does not care which dates are used"));
                }
                return new Plan(
                        "Understood. I’ll treat the dates as flexible and stop asking. I’ll use the active route and compare the relevant transport methods when the travel service runs.",
                        actions);
            }
            return new Plan("Understood. I have enough for now, so I won’t keep asking questions.", actions);
        }

        String focus = attractionFocus(lower);
        if (!focus.isEmpty() && !context.isEmpty()) {
            String destination = context.get(context.size() - 1);
            actions.add(new Action(UPDATE_DESTINATION_FOCUS, destination, focus));
            actions.add(new Action(QUEUE_KNOWLEDGE_PACK, destination, focus));
            return new Plan(
                    focus + " is the main focus of the " + destination + " trip. I’ll prioritize realistic pacing, transportation, nearby places, weather concerns, and current events without making you keep explaining it.",
                    actions);
        }

        if (isPlanningStatement(lower) && !current.isEmpty()) {
            boolean saveRequested = containsAny(lower, "save this", "save it", "add to my wish", "add it to my wish", "put on my wish", "remember this destination");
            boolean memoryConsent = "yes".equals(profile.getOrDefault("memory_consent", "no"));
            boolean durablePlan = TravelPlanningConversationPolicy.explicitlyPlansTrip(lower)
                    && memoryConsent;
            for (String destination : current) {
                if (memoryConsent) actions.add(new Action(
                        QUEUE_KNOWLEDGE_PACK, destination, "Automatic destination research"));
                if (saveRequested && memoryConsent) actions.add(new Action(
                        SAVE_WISH, destination, "Owner-requested wish-list destination"));
                if (durablePlan) actions.add(new Action(
                        SAVE_PLANNED_TRIP,
                        destination,
                        "||conversation-confirmed planned destination; dates not set"));
            }
            String destinations = DestinationParser.join(current);
            StringBuilder reply = new StringBuilder();
            if (durablePlan) {
                reply.append("I saved ").append(destinations)
                        .append(" as a planned destination with dates not set. That is not a booking or an active watch. ");
            } else {
                reply.append("I’ll treat ").append(destinations)
                        .append(" as a possible destination in this conversation, not your home area, confirmed trip, or active watch. ");
            }
            if (memoryConsent) {
                reply.append("A destination research request is saved separately and is runnable only after the owner opt-in, connection, source, and Android scheduling gates all pass. ");
            } else {
                reply.append("I did not save a destination research request because memory is not enabled for this profile. ");
            }
            if (saveRequested && memoryConsent) {
                reply.append("You asked me to save it to your wish list. ");
            } else if (saveRequested) {
                reply.append("You asked me to save it, but I did not create a permanent wish because memory is not enabled for this profile. ");
            } else {
                reply.append("I have not added it to your permanent wish list. ");
            }
            reply.append("I’ll use reversible planning defaults and let you correct them later.");
            if (containsAny(lower, "in my email", "in an email", "from my email", "gmail")) {
                reply.append(" Your itinerary details may be imported only through the read-only Gmail connection or from the exact booking message you choose to share; I have not read your mailbox in this turn.");
            }
            return new Plan(reply.toString(), actions);
        }

        if (asksForNotifications(lower) && !context.isEmpty()) {
            String destination = context.get(context.size() - 1);
            JourneyIntentParser.JourneyIntent broad = JourneyIntentParser.parse(
                    "monitor travel options to " + destination, profile, history);
            actions.add(new Action(
                    CREATE_MOBILITY_WATCH,
                    destination,
                    journeyDetail(broad, "options")));
            return new Plan(
                    "You explicitly requested a travel-options watch from " + home(profile) + " to " + destination
                            + ". The app will activate it only if a real provider is configured; otherwise it remains a request with setup required, not a running search.",
                    actions);
        }

        if (TravelPlanningConversationPolicy.asksForShortTripRecommendation(lower)
                && !context.isEmpty()) {
            String destination = context.get(context.size() - 1);
            return new Plan(TravelPlanningConversationPolicy.shortTripReply(destination), actions);
        }

        if (TravelPlanningConversationPolicy.asksForCurrentCost(lower) && !context.isEmpty()) {
            String destination = context.get(context.size() - 1);
            return new Plan(TravelPlanningConversationPolicy.currentCostReply(destination), actions);
        }

        return new Plan(null, actions);
    }

    private static String journeyDetail(JourneyIntentParser.JourneyIntent journey, String purpose) {
        return journey.origin + "|" + journey.eventName + "|" + journey.modeCsv() + "|" + purpose;
    }

    private static String modeLabel(List<String> modes) {
        if (modes == null || modes.isEmpty()) return "the route";
        return modes.get(0).replace('_', ' ');
    }

    private static boolean isPlanningStatement(String lower) {
        return containsAny(lower,
                "thinking about going", "thinking of going", "planning on going", "planning to go", "planning a trip to",
                "i am traveling to", "i'm traveling to", "i am travelling to", "i'm travelling to",
                "we are traveling to", "we're traveling to", "we are travelling to", "we're travelling to",
                "want to go", "want to visit", "would love to travel", "would love to visit",
                "always wanted to visit", "always wanted to go", "dream of visiting",
                "dreamed of visiting", "bucket list");
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
        for (String phrase : phrases) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }
}
