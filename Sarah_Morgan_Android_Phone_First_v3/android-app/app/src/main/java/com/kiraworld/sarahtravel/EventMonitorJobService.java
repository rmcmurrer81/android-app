package com.kiraworld.sarahtravel;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.SharedPreferences;

/** Runs at most one exact event/booking refresh lease at a time. */
public final class EventMonitorJobService extends JobService {
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
                    EventMonitorScheduler.EXTRA_SCHEDULE_TOKEN, 0L);
        }
    }

    private final Object runLock = new Object();
    private ActiveRun activeRun;
    private long nextGeneration;

    @Override
    public boolean onStartJob(JobParameters params) {
        if (params == null || params.getExtras().getLong(
                EventMonitorScheduler.EXTRA_SCHEDULE_TOKEN, 0L) == 0L) {
            return false;
        }
        final ActiveRun[] holder = new ActiveRun[1];
        Thread worker = new Thread(() -> runExactJob(holder[0]), "SarahEventMonitor");
        ActiveRun run;
        synchronized (runLock) {
            // A periodic and immediate job may arrive together. Never replace
            // the ownership token of work that is already in flight.
            if (activeRun != null) return false;
            run = new ActiveRun(params, worker, ++nextGeneration);
            holder[0] = run;
            activeRun = run;
        }
        worker.start();
        return true;
    }

    private void runExactJob(ActiveRun run) {
        boolean retry = false;
        EventTripStore store = null;
        try {
            if (run == null || run.stopped || Thread.currentThread().isInterrupted()) return;
            ConfirmedOwnerLease ownerLease = ConfirmedOwnerLease.capture(
                    getApplicationContext());
            if (ownerLease == null) return;
            ownerLease.requireActive();
            String personId = ownerLease.personId();
            if (EventTripProfilePolicy.profileKey(personId).isEmpty()) return;

            ownerLease.requireActive();
            store = new EventTripStore(getApplicationContext(), personId);
            if (run.stopped || !store.isActiveProfile()
                    || Thread.currentThread().isInterrupted()) return;

            SharedPreferences preferences = getSharedPreferences(
                    SettingsActivity.PREFS,
                    MODE_PRIVATE);
            String providerId = preferences.getString(
                    "connected_provider", SarahModelConfig.PROVIDER_ID);
            String model = preferences.getString("model", SarahModelConfig.MODEL_ID);
            String apiKey = SecureStore.loadApiKey(this);
            boolean monitoringEnabled = preferences.getBoolean(
                    "deal_alerts_enabled",
                    BackgroundResearchPolicy.DEFAULT_BACKGROUND_MONITORING_ENABLED);
            if (EventTripMonitoringPolicy.leaseStillValid(
                    personId,
                    EventTripStore.activePersonId(this),
                    true,
                    monitoringEnabled,
                    run.stopped || Thread.currentThread().isInterrupted())) {
                ownerLease.requireActive();
                EventResearchCoordinator.refreshDue(
                        getApplicationContext(),
                        store,
                        providerId,
                        apiKey,
                        model,
                        8,
                        ownerLease);
            }
            if (!run.stopped && store.isActiveProfile()
                    && !Thread.currentThread().isInterrupted()) {
                ownerLease.requireActive();
                BookingExtractionCoordinator.refreshPending(
                        store,
                        providerId,
                        apiKey,
                        model,
                        6,
                        ownerLease);
            }
        } catch (Exception failure) {
            // Periodic jobs receive their next normal cadence without an
            // immediate retry loop. App startup re-enqueues eligible work.
            retry = false;
        } finally {
            if (store != null) store.close();
            boolean mayFinish;
            synchronized (runLock) {
                mayFinish = activeRun == run && !run.stopped;
                if (activeRun == run) activeRun = null;
            }
            // Android owns job completion after onStopJob. A stopped or stale
            // worker must never complete a newer job by mistake.
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
                            EventMonitorScheduler.EXTRA_SCHEDULE_TOKEN, 0L);
            // Match the Android job plus the scheduler token. The captured
            // service generation keeps cancellation bound to this exact run.
            if (run == null || params == null
                    || run.params.getJobId() != params.getJobId()
                    || run.scheduleToken == 0L
                    || run.scheduleToken != stoppingToken) return false;
            run.stopped = true;
            activeRun = null;
        }
        run.thread.interrupt();
        ConnectedModelGateway.cancel(run.thread);
        TavilyClient.cancel(run.thread);
        OfficialEventPageLookup.cancel(run.thread);
        return false;
    }
}
