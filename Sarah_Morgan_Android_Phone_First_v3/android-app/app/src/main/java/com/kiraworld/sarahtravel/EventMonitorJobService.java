package com.kiraworld.sarahtravel;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.SharedPreferences;

public final class EventMonitorJobService extends JobService {
    private volatile Thread worker;

    @Override
    public boolean onStartJob(JobParameters params) {
        worker = new Thread(() -> {
            boolean retry = false;
            EventTripStore store = new EventTripStore(getApplicationContext());
            try {
                SharedPreferences preferences = getSharedPreferences(
                        SettingsActivity.PREFS,
                        MODE_PRIVATE);
                String providerId = preferences.getString("connected_provider", SarahModelConfig.PROVIDER_ID);
                String model = preferences.getString("model", SarahModelConfig.MODEL_ID);
                String apiKey = SecureStore.loadApiKey(this);
                EventResearchCoordinator.refreshDue(
                        getApplicationContext(),
                        store,
                        providerId,
                        apiKey,
                        model,
                        8);
                BookingExtractionCoordinator.refreshPending(
                        store,
                        providerId,
                        apiKey,
                        model,
                        6);
            } catch (Exception ignored) {
                retry = true;
            } finally {
                store.close();
                jobFinished(params, retry);
            }
        }, "SarahEventMonitor");
        worker.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Thread current = worker;
        if (current != null) current.interrupt();
        return true;
    }
}
