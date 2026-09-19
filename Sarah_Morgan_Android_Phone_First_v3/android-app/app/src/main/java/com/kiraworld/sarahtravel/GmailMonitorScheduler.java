package com.kiraworld.sarahtravel;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Owner-controlled scheduler for bounded read-only travel-email checks. */
public final class GmailMonitorScheduler {
    public static final long CHECK_INTERVAL_HOURS = 6L;
    static final String INPUT_PROFILE_ID = "exact_profile_id";
    private static final String PREFIX = "sarah-gmail-readonly-monitor-";

    private GmailMonitorScheduler() { }

    public static void setEnabled(Context context, String profileId, boolean enabled) {
        String key = EventTripProfilePolicy.profileKey(profileId);
        if (context == null || key.isEmpty()) {
            throw new IllegalArgumentException("Exact profile required for Gmail monitoring");
        }
        if (enabled && !ConfirmedOwnerLease.isExactActiveOwner(context, profileId)) {
            throw new SecurityException("Exact active owner confirmation required");
        }
        GmailTokenVault vault = new GmailTokenVault(context);
        vault.setMonitoringEnabled(profileId, enabled);
        WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
        String workName = PREFIX + key;
        if (!enabled) {
            manager.cancelUniqueWork(workName);
            return;
        }
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                GmailTravelMonitorWorker.class,
                CHECK_INTERVAL_HOURS,
                TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInputData(new Data.Builder()
                        .putString(INPUT_PROFILE_ID, profileId)
                        .build())
                .setInitialDelay(1L, TimeUnit.HOURS)
                .addTag(workName)
                .build();
        manager.enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.UPDATE,
                request);
    }
}
