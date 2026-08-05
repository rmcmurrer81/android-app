package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Map;

public final class AgenticActionExecutor {
    public static final class Result {
        public final boolean createdDealWatch;
        public final boolean queuedKnowledge;

        Result(boolean createdDealWatch, boolean queuedKnowledge) {
            this.createdDealWatch = createdDealWatch;
            this.queuedKnowledge = queuedKnowledge;
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
        String origin = profile.getOrDefault("hometown", "Home area").trim();

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
            }
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
        return new Result(createdWatch, queuedKnowledge);
    }
}
