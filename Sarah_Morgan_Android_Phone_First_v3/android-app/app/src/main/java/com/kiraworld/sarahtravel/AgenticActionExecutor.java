package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Map;

public final class AgenticActionExecutor {
    public static final class Result {
        /** True when a fare, mobility, or event watch needs notification permission. */
        public final boolean createdDealWatch;
        public final boolean queuedKnowledge;
        public final boolean changedEventMonitor;
        public final boolean importedBooking;

        Result(
                boolean createdDealWatch,
                boolean queuedKnowledge,
                boolean changedEventMonitor,
                boolean importedBooking) {
            this.createdDealWatch = createdDealWatch;
            this.queuedKnowledge = queuedKnowledge;
            this.changedEventMonitor = changedEventMonitor;
            this.importedBooking = importedBooking;
        }
    }

    private AgenticActionExecutor() { }

    public static Result apply(
            Context context,
            SarahDatabase db,
            Map<String, String> profile,
            List<AgenticTravelPlanner.Action> actions) {
        boolean createdWatch = false;
        boolean queuedKnowledge = false;
        boolean changedEventMonitor = false;
        boolean importedBooking = false;
        String origin = profile.getOrDefault("hometown", "Home area").trim();
        EventTripStore eventStore = new EventTripStore(context.getApplicationContext());
        MobilityWatchStore mobilityStore = new MobilityWatchStore(context.getApplicationContext());

        try {
            for (AgenticTravelPlanner.Action action : actions) {
                if (AgenticTravelPlanner.QUEUE_KNOWLEDGE_PACK.equals(action.type)) {
                    db.queueKnowledgePack(action.destination);
                    queuedKnowledge = true;
                } else if (AgenticTravelPlanner.SAVE_WISH.equals(action.type)) {
                    db.addWish(action.destination, action.detail);
                } else if (AgenticTravelPlanner.CREATE_DEAL_WATCH.equals(action.type)) {
                    createdWatch |= db.createDefaultDealWatch(origin, action.destination);
                } else if (AgenticTravelPlanner.UPDATE_DESTINATION_FOCUS.equals(action.type)) {
                    db.addMemory("trip_focus", action.destination + " trip priority: " + action.detail, action.detail);
                    db.addWish(action.destination, action.detail + " is the main reason for the trip");
                } else if (AgenticTravelPlanner.SET_FLEXIBLE_DATES.equals(action.type)) {
                    db.addMemory("travel_preference", "Travel dates are flexible", action.detail);
                    db.markDealWatchesFlexible(List.of(action.destination));
                } else if (AgenticTravelPlanner.CREATE_EVENT_TRIP.equals(action.type)) {
                    long eventTripId = eventStore.upsertEventTrip(action.detail, action.destination, true);
                    changedEventMonitor |= eventTripId > 0;
                    db.addMemory(
                            "event_trip",
                            "Plans to attend " + action.detail + " in " + action.destination,
                            action.detail + " | " + action.destination);
                } else if (AgenticTravelPlanner.SAVE_BOOKING_LINK.equals(action.type)) {
                    String[] detail = action.detail.split("\\|", 2);
                    String provider = detail.length > 0 ? detail[0] : "Other";
                    String bookingType = detail.length > 1 ? detail[1] : "travel";
                    importedBooking = eventStore.addBookingLink(
                            provider,
                            bookingType,
                            action.destination,
                            action.destination) > 0;
                } else if (AgenticTravelPlanner.SAVE_JOURNEY_PLAN.equals(action.type)) {
                    JourneyDetail detail = JourneyDetail.parse(action.detail, origin);
                    long id = mobilityStore.saveJourneyPlan(
                            detail.origin,
                            action.destination,
                            detail.eventName,
                            detail.modes,
                            detail.purpose);
                    if (id > 0) {
                        db.addMemory(
                                "journey_plan",
                                "Journey from " + detail.origin + " to " + action.destination
                                        + " using " + detail.modes.replace(',', '/'),
                                action.detail);
                    }
                } else if (AgenticTravelPlanner.CREATE_MOBILITY_WATCH.equals(action.type)) {
                    JourneyDetail detail = JourneyDetail.parse(action.detail, origin);
                    createdWatch |= mobilityStore.createWatch(
                            detail.origin,
                            action.destination,
                            detail.eventName,
                            detail.modes,
                            detail.purpose);
                }
            }
        } finally {
            eventStore.close();
            mobilityStore.close();
        }

        SharedPreferences preferences = context.getSharedPreferences(
                SettingsActivity.PREFS,
                Context.MODE_PRIVATE);
        boolean alertsEnabled = preferences.getBoolean("deal_alerts_enabled", true);
        boolean researchEnabled = preferences.getBoolean("auto_destination_research", true);
        if ((createdWatch && alertsEnabled) || (queuedKnowledge && researchEnabled)) {
            DealWatchScheduler.ensureScheduled(context);
            DealWatchScheduler.runSoon(context);
        }
        if (changedEventMonitor || importedBooking) {
            EventMonitorScheduler.ensureScheduled(context);
            EventMonitorScheduler.runSoon(context);
        }
        return new Result(
                createdWatch || changedEventMonitor,
                queuedKnowledge,
                changedEventMonitor,
                importedBooking);
    }

    private static final class JourneyDetail {
        final String origin;
        final String eventName;
        final String modes;
        final String purpose;

        JourneyDetail(String origin, String eventName, String modes, String purpose) {
            this.origin = origin;
            this.eventName = eventName;
            this.modes = modes;
            this.purpose = purpose;
        }

        static JourneyDetail parse(String encoded, String fallbackOrigin) {
            String[] parts = (encoded == null ? "" : encoded).split("\\|", -1);
            String origin = parts.length > 0 && !parts[0].trim().isEmpty() ? parts[0].trim() : fallbackOrigin;
            String event = parts.length > 1 ? parts[1].trim() : "";
            String modes = parts.length > 2 && !parts[2].trim().isEmpty()
                    ? parts[2].trim() : "air,rail,intercity_bus";
            String purpose = parts.length > 3 && !parts[3].trim().isEmpty()
                    ? parts[3].trim() : "options";
            return new JourneyDetail(origin, event, modes, purpose);
        }
    }
}
