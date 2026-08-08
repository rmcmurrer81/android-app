package com.kiraworld.sarahtravel;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.Map;

/** Runs at most one exact, cancellable confirmed-owner discovery lease. */
public final class ProactiveDiscoveryJobService extends JobService {
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
                    ProactiveDiscoveryScheduler.EXTRA_SCHEDULE_TOKEN, 0L);
        }
    }

    private final Object runLock = new Object();
    private ActiveRun activeRun;
    private long nextGeneration;

    @Override
    public boolean onStartJob(JobParameters params) {
        if (params == null || params.getExtras().getLong(
                ProactiveDiscoveryScheduler.EXTRA_SCHEDULE_TOKEN, 0L) == 0L) {
            return false;
        }
        final ActiveRun[] holder = new ActiveRun[1];
        Thread worker = new Thread(
                () -> runExactJob(holder[0]),
                "Sarah-Proactive-Discovery");
        ActiveRun run;
        synchronized (runLock) {
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
        SarahDatabase db = new SarahDatabase(getApplicationContext());
        try {
            if (run == null || run.stopped || Thread.currentThread().isInterrupted()) return;
            ConfirmedOwnerLease ownerLease = ConfirmedOwnerLease.capture(
                    getApplicationContext());
            if (ownerLease == null) return;
            ownerLease.requireActive();
            Map<String, String> active = ownerLease.capturedProfile();
            active.put("active_speaker_is_owner", "yes");
            String personId = ownerLease.personId();

            ownerLease.requireActive();
            Map<String, String> owner = db.getProfile();
            active.put("interests", owner.getOrDefault("interests", ""));
            PersonProfileStore people = new PersonProfileStore(getApplicationContext());
            try {
                ownerLease.requireActive();
                String learned = people.interestSummary(
                        active.getOrDefault("name", ""), 20);
                if (!learned.isEmpty()) active.put("learned_interests", learned);
            } finally {
                people.close();
            }

            ownerLease.requireActive();
            boolean ownerOptIn = new SarahLocationStore(getApplicationContext())
                    .backgroundResearchEnabled(personId);
            boolean allowed = KnowledgePackSchedulingPolicy.canSchedule(
                            true,
                            "yes".equals(active.getOrDefault("memory_consent", "no")),
                            ConnectivityMonitor.hasValidatedInternet(getApplicationContext()),
                            SarahModelConfig.fullConversationAvailable(),
                            TavilyClient.configured(),
                            ownerOptIn)
                    && getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                        .getBoolean("web_search", true)
                    && SettingsActivity.getConversationMode(getApplicationContext())
                        != ConversationModePolicy.MODE_LOCAL_ONLY;
            if (allowed && !run.stopped && !Thread.currentThread().isInterrupted()) {
                ownerLease.requireActive();
                ProactiveDiscoveryCoordinator.refresh(
                        getApplicationContext(),
                        active,
                        db.listTrips(20),
                        "profile_opted_in_job_service",
                        ownerLease);
            }
        } catch (Exception failure) {
            retry = !Thread.currentThread().isInterrupted()
                    && ConfirmedOwnerLease.capture(getApplicationContext()) != null;
        } finally {
            db.close();
            boolean mayFinish;
            synchronized (runLock) {
                mayFinish = activeRun == run && !run.stopped;
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
                            ProactiveDiscoveryScheduler.EXTRA_SCHEDULE_TOKEN, 0L);
            if (run == null || params == null
                    || run.params.getJobId() != params.getJobId()
                    || run.scheduleToken == 0L
                    || run.scheduleToken != stoppingToken) return false;
            run.stopped = true;
            activeRun = null;
        }
        run.thread.interrupt();
        TavilyClient.cancel(run.thread);
        ConnectedModelGateway.cancel(run.thread);
        return true;
    }
}
