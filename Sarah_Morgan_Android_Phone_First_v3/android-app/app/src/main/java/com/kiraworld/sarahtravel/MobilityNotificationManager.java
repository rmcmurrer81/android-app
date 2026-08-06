package com.kiraworld.sarahtravel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.content.pm.PackageManager;
import android.Manifest;

import java.util.Map;

public final class MobilityNotificationManager {
    public static final String CHANNEL_ID = "sarah_mobility_updates";

    private MobilityNotificationManager() { }

    public static void createChannel(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Sarah journey updates",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Rail, transit, bus, driving, ferry, and mixed-route updates from Sarah's configured travel service");
        manager.createNotificationChannel(channel);
    }

    public static boolean canNotify(Context context) {
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    public static void post(Context context, Map<String, String> watch, MobilityResult result) {
        if (!canNotify(context) || result == null || !result.significant) return;
        createChannel(context);
        Intent intent = result.actionUrl.isEmpty()
                ? new Intent(context, MainActivity.class)
                : new Intent(Intent.ACTION_VIEW, Uri.parse(result.actionUrl));
        PendingIntent pending = PendingIntent.getActivity(
                context,
                (int) longValue(watch, "id", System.currentTimeMillis()),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String route = watch.getOrDefault("origin", "Origin") + " → "
                + watch.getOrDefault("destination", "Destination");
        String title = result.recommendedMode.isEmpty()
                ? "Sarah found a journey update"
                : "Sarah found a " + result.recommendedMode.replace('_', ' ') + " option";
        String body = result.summary.isEmpty() ? route : result.summary;
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body + "\n" + route))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .build();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(50000 + (int) longValue(watch, "id", 0), notification);
    }

    private static long longValue(Map<String, String> row, String key, long fallback) {
        try { return Long.parseLong(row.getOrDefault(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }
}
