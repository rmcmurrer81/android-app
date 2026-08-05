package com.kiraworld.sarahtravel;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import java.util.Map;

public final class DealNotificationManager {
    public static final String CHANNEL_ID = "sarah_travel_deals";

    private DealNotificationManager() { }

    public static void createChannel(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Sarah travel deals",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Fare opportunities and related weather context from Sarah's configured travel service");
        manager.createNotificationChannel(channel);
    }

    public static void requestPermissionIfNeeded(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, requestCode);
        }
    }

    public static boolean canNotify(Context context) {
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    public static void post(Context context, Map<String, String> watch, TravelDealResult result) {
        if (!canNotify(context)) return;
        createChannel(context);
        Intent intent;
        if (!result.bookingUrl.isEmpty()) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(result.bookingUrl));
        } else {
            intent = new Intent(context, MainActivity.class);
        }
        PendingIntent pending = PendingIntent.getActivity(
                context,
                (int) longValue(watch, "id", System.currentTimeMillis()),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String destination = watch.getOrDefault("destination", "your destination");
        String body = result.notificationText();
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(result.notificationTitle(destination))
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify((int) longValue(watch, "id", System.currentTimeMillis()), notification);
    }

    private static long longValue(Map<String, String> row, String key, long fallback) {
        try { return Long.parseLong(row.getOrDefault(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }
}
