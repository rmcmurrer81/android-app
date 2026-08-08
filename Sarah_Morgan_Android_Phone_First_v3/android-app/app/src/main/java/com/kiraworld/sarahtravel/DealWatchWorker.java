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
    private static final class ActiveRun {
        final JobParameters params;
        final Thread thread;
        final long generation;
        final long scheduleToken;
        volatile boolean stopped;

        ActiveRun(JobParameters params, Thread thread, long generation) {
            this.params = params;
            this.thread = thread;
            this.generation = generation;
            this.scheduleToken = params.getExtras().getLong(
                    DealWatchScheduler.EXTRA_SCHEDULE_TOKEN, 0L);
        }
    }

    private final Object runLock = new Object();
    private ActiveRun activeRun;
    private long nextGeneration;

    @Override
    public boolean onStartJob(JobParameters params) {
        if (params == null) return false;
        if (params.getExtras().getLong(
                DealWatchScheduler.EXTRA_SCHEDULE_TOKEN, 0L) == 0L) return false;
        final ActiveRun[] holder = new ActiveRun[1];
        Thread worker = new Thread(
                () -> runExactJob(holder[0]),
                "SarahTravelAutomation");
        ActiveRun run;
        synchronized (runLock) {
            // Periodic and run-soon jobs can arrive together. Never replace
            // the cancellation/finish identity of work already in flight.
            if (activeRun != null) return false;
            run = new ActiveRun(params, worker, ++nextGeneration);
            holder[0] = run;
            activeRun = run;
        }
        worker.start();
        return true;
    }

    private void runExactJob(ActiveRun run) {
        boolean retry = true;
        try {
            if (run == null || run.stopped || Thread.currentThread().isInterrupted()) return;
            retry = runAutomation(getApplicationContext());
        } finally {
            boolean mayFinish;
            synchronized (runLock) {
                mayFinish = run != null && activeRun == run && !run.stopped;
                if (activeRun == run) activeRun = null;
            }
            if (mayFinish) jobFinished(run.params, retry);
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        ActiveRun run;
        synchronized (runLock) {
            run = activeRun;
            long stoppingToken = params == null ? Long.MIN_VALUE
                    : params.getExtras().getLong(
                            DealWatchScheduler.EXTRA_SCHEDULE_TOKEN, 0L);
            // Binder may recreate JobParameters, so match the immutable job
            // ID plus the scheduler token captured by this exact generation.
            if (run == null || params == null
                    || run.params.getJobId() != params.getJobId()
                    || run.scheduleToken == 0L
                    || run.scheduleToken != stoppingToken) return false;
            run.stopped = true;
            activeRun = null;
        }
        run.thread.interrupt();
        TravelDealGateway.cancel(run.thread);
        MobilityGateway.cancel(run.thread);
        TavilyClient.cancel(run.thread);
        ConnectedModelGateway.cancel(run.thread);
        OfficialEventPageLookup.cancel(run.thread);
        return true;
    }

    private static boolean runAutomation(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE);
        SarahDatabase db = new SarahDatabase(context);
        boolean temporaryFailure = false;
        try {
            ConfirmedOwnerLease ownerLease = ConfirmedOwnerLease.capture(context);
            if (ownerLease == null) return false;
            ownerLease.requireActive();
            Map<String, String> active = ownerLease.capturedProfile();
            String personId = ownerLease.personId();
            boolean backgroundResearchAllowed = KnowledgePackSchedulingPolicy.canSchedule(
                    true,
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
                    ownerLease.requireActive();
                    DestinationKnowledgeCoordinator.refreshPending(
                            db,
                            KnowledgeProfileKey.forProfile(active),
                            SarahModelConfig.PROVIDER_ID,
                            key,
                            SarahModelConfig.MODEL_ID,
                            BackgroundResearchPolicy.MAX_PACKS_PER_RUN,
                            ownerLease);
                    ownerLease.requireActive();
                } catch (Exception failure) {
                    if (Thread.currentThread().isInterrupted()) return true;
                    if (!ownerLease.isActive()) return false;
                    Log.e(TAG, "Destination knowledge refresh failed; append-only attempt receipt retained", failure);
                    temporaryFailure = true;
                }
            }

            if (Thread.currentThread().isInterrupted()) return true;
            if (!ownerLease.isActive()) return false;

            boolean monitoringAllowed = BackgroundResearchPolicy.monitoringCanRun(
                    prefs.getBoolean(
                            "deal_alerts_enabled",
                            BackgroundResearchPolicy.DEFAULT_BACKGROUND_MONITORING_ENABLED),
                    TravelDealGateway.isConfigured(context)
                            || MobilityGateway.isConfigured(context),
                    true);
            if (!monitoringAllowed) return temporaryFailure;
            ownerLease.requireActive();
            List<Map<String, String>> watches = db.listActiveDealWatches(100);
            for (Map<String, String> watch : watches) {
                if (Thread.currentThread().isInterrupted()) return true;
                if (!ownerLease.isActive()) return false;
                long id = longValue(watch, "id", 0);
                try {
                    ownerLease.requireActive();
                    TravelDealResult result = TravelDealGateway.check(
                            context, watch, ownerLease);
                    ownerLease.requireActive();
                    long now = System.currentTimeMillis();
                    if (!result.configured) {
                        ownerLease.requireActive();
                        db.updateDealWatchCheck(id, "setup_required", now, 0,
                                watch.getOrDefault("currency", "USD"));
                        continue;
                    }
                    if (!result.found) {
                        ownerLease.requireActive();
                        db.updateDealWatchCheck(id, "checked_no_result", now, 0, result.currency);
                        continue;
                    }
                    if (result.isDeal && shouldNotify(watch, result)) {
                        ownerLease.requireActive();
                        DealNotificationManager.post(context, watch, result);
                        ownerLease.requireActive();
                        db.updateDealWatchCheck(id, "deal_notified", now, result.totalPrice, result.currency);
                    } else {
                        ownerLease.requireActive();
                        db.updateDealWatchCheck(id, "checked_no_new_deal", now, 0, result.currency);
                    }
                } catch (Exception failure) {
                    if (Thread.currentThread().isInterrupted()) return true;
                    if (!ownerLease.isActive()) return false;
                    temporaryFailure = true;
                    ownerLease.requireActive();
                    db.updateDealWatchCheck(id, "temporary_error", System.currentTimeMillis(), 0,
                            watch.getOrDefault("currency", "USD"));
                }
            }

            ownerLease.requireActive();
            if (MobilityWatchCoordinator.run(context, ownerLease)) temporaryFailure = true;
            if (!ownerLease.isActive()) return false;
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
