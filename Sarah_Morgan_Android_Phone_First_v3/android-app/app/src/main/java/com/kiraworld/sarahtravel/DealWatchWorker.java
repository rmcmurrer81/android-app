package com.kiraworld.sarahtravel;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;
import java.util.Map;

/** Runs destination-pack refreshes, fare checks, and multimodal journey checks. */
public final class DealWatchWorker extends JobService {
    private volatile Thread running;

    @Override
    public boolean onStartJob(JobParameters params) {
        running = new Thread(() -> {
            boolean retry = runAutomation(getApplicationContext());
            jobFinished(params, retry);
        }, "SarahTravelAutomation");
        running.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Thread thread = running;
        if (thread != null) thread.interrupt();
        return true;
    }

    private static boolean runAutomation(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE);
        SarahDatabase db = new SarahDatabase(context);
        boolean temporaryFailure = false;
        try {
            if (prefs.getBoolean("auto_destination_research", true)) {
                String key = SecureStore.loadApiKey(context);
                if (!key.isEmpty()) {
                    try {
                        DestinationKnowledgeCoordinator.refreshPending(
                                db,
                                prefs.getString("connected_provider", "openai"),
                                key,
                                prefs.getString("model", "gpt-5-mini"),
                                4);
                    } catch (Exception ignored) {
                        temporaryFailure = true;
                    }
                }
            }

            if (!prefs.getBoolean("deal_alerts_enabled", true)) return temporaryFailure;
            List<Map<String, String>> watches = db.listActiveDealWatches(100);
            for (Map<String, String> watch : watches) {
                long id = longValue(watch, "id", 0);
                try {
                    TravelDealResult result = TravelDealGateway.check(context, watch);
                    long now = System.currentTimeMillis();
                    if (!result.configured) {
                        db.updateDealWatchCheck(id, "backend_not_configured", now, 0,
                                watch.getOrDefault("currency", "USD"));
                        continue;
                    }
                    if (!result.found) {
                        db.updateDealWatchCheck(id, "checked_no_result", now, 0, result.currency);
                        continue;
                    }
                    if (result.isDeal && shouldNotify(watch, result)) {
                        DealNotificationManager.post(context, watch, result);
                        db.updateDealWatchCheck(id, "deal_notified", now, result.totalPrice, result.currency);
                    } else {
                        db.updateDealWatchCheck(id, "checked_no_new_deal", now, 0, result.currency);
                    }
                } catch (Exception ignored) {
                    temporaryFailure = true;
                    db.updateDealWatchCheck(id, "temporary_error", System.currentTimeMillis(), 0,
                            watch.getOrDefault("currency", "USD"));
                }
            }

            if (MobilityWatchCoordinator.run(context)) temporaryFailure = true;
        } finally {
            db.close();
        }
        return temporaryFailure;
    }

    private static boolean shouldNotify(Map<String, String> watch, TravelDealResult result) {
        if (!result.isDeal || result.totalPrice <= 0) return result.isDeal;
        double previous = doubleValue(watch, "last_notified_price", 0);
        return previous <= 0 || result.totalPrice <= previous * 0.98;
    }

    private static long longValue(Map<String, String> row, String key, long fallback) {
        try { return Long.parseLong(row.getOrDefault(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }

    private static double doubleValue(Map<String, String> row, String key, double fallback) {
        try { return Double.parseDouble(row.getOrDefault(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }
}
