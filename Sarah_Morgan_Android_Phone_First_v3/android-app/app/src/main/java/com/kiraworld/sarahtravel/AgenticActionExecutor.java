package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class AgenticActionExecutor {
    public static final class Result {
        /** True when a fare, mobility, or event watch needs notification permission. */
        public final boolean createdDealWatch;
        public final boolean queuedKnowledge;
        public final boolean changedEventMonitor;
        public final boolean importedBooking;
        public final boolean monitoringUnavailable;
        public final List<String> durableActionReceipts;
        public final List<String> pendingActionReceipts;

        Result(
                boolean createdDealWatch,
                boolean queuedKnowledge,
                boolean changedEventMonitor,
                boolean importedBooking,
                boolean monitoringUnavailable,
                List<String> durableActionReceipts,
                List<String> pendingActionReceipts) {
            this.createdDealWatch = createdDealWatch;
            this.queuedKnowledge = queuedKnowledge;
            this.changedEventMonitor = changedEventMonitor;
            this.importedBooking = importedBooking;
            this.monitoringUnavailable = monitoringUnavailable;
            this.durableActionReceipts = new ArrayList<>(durableActionReceipts);
            this.pendingActionReceipts = new ArrayList<>(pendingActionReceipts);
        }

        public boolean hasDurableBackgroundWork() { return !durableActionReceipts.isEmpty(); }
        public String receiptSummary() { return String.join("; ", durableActionReceipts); }
        public boolean hasPendingRequests() { return !pendingActionReceipts.isEmpty(); }
        public String pendingSummary() { return String.join("; ", pendingActionReceipts); }
    }

    private AgenticActionExecutor() { }

    public static Result apply(
            Context context,
            SarahDatabase db,
            Map<String, String> profile,
            List<AgenticTravelPlanner.Action> actions) {
        return apply(context, db, profile, actions, false);
    }

    public static Result apply(
            Context context,
            SarahDatabase db,
            Map<String, String> profile,
            List<AgenticTravelPlanner.Action> actions,
            boolean validatedInternet) {
        boolean createdWatch = false;
        boolean queuedKnowledge = false;
        boolean changedEventMonitor = false;
        boolean importedBooking = false;
        boolean monitoringUnavailable = false;
        List<String> durableReceipts = new ArrayList<>();
        List<String> pendingReceipts = new ArrayList<>();
        String origin = profile.getOrDefault("hometown", "Home area").trim();
        EventTripStore eventStore = new EventTripStore(context.getApplicationContext());
        MobilityWatchStore mobilityStore = new MobilityWatchStore(context.getApplicationContext());
        PersonProfileStore people = new PersonProfileStore(context.getApplicationContext());
        SharedPreferences preferences = context.getSharedPreferences(
                SettingsActivity.PREFS,
                Context.MODE_PRIVATE);
        boolean alertsEnabled = preferences.getBoolean(
                "deal_alerts_enabled",
                BackgroundResearchPolicy.DEFAULT_BACKGROUND_MONITORING_ENABLED);
        String personId = profile.getOrDefault(
                "person_id", profile.getOrDefault("name", "unknown_profile"));
        boolean researchEnabled = new SarahLocationStore(context)
                .backgroundResearchEnabled(personId);
        boolean memoryConsent = "yes".equals(profile.getOrDefault("memory_consent", "no"));
        String knowledgeScope = KnowledgeProfileKey.forProfile(profile);

        try {
            for (AgenticTravelPlanner.Action action : actions) {
                if (AgenticTravelPlanner.QUEUE_KNOWLEDGE_PACK.equals(action.type)) {
                    boolean requestSaved = KnowledgePackSchedulingPolicy.canRequest(
                            memoryConsent, action.destination)
                            && db.queueKnowledgePack(
                                    knowledgeScope, action.destination, false);
                    if (!requestSaved) continue;
                    String receipt = "destination knowledge request for " + action.destination;
                    boolean canSchedule = KnowledgePackSchedulingPolicy.canSchedule(
                            isOwner(profile),
                            memoryConsent,
                            validatedInternet,
                            SarahModelConfig.fullConversationAvailable(),
                            TavilyClient.configured(),
                            researchEnabled);
                    boolean scheduled = false;
                    boolean promotedBeforeScheduling = canSchedule
                            && db.markKnowledgePackScheduled(knowledgeScope, action.destination);
                    if (promotedBeforeScheduling) {
                        boolean periodicAccepted = DealWatchScheduler.ensureScheduled(context);
                        boolean immediateAccepted = DealWatchScheduler.runSoon(context);
                        scheduled = periodicAccepted || immediateAccepted;
                        if (!scheduled) {
                            db.markKnowledgePackNotScheduled(knowledgeScope, action.destination);
                        }
                    }
                    if (scheduled) {
                        queuedKnowledge = true;
                        durableReceipts.add(receipt);
                    } else {
                        pendingReceipts.add(receipt + " (saved, not scheduled)");
                        monitoringUnavailable = true;
                    }
                } else if (AgenticTravelPlanner.SAVE_WISH.equals(action.type)) {
                    db.addWish(action.destination, action.detail);
                } else if (AgenticTravelPlanner.CREATE_DEAL_WATCH.equals(action.type)) {
                    if (TravelDealGateway.isConfigured(context)) {
                        boolean created = db.createDefaultDealWatch(origin, action.destination);
                        createdWatch |= created;
                        if (created) {
                            String receipt = "fare watch from " + origin + " to " + action.destination;
                            if (alertsEnabled) durableReceipts.add(receipt);
                            else pendingReceipts.add(receipt + " (saved; automatic monitoring is off)");
                        }
                    } else {
                        monitoringUnavailable = true;
                    }
                } else if (AgenticTravelPlanner.UPDATE_DESTINATION_FOCUS.equals(action.type)) {
                    db.addMemory("trip_focus", action.destination + " trip priority: " + action.detail, action.detail);
                    db.addWish(action.destination, action.detail + " is the main reason for the trip");
                } else if (AgenticTravelPlanner.SET_FLEXIBLE_DATES.equals(action.type)) {
                    db.addMemory("travel_preference", "Travel dates are flexible", action.detail);
                    db.markDealWatchesFlexible(Collections.singletonList(action.destination));
                } else if (AgenticTravelPlanner.CREATE_EVENT_TRIP.equals(action.type)) {
                    boolean sourceRouteAvailable = KnownEventCatalog.find(action.detail) != null
                            || ("openai".equals(SarahModelConfig.PROVIDER_ID)
                                && SarahModelConfig.fullConversationAvailable());
                    boolean runnable = sourceRouteAvailable
                            && eventStore.ensureRunnableEventMonitor(action.detail, action.destination);
                    changedEventMonitor |= runnable;
                    if (runnable) {
                        durableReceipts.add("event monitor for " + action.detail + " in " + action.destination);
                    } else if (!sourceRouteAvailable) {
                        monitoringUnavailable = true;
                    }
                    if (isOwner(profile)) {
                        db.addMemory(
                                "event_trip",
                                "Plans to attend " + action.detail + " in " + action.destination,
                                action.detail + " | " + action.destination);
                    } else {
                        people.setTripParticipation(activeName(profile), action.destination, "going");
                        people.addMemory(
                                activeName(profile),
                                "event_trip",
                                "Plans to attend " + action.detail + " in " + action.destination,
                                action.detail + " | " + action.destination);
                    }
                } else if (AgenticTravelPlanner.SAVE_BOOKING_LINK.equals(action.type)) {
                    String[] detail = action.detail.split("\\|", 2);
                    String provider = detail.length > 0 ? detail[0] : "Other";
                    String bookingType = detail.length > 1 ? detail[1] : "travel";
                    importedBooking = eventStore.addBookingLink(
                            provider,
                            bookingType,
                            action.destination,
                            action.destination) > 0;
                } else if (AgenticTravelPlanner.SAVE_PLANNED_TRIP.equals(action.type)) {
                    PlannedTripDetail detail = PlannedTripDetail.parse(action.detail);
                    if (isOwner(profile)) {
                        if (!plannedTripExists(db.listTrips(100), action.destination, detail)) {
                            String dateNote = detail.hasDates()
                                    ? "Planned dates: " + detail.startDate + " through " + detail.endDate
                                    : "Dates not set; source: " + detail.label;
                            db.addTrip(
                                    detail.label + " trip to " + action.destination,
                                    action.destination,
                                    "planned",
                                    dateNote);
                        }
                    } else {
                        String name = activeName(profile);
                        people.setTripParticipation(name, action.destination, "going");
                        people.addMemory(
                                name,
                                "planned_trip",
                                "Plans to visit " + action.destination + " " + detail.label,
                                detail.hasDates()
                                        ? detail.startDate + " through " + detail.endDate
                                        : "Dates not set; source: " + detail.label);
                    }
                } else if (AgenticTravelPlanner.SAVE_JOURNEY_PLAN.equals(action.type)) {
                    JourneyDetail detail = JourneyDetail.parse(action.detail, origin);
                    long id = mobilityStore.saveJourneyPlan(
                            detail.origin,
                            action.destination,
                            detail.eventName,
                            detail.modes,
                            detail.purpose);
                    if (id > 0) {
                        if (isOwner(profile)) {
                            db.addMemory(
                                    "journey_plan",
                                    "Journey from " + detail.origin + " to " + action.destination
                                            + " using " + detail.modes.replace(',', '/'),
                                    action.detail);
                        } else {
                            people.addMemory(
                                    activeName(profile),
                                    "journey_plan",
                                    "Journey from " + detail.origin + " to " + action.destination
                                            + " using " + detail.modes.replace(',', '/'),
                                    action.detail);
                        }
                    }
                } else if (AgenticTravelPlanner.CREATE_MOBILITY_WATCH.equals(action.type)) {
                    JourneyDetail detail = JourneyDetail.parse(action.detail, origin);
                    if (MobilityGateway.isConfigured(context)) {
                        boolean created = mobilityStore.createWatch(
                                detail.origin,
                                action.destination,
                                detail.eventName,
                                detail.modes,
                                detail.purpose);
                        createdWatch |= created;
                        if (created) {
                            String receipt = "mobility watch from " + detail.origin
                                    + " to " + action.destination;
                            if (alertsEnabled) durableReceipts.add(receipt);
                            else pendingReceipts.add(receipt + " (saved; automatic monitoring is off)");
                        }
                    } else {
                        monitoringUnavailable = true;
                    }
                }
            }
        } finally {
            eventStore.close();
            mobilityStore.close();
            people.close();
        }

        boolean monitoringRunnable = BackgroundResearchPolicy.monitoringCanRun(
                alertsEnabled,
                TravelDealGateway.isConfigured(context) || MobilityGateway.isConfigured(context),
                createdWatch);
        if (monitoringRunnable) {
            DealWatchScheduler.ensureScheduled(context);
            DealWatchScheduler.runSoon(context);
        }
        if (changedEventMonitor || importedBooking) {
            EventMonitorScheduler.ensureScheduled(context);
            EventMonitorScheduler.runSoon(context);
        }
        return new Result(
                monitoringRunnable || changedEventMonitor,
                queuedKnowledge,
                changedEventMonitor,
                importedBooking,
                monitoringUnavailable,
                durableReceipts,
                pendingReceipts);
    }

    private static boolean isOwner(Map<String, String> profile) {
        return "yes".equalsIgnoreCase(profile.getOrDefault("active_speaker_is_owner", "no"))
                || "yes".equalsIgnoreCase(profile.getOrDefault("is_owner", "no"));
    }

    private static String activeName(Map<String, String> profile) {
        return profile.getOrDefault("name", profile.getOrDefault("active_speaker", "Guest"));
    }

    private static boolean plannedTripExists(
            List<Map<String, String>> trips,
            String destination,
            PlannedTripDetail detail) {
        for (Map<String, String> trip : trips) {
            if (!destination.equalsIgnoreCase(trip.getOrDefault("destination", ""))) continue;
            String notes = trip.getOrDefault("notes", "");
            if (notes.contains(detail.startDate) && notes.contains(detail.endDate)) return true;
        }
        return false;
    }

    private static final class PlannedTripDetail {
        final String startDate;
        final String endDate;
        final String label;

        PlannedTripDetail(String startDate, String endDate, String label) {
            this.startDate = startDate;
            this.endDate = endDate;
            this.label = label.isEmpty() ? "planned" : label;
        }

        static PlannedTripDetail parse(String encoded) {
            String[] parts = (encoded == null ? "" : encoded).split("\\|", -1);
            return new PlannedTripDetail(
                    parts.length > 0 ? parts[0].trim() : "",
                    parts.length > 1 ? parts[1].trim() : "",
                    parts.length > 2 ? parts[2].trim() : "planned");
        }

        boolean hasDates() {
            return !startDate.isEmpty() && !endDate.isEmpty();
        }
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
