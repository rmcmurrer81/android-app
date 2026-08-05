package com.kiraworld.sarahtravel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

public final class EventNotificationManager {
    public static final String CHANNEL_ID = "sarah_event_updates";

    private EventNotificationManager() { }

    public static void createChannel(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Sarah event updates",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Verified schedule, venue, registration, transport, and nearby-place updates for monitored trips");
        manager.createNotificationChannel(channel);
    }

    public static void post(
            Context context,
            long eventTripId,
            String eventName,
            String title,
            String detail,
            String sourceUrl) {
        if (!DealNotificationManager.canNotify(context)) return;
        createChannel(context);
        Intent intent = sourceUrl == null || sourceUrl.trim().isEmpty()
                ? new Intent(context, MainActivity.class)
                : new Intent(Intent.ACTION_VIEW, Uri.parse(sourceUrl));
        PendingIntent pending = PendingIntent.getActivity(
                context,
                (int) eventTripId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String body = detail == null || detail.trim().isEmpty() ? title : detail;
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(eventName + ": " + title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_EVENT)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify((int) (eventTripId * 31 + title.hashCode()), notification);
    }
}
