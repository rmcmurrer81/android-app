package com.kiraworld.sarahtravel;

import android.content.Context;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/** Explicit, per-item, local reminder scheduling. Android may deliver it late. */
public final class TravelReminderScheduler {
    static final String INPUT_PROFILE_ID = "calendar_profile_id";
    static final String INPUT_MESSAGE_ID = "calendar_message_id";
    static final String INPUT_TRIGGER = "calendar_trigger_instant";
    private static final String PREFIX = "sarah-owner-travel-reminder-";

    private TravelReminderScheduler() { }

    public static boolean schedule(
            Context context,
            String profileId,
            String messageId,
            long anchorEpochMillis,
            long leadMillis) {
        if (context == null || !DealNotificationManager.canNotify(context)
                || !ConfirmedOwnerLease.isExactActiveOwner(context, profileId)) return false;
        long now = System.currentTimeMillis();
        long triggerMillis = EmailCalendarPolicy.reminderTrigger(
                anchorEpochMillis, leadMillis, now);
        if (triggerMillis < 0L) return false;
        String trigger = Instant.ofEpochMilli(triggerMillis).toString();
        GmailTokenVault vault = new GmailTokenVault(context);
        if (!vault.setReminder(profileId, messageId, trigger, leadMillis, true, now)) {
            return false;
        }
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(TravelReminderWorker.class)
                .setInitialDelay(triggerMillis - now, TimeUnit.MILLISECONDS)
                .setInputData(new Data.Builder()
                        .putString(INPUT_PROFILE_ID, profileId)
                        .putString(INPUT_MESSAGE_ID, messageId)
                        .putString(INPUT_TRIGGER, trigger)
                        .build())
                .addTag(workName(profileId, messageId))
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                workName(profileId, messageId), ExistingWorkPolicy.REPLACE, request);
        return true;
    }

    public static void cancel(Context context, String profileId, String messageId) {
        if (context == null) return;
        WorkManager.getInstance(context.getApplicationContext())
                .cancelUniqueWork(workName(profileId, messageId));
        new GmailTokenVault(context).cancelReminder(profileId, messageId);
    }

    private static String workName(String profileId, String messageId) {
        return PREFIX + EventTripProfilePolicy.profileKey(profileId) + "-"
                + (messageId == null ? 0 : messageId.hashCode());
    }
}
