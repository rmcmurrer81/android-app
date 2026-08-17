package com.kiraworld.sarahtravel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

/** Delivers only the exact still-scheduled owner reminder stored in the encrypted vault. */
public final class TravelReminderWorker extends Worker {
    private static final String CHANNEL_ID = "sarah_owner_travel_reminders";

    public TravelReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        String profileId = value(getInputData().getString(
                TravelReminderScheduler.INPUT_PROFILE_ID));
        String messageId = value(getInputData().getString(
                TravelReminderScheduler.INPUT_MESSAGE_ID));
        String trigger = EmailCalendarPolicy.normalizedInstant(getInputData().getString(
                TravelReminderScheduler.INPUT_TRIGGER));
        if (profileId.isEmpty() || messageId.isEmpty() || trigger.isEmpty()) return Result.success();
        GmailTokenVault vault = new GmailTokenVault(getApplicationContext());
        JSONObject item = vault.receipt(profileId, messageId);
        if (item == null
                || !EmailCalendarPolicy.CALENDAR_SAVED.equals(
                        item.optString("calendar_item_state", ""))
                || !EmailCalendarPolicy.REMINDER_SCHEDULED.equals(
                        item.optString("reminder_state", ""))
                || !trigger.equals(item.optString("reminder_trigger_instant", ""))) {
            return Result.success();
        }
        if (!post(item, messageId)) {
            vault.markReminderBlocked(profileId, messageId, trigger, System.currentTimeMillis());
            return Result.success();
        }
        vault.markReminderDelivered(profileId, messageId, trigger, System.currentTimeMillis());
        return Result.success();
    }

    private boolean post(JSONObject item, String messageId) {
        Context context = getApplicationContext();
        if (!DealNotificationManager.canNotify(context)) return false;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Sarah trip and event reminders",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Local reminders explicitly requested for exact saved calendar items");
        manager.createNotificationChannel(channel);
        Intent open = new Intent(context, TravelCalendarActivity.class);
        open.putExtra(TravelCalendarActivity.EXTRA_MESSAGE_ID, messageId);
        PendingIntent pending = PendingIntent.getActivity(
                context,
                messageId.hashCode(),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = item.optString("subject", "Saved trip or event");
        String when = item.optString("calendar_start_instant", "");
        String body = "Upcoming: " + title + (when.isEmpty() ? "" : " at " + when);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Sarah reminder")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
        manager.notify(messageId.hashCode() ^ 0x53415241, notification);
        return true;
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
