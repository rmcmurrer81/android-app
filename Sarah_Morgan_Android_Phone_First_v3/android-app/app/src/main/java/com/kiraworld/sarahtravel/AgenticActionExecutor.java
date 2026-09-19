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
        public final List<String> completedForegroundReceipts;
        public final List<String> failedForegroundReceipts;

        Result(
                boolean createdDealWatch,
                boolean queuedKnowledge,
                boolean changedEventMonitor,
                boolean importedBooking,
                boolean monitoringUnavailable,
                List<String> durableActionReceipts,
                List<String> pendingActionReceipts,
                List<String> completedForegroundReceipts,
                List<String> failedForegroundReceipts) {
            this.createdDealWatch = createdDealWatch;
            this.queuedKnowledge = queuedKnowledge;
            this.changedEventMonitor = changedEventMonitor;
            this.importedBooking = importedBooking;
            this.monitoringUnavailable = monitoringUnavailable;
            this.durableActionReceipts = new ArrayList<>(durableActionReceipts);
            this.pendingActionReceipts = new ArrayList<>(pendingActionReceipts);
            this.completedForegroundReceipts = new ArrayList<>(completedForegroundReceipts);
            this.failedForegroundReceipts = new ArrayList<>(failedForegroundReceipts);
        }

        public boolean hasDurableBackgroundWork() { return !durableActionReceipts.isEmpty(); }
        public String receiptSummary() { return String.join("; ", durableActionReceipts); }
        public boolean hasPendingRequests() { return !pendingActionReceipts.isEmpty(); }
        public String pendingSummary() { return String.join("; ", pendingActionReceipts); }
        public boolean hasCompletedForegroundAction() {
            return !completedForegroundReceipts.isEmpty();
        }
        public String completedForegroundSummary() {
            return String.join("; ", completedForegroundReceipts);
        }
        public boolean hasFailedForegroundAction() { return !failedForegroundReceipts.isEmpty(); }
        public String failedForegroundSummary() {
            return String.join("; ", failedForegroundReceipts);
        }
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
        boolean eventMonitorCancellationApplied = false;
        boolean enabledEventMonitorsRemain = false;
        List<String> durableReceipts = new ArrayList<>();
        List<String> pendingReceipts = new ArrayList<>();
        List<String> newGlobalWatchReceipts = new ArrayList<>();
        List<String> newEventMonitorReceipts = new ArrayList<>();
        List<String> existingEventMonitorReceipts = new ArrayList<>();
        List<String> completedForegroundReceipts = new ArrayList<>();
        List<String> failedForegroundReceipts = new ArrayList<>();
        boolean eventMonitorNeedsScheduling = false;
        String origin = profile.getOrDefault("hometown", "Home area").trim();
        String personId = profile.getOrDefault("person_id", "");
        ConfirmedOwnerLease ownerLease = ConfirmedOwnerLease.capture(context);
        EventTripStore eventStore = new EventTripStore(
                context.getApplicationContext(), personId);
        MobilityWatchStore mobilityStore = new MobilityWatchStore(context.getApplicationContext());
        PersonProfileStore people = new PersonProfileStore(context.getApplicationContext());
        SharedPreferences preferences = context.getSharedPreferences(
                SettingsActivity.PREFS,
                Context.MODE_PRIVATE);
        boolean alertsEnabled = preferences.getBoolean(
                "deal_alerts_enabled",
                BackgroundResearchPolicy.DEFAULT_BACKGROUND_MONITORING_ENABLED);
        boolean researchEnabled = new SarahLocationStore(context)
                .backgroundResearchEnabled(personId);
        boolean memoryConsent = "yes".equals(profile.getOrDefault("memory_consent", "no"));
        String knowledgeScope = KnowledgeProfileKey.forProfile(profile);

        try {
            for (AgenticTravelPlanner.Action action : actions) {
                if (AgenticGlobalActionPolicy.requiresExactConfirmedOwner(action.type)
                        && !isExactConfirmedOwner(ownerLease, profile)) {
                    failedForegroundReceipts.add(
                            AgenticGlobalActionPolicy.rejectedReceipt(
                                    action.type, action.destination));
                    continue;
                }
                if (AgenticTravelPlanner.QUEUE_KNOWLEDGE_PACK.equals(action.type)) {
                    boolean requestSaved = KnowledgePackSchedulingPolicy.canRequest(
                            memoryConsent, action.destination)
                            && db.queueKnowledgePack(
                                    knowledgeScope, action.destination, false);
                    if (!requestSaved) continue;
                    String receipt = "destination knowledge request for " + action.destination;
                    boolean canSchedule = KnowledgePackSchedulingPolicy.canSchedule(
                            isExactConfirmedOwner(ownerLease, profile),
                            memoryConsent,
                            validatedInternet,
                            SarahModelConfig.fullConversationAvailable(),
                            TavilyClient.configured(),
                            researchEnabled);
                    boolean scheduled = false;
                    boolean ownerLeaseRevokedBeforeScheduling = false;
                    boolean promotedBeforeScheduling = canSchedule
                            && db.markKnowledgePackScheduled(knowledgeScope, action.destination);
                    if (promotedBeforeScheduling
                            && isExactConfirmedOwner(ownerLease, profile)) {
                        boolean periodicAccepted = DealWatchScheduler.ensureScheduled(context);
                        boolean immediateAccepted = DealWatchScheduler.runSoon(context);
                        scheduled = periodicAccepted || immediateAccepted;
                        if (!scheduled) {
                            db.markKnowledgePackNotScheduled(knowledgeScope, action.destination);
                        }
                    } else if (promotedBeforeScheduling) {
                        ownerLeaseRevokedBeforeScheduling = true;
                        db.markKnowledgePackNotScheduled(
                                knowledgeScope, action.destination);
                    }
                    if (scheduled) {
                        queuedKnowledge = true;
                        durableReceipts.add(receipt);
                    } else {
                        pendingReceipts.add(receipt
                                + (ownerLeaseRevokedBeforeScheduling
                                    ? " (saved; exact confirmed owner lease changed before scheduling)"
                                    : " (saved, not scheduled)"));
                        monitoringUnavailable = true;
                    }
                } else if (AgenticTravelPlanner.SAVE_WISH.equals(action.type)) {
                    if (memoryConsent) db.addWish(action.destination, action.detail);
                } else if (AgenticTravelPlanner.CREATE_DEAL_WATCH.equals(action.type)) {
                    if (TravelDealGateway.isConfigured(context)) {
                        boolean created = db.createDefaultDealWatch(origin, action.destination);
                        createdWatch |= created;
                        if (created) {
                            String receipt = "fare watch from " + origin + " to " + action.destination;
                            if (alertsEnabled) newGlobalWatchReceipts.add(receipt);
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
                } else if (AgenticTravelPlanner.CANCEL_EVENT_MONITOR.equals(action.type)) {
                    EventTripStore.MonitorDisableResult stop =
                            eventStore.resolveAndDisableEventMonitor(
                                    action.detail, action.destination);
                    if (stop.disabled) {
                        eventMonitorCancellationApplied =
                                isExactConfirmedOwner(ownerLease, profile);
                        completedForegroundReceipts.add("Monitoring is now off for "
                                + stop.eventName + " in " + stop.destination
                                + ". The saved event trip remains.");
                    } else if (stop.ambiguous) {
                        failedForegroundReceipts.add("More than one active "
                                + action.detail + " monitor exists for this profile. Please name the destination; no monitor setting changed.");
                    } else {
                        failedForegroundReceipts.add("I did not find an active "
                                + action.detail + " monitor for this profile, so no monitor setting changed.");
                    }
                } else if (AgenticTravelPlanner.CREATE_EVENT_TRIP.equals(action.type)) {
                    boolean sourceRouteAvailable = KnownEventCatalog.find(action.detail) != null
                            || TavilyClient.configured();
                    // The owner's exact event and destination are enough to save a
                    // non-monitored trip. Current-source availability gates only
                    // monitoring and later factual refreshes.
                    boolean staticSaved = eventStore.upsertEventTrip(
                            action.detail, action.destination, false) > 0;
                    boolean monitoringAuthorized = EventTripMonitoringPolicy.canEnable(
                            action.monitoringRequested,
                            isExactConfirmedOwner(ownerLease, profile),
                            alertsEnabled,
                            sourceRouteAvailable);
                    boolean runnable = staticSaved
                            && monitoringAuthorized
                            && eventStore.ensureRunnableEventMonitor(action.detail, action.destination);
                    boolean monitorStillEnabled = staticSaved
                            && eventStore.eventMonitorEnabled(action.detail, action.destination);
                    changedEventMonitor |= runnable;
                    if (runnable) {
                        eventMonitorNeedsScheduling = true;
                        newEventMonitorReceipts.add(
                                "event monitor for " + action.detail + " in " + action.destination);
                    } else if (monitorStillEnabled) {
                        String existing = "existing event monitor for " + action.detail
                                + " in " + action.destination;
                        if (alertsEnabled && sourceRouteAvailable
                                && isExactConfirmedOwner(ownerLease, profile)) {
                            eventMonitorNeedsScheduling = true;
                            existingEventMonitorReceipts.add(existing);
                        } else {
                            pendingReceipts.add(existing
                                    + " (setting preserved; exact confirmed owner scheduling is not available)");
                        }
                    } else if (staticSaved) {
                        pendingReceipts.add("event trip for " + action.detail + " in "
                                + action.destination + " (saved; automatic monitoring is off)");
                    }
                    if (action.monitoringRequested && !sourceRouteAvailable) {
                        monitoringUnavailable = true;
                    }
                    if (staticSaved && memoryConsent
                            && isExactConfirmedOwner(ownerLease, profile)) {
                        db.addMemory(
                                "event_trip",
                                "Plans to attend " + action.detail + " in " + action.destination,
                                action.detail + " | " + action.destination);
                    } else if (staticSaved && memoryConsent) {
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
                    if (isExactConfirmedOwner(ownerLease, profile)) {
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
                        db.addMemory(
                                "journey_plan",
                                "Journey from " + detail.origin + " to " + action.destination
                                        + " using " + detail.modes.replace(',', '/'),
                                action.detail);
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
                            if (alertsEnabled) newGlobalWatchReceipts.add(receipt);
                            else pendingReceipts.add(receipt + " (saved; automatic monitoring is off)");
                        }
                    } else {
                        monitoringUnavailable = true;
                    }
                }
            }
            enabledEventMonitorsRemain = eventStore.hasEnabledEventMonitors();
        } finally {
            eventStore.close();
            mobilityStore.close();
            people.close();
        }

        boolean monitoringRunnable = BackgroundResearchPolicy.monitoringCanRun(
                alertsEnabled,
                TravelDealGateway.isConfigured(context) || MobilityGateway.isConfigured(context),
                createdWatch);
        boolean exactOwnerAtSchedulerBoundary =
                isExactConfirmedOwner(ownerLease, profile);
        boolean globalWatchSchedulerAccepted = false;
        if (monitoringRunnable && exactOwnerAtSchedulerBoundary) {
            boolean periodicAccepted = DealWatchScheduler.ensureScheduled(context);
            boolean immediateAccepted = DealWatchScheduler.runSoon(context);
            globalWatchSchedulerAccepted = periodicAccepted;
            if (periodicAccepted) {
                durableReceipts.addAll(newGlobalWatchReceipts);
            } else {
                monitoringUnavailable = true;
                for (String receipt : newGlobalWatchReceipts) {
                    pendingReceipts.add(receipt
                            + (immediateAccepted
                                ? " (one refresh was scheduled; durable Android monitoring was rejected)"
                                : " (saved; Android scheduler rejected the job)"));
                }
            }
        } else if (monitoringRunnable) {
            monitoringUnavailable = true;
            for (String receipt : newGlobalWatchReceipts) {
                pendingReceipts.add(receipt
                        + " (saved; exact confirmed owner lease changed before scheduling)");
            }
        }
        boolean eventSchedulerAccepted = false;
        if (exactOwnerAtSchedulerBoundary
                && eventMonitorCancellationApplied && !enabledEventMonitorsRemain
                && !eventMonitorNeedsScheduling) {
            EventMonitorScheduler.cancelPeriodicMonitoring(context);
        }
        if (eventMonitorNeedsScheduling && exactOwnerAtSchedulerBoundary) {
            boolean periodicAccepted = EventMonitorScheduler.ensureScheduled(context);
            boolean immediateAccepted = EventMonitorScheduler.runSoon(context);
            // A one-shot refresh is not a durable monitor. Only the accepted
            // persisted periodic job can support a durable-monitor receipt.
            eventSchedulerAccepted = periodicAccepted;
            if (eventSchedulerAccepted) {
                durableReceipts.addAll(newEventMonitorReceipts);
                durableReceipts.addAll(existingEventMonitorReceipts);
            } else {
                monitoringUnavailable = true;
                for (String receipt : newEventMonitorReceipts) {
                    pendingReceipts.add(receipt
                            + (immediateAccepted
                                ? " (one refresh was scheduled; durable Android monitoring was rejected)"
                                : " (enabled in saved data; Android scheduler rejected the job)"));
                }
                for (String receipt : existingEventMonitorReceipts) {
                    pendingReceipts.add(receipt
                            + (immediateAccepted
                                ? " (one refresh was scheduled; durable Android monitoring remains pending)"
                                : " (setting preserved; Android scheduler rejected the job)"));
                }
            }
        } else if (eventMonitorNeedsScheduling) {
            monitoringUnavailable = true;
            for (String receipt : newEventMonitorReceipts) {
                pendingReceipts.add(receipt
                        + " (saved setting; exact confirmed owner lease changed before scheduling)");
            }
            for (String receipt : existingEventMonitorReceipts) {
                pendingReceipts.add(receipt
                        + " (setting preserved; exact confirmed owner lease changed before scheduling)");
            }
        } else if (importedBooking && exactOwnerAtSchedulerBoundary) {
            EventMonitorScheduler.runSoon(context);
        }
        return new Result(
                globalWatchSchedulerAccepted || eventSchedulerAccepted,
                queuedKnowledge,
                changedEventMonitor && eventSchedulerAccepted,
                importedBooking,
                monitoringUnavailable,
                durableReceipts,
                pendingReceipts,
                completedForegroundReceipts,
                failedForegroundReceipts);
    }

    private static boolean isExactConfirmedOwner(
            ConfirmedOwnerLease ownerLease,
            Map<String, String> profile) {
        if (ownerLease == null || profile == null) return false;
        try {
            ownerLease.requireActive();
        } catch (IllegalStateException e) {
            return false;
        }
        Map<String, String> captured = ownerLease.capturedProfile();
        String profileId = profile.getOrDefault("person_id", "").trim();
        String profileName = profile.getOrDefault("name", "").trim();
        return !profileId.isEmpty()
                && profileId.equals(ownerLease.personId())
                && profileName.equals(captured.getOrDefault("name", "").trim())
                && "yes".equals(profile.getOrDefault("active_speaker_is_owner", "no"))
                && "yes".equals(profile.getOrDefault("is_owner", "no"));
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
