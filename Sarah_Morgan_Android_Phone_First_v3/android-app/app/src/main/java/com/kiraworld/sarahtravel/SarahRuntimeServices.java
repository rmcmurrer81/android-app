package com.kiraworld.sarahtravel;

import android.content.Context;

import java.util.List;
import java.util.Map;

/**
 * Bridges the pure conversation layer to durable Android travel services
 * without forcing MainActivity to know every travel subsystem.
 */
public final class SarahRuntimeServices {
    private static volatile Context appContext;

    private SarahRuntimeServices() { }

    public static void install(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    public static String answerAndApply(
            String message,
            Map<String, String> profile,
            List<Map<String, String>> history,
            List<Map<String, String>> memories,
            List<Map<String, String>> wishes) {
        Context context = appContext;
        if (context == null) return null;

        SarahDatabase db = new SarahDatabase(context);
        try {
            AgenticTravelPlanner.Plan plan = AgenticTravelPlanner.plan(message, profile, history, memories);
            TravelAutomation.Result applied = TravelAutomation.apply(db, profile, message, history);

            if (applied.queuedKnowledge || applied.createdDealWatch || applied.learnedFlexibleDates
                    || !plan.actions.isEmpty()) {
                DealWatchScheduler.ensureScheduled(context);
                DealWatchScheduler.runSoon(context);
            }

            if (plan.handled()) return plan.reply;

            String agentic = AgenticTravelCore.answer(
                    message,
                    profile,
                    history,
                    memories,
                    wishes,
                    db.listKnowledgePacks(50),
                    db.listDealWatches(50));
            if (agentic != null && !agentic.trim().isEmpty()) return agentic;

            return DestinationPackResponder.answer(message, history, db.listKnowledgePacks(50));
        } finally {
            db.close();
        }
    }
}
