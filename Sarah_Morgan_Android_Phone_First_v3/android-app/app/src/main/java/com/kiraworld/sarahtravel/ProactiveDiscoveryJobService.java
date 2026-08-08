package com.kiraworld.sarahtravel;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.Map;

/** Profile-gated, cancellable periodic discovery work. */
public final class ProactiveDiscoveryJobService extends JobService {
    private volatile Thread running;
    private volatile boolean stopped;

    @Override
    public boolean onStartJob(JobParameters params) {
        stopped = false;
        running = new Thread(() -> runBounded(params), "Sarah-Proactive-Discovery");
        running.start();
        return true;
    }

    private void runBounded(JobParameters params) {
        boolean retry = false;
        SarahDatabase db = new SarahDatabase(getApplicationContext());
        PersonProfileStore people = new PersonProfileStore(getApplicationContext());
        try {
            Map<String, String> owner = db.getProfile();
            people.ensureOwner(owner);
            Map<String, String> active = people.getActiveProfile();
            if (active.isEmpty()) active = owner;
            boolean activeIsOwner = "yes".equals(active.getOrDefault("is_owner", "no"));
            active.put("active_speaker_is_owner", activeIsOwner ? "yes" : "no");
            if (activeIsOwner) {
                active.put("interests", owner.getOrDefault("interests", ""));
                String learned = people.interestSummary(active.getOrDefault("name", ""), 20);
                if (!learned.isEmpty()) active.put("learned_interests", learned);
            }
            String personId = active.getOrDefault(
                    "person_id", active.getOrDefault("name", "unknown_profile"));
            boolean ownerOptIn = new SarahLocationStore(getApplicationContext())
                    .backgroundResearchEnabled(personId);
            boolean allowed = KnowledgePackSchedulingPolicy.canSchedule(
                            activeIsOwner,
                            "yes".equals(active.getOrDefault("memory_consent", "no")),
                            ConnectivityMonitor.hasValidatedInternet(getApplicationContext()),
                            SarahModelConfig.fullConversationAvailable(),
                            TavilyClient.configured(),
                            ownerOptIn)
                    && getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE)
                        .getBoolean("web_search", true)
                    && SettingsActivity.getConversationMode(getApplicationContext())
                        != ConversationModePolicy.MODE_LOCAL_ONLY;
            if (allowed && !Thread.currentThread().isInterrupted()) {
                ProactiveDiscoveryCoordinator.refresh(
                        getApplicationContext(), active, db.listTrips(20),
                        "profile_opted_in_job_service");
            }
        } catch (Exception failure) {
            retry = !Thread.currentThread().isInterrupted();
        } finally {
            people.close();
            db.close();
            if (!stopped) jobFinished(params, retry);
            running = null;
        }
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
}
