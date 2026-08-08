package com.kiraworld.sarahtravel;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.List;
import java.util.Map;

/** Runs destination-pack refreshes, fare checks, and multimodal journey checks. */
public final class DealWatchWorker extends JobService {
    private static final String TAG = "SarahTravelAutomation";
    private volatile Thread running;
    private volatile boolean stopped;

    @Override
    public boolean onStartJob(JobParameters params) {
        stopped = false;
        running = new Thread(() -> {
            boolean retry = true;
            try {
                retry = runAutomation(getApplicationContext());
            } finally {
                if (!stopped) jobFinished(params, retry);
                running = null;
            }
        }, "SarahTravelAutomation");
        running.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopped = true;
        Thread thread = running;
        if (thread != null) {
            thread.interrupt();
            TavilyClient.cancel(thread);
        }
        return true;
    }

    private static boolean runAutomation(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE);
        SarahDatabase db = new SarahDatabase(context);
        PersonProfileStore people = new PersonProfileStore(context);
        boolean temporaryFailure = false;
        try {
            Map<String, String> owner = db.getProfile();
            people.ensureOwner(owner);
            Map<String, String> active = people.getActiveProfile();
            if (active.isEmpty()) active = owner;
            String personId = active.getOrDefault(
                    "person_id", active.getOrDefault("name", "unknown_profile"));
            boolean backgroundResearchAllowed = KnowledgePackSchedulingPolicy.canSchedule(
                    "yes".equals(active.getOrDefault("is_owner", "no")),
                    "yes".equals(active.getOrDefault("memory_consent", "no")),
                    ConnectivityMonitor.hasValidatedInternet(context),
                    SarahModelConfig.fullConversationAvailable(),
                    TavilyClient.configured(),
                    new SarahLocationStore(context).backgroundResearchEnabled(personId))
                    && prefs.getBoolean("web_search", true)
                    && SettingsActivity.getConversationMode(context)
                        != ConversationModePolicy.MODE_LOCAL_ONLY;
            if (backgroundResearchAllowed) {
                String key = SecureStore.loadApiKey(context);
                try {
                    DestinationKnowledgeCoordinator.refreshPending(
                            db,
                            KnowledgeProfileKey.forProfile(active),
                            SarahModelConfig.PROVIDER_ID,
                            key,
                            SarahModelConfig.MODEL_ID,
                            BackgroundResearchPolicy.MAX_PACKS_PER_RUN);
                } catch (Exception failure) {
                    Log.e(TAG, "Destination knowledge refresh failed; append-only attempt receipt retained", failure);
                    temporaryFailure = true;
                }
            }

            if (Thread.currentThread().isInterrupted()) return true;

            boolean monitoringAllowed = BackgroundResearchPolicy.monitoringCanRun(
                    prefs.getBoolean(
                            "deal_alerts_enabled",
                            BackgroundResearchPolicy.DEFAULT_BACKGROUND_MONITORING_ENABLED),
                    TravelDealGateway.isConfigured(context)
                            || MobilityGateway.isConfigured(context),
                    true);
            if (!monitoringAllowed) return temporaryFailure;
            List<Map<String, String>> watches = db.listActiveDealWatches(100);
            for (Map<String, String> watch : watches) {
                if (Thread.currentThread().isInterrupted()) return true;
                long id = longValue(watch, "id", 0);
                try {
                    TravelDealResult result = TravelDealGateway.check(context, watch);
                    long now = System.currentTimeMillis();
                    if (!result.configured) {
                        db.updateDealWatchCheck(id, "setup_required", now, 0,
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
            people.close();
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
