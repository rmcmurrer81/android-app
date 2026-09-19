package com.kiraworld.sarahtravel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** A proposal notification only; opening it cannot save an item or schedule a reminder. */
public final class EmailCandidateNotificationManager {
    static final String CHANNEL_ID = "sarah_email_travel_proposals";

    private EmailCandidateNotificationManager() { }

    public static void postProposal(Context context, String messageId, String subject) {
        if (context == null || !DealNotificationManager.canNotify(context)) return;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Sarah email travel suggestions",
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Owner review requests for read-only travel and event email candidates");
        manager.createNotificationChannel(channel);

        Intent review = new Intent(context, TravelCalendarActivity.class);
        review.putExtra(TravelCalendarActivity.EXTRA_MESSAGE_ID, cleanId(messageId));
        PendingIntent pending = PendingIntent.getActivity(
                context,
                cleanId(messageId).hashCode(),
                review,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String safeSubject = clean(subject, 160);
        String body = safeSubject.isEmpty()
                ? "I saw a possible trip or event in your connected email. Do you want me to remember it?"
                : "I saw “" + safeSubject + "” in your connected email. Do you want me to remember it?";
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Sarah found a travel or event candidate")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
        manager.notify(cleanId(messageId).hashCode(), notification);
    }

    private static String cleanId(String value) {
        String text = value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "");
        return text.length() <= 128 ? text : text.substring(0, 128);
    }

    private static String clean(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
