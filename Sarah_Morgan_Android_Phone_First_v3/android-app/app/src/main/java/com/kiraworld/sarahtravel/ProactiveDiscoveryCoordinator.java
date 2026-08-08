package com.kiraworld.sarahtravel;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProactiveDiscoveryCoordinator {
    private static final String CHANNEL = "sarah_discoveries";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private ProactiveDiscoveryCoordinator() { }

    public static String availabilityStatus(
            Context context,
            Map<String, String> profile,
            List<Map<String, String>> trips) {
        SharedPreferences preferences = context.getSharedPreferences(
                SettingsActivity.PREFS, Context.MODE_PRIVATE);
        String personId = profile.getOrDefault(
                "person_id", profile.getOrDefault("name", "unknown_profile"));
        SarahLocationStore locations = new SarahLocationStore(context);
        if (!preferences.getBoolean("web_search", true)) {
            return "Allow current public research in Settings first";
        }
        if (!locations.backgroundResearchEnabled(personId)) {
            return "Turn on automatic destination research in Settings first";
        }
        if (SettingsActivity.getConversationMode(context)
                == ConversationModePolicy.MODE_LOCAL_ONLY) {
            return "Connected research is off in local-only mode";
        }
        if (!hasValidatedInternet(context)) {
            return "Internet is needed before this research can start";
        }
        if (!TavilyClient.configured()) {
            return "Current-source research is not connected in this build";
        }
        if (!"yes".equals(profile.getOrDefault("active_speaker_is_owner", "no"))) {
            return "Automatic research is available only to the active owner profile";
        }
        if (!"yes".equals(profile.getOrDefault("memory_consent", "no"))) {
            return "Allow travel memory before saving research to this profile";
        }
        if (plans(profile, trips, locations, personId).isEmpty()) {
            return "Add a trip destination or approve a nearby area first";
        }
        return "ready";
    }

    public static int refresh(
            Context context,
            Map<String, String> profile,
            List<Map<String, String>> trips) throws Exception {
        return refresh(context, profile, trips, "profile_opted_in_scheduled");
    }

    public static int refresh(
            Context context,
            Map<String, String> profile,
            List<Map<String, String>> trips,
            String trigger) throws Exception {
        if (!"ready".equals(availabilityStatus(context, profile, trips))) return 0;
        if (!RUNNING.compareAndSet(false, true)) return 0;

        String personId = profile.getOrDefault(
                "person_id", profile.getOrDefault("name", "unknown_profile"));
        String speaker = profile.getOrDefault("name", "Traveler");
        SarahLocationStore locations = new SarahLocationStore(context);
        List<AdaptiveResearchPlan.Query> plans = plans(
                profile, trips, locations, personId);
        long startedAt = System.currentTimeMillis();
        int sourceCount = 0;
        int savedCount = 0;
        ProactiveResearchReceiptStore.started(
                context, personId, trigger, plans.size(), startedAt);
        try {
            ProactiveDiscoveryStore store = new ProactiveDiscoveryStore(context);
            try {
                store.claimLegacyProfile(personId, speaker);
                for (AdaptiveResearchPlan.Query plan : plans) {
                    requireActiveLease(context, personId);
                    List<TavilyClient.Result> results = TavilyClient.search(
                            plan.text,
                            BackgroundResearchPolicy.MAX_DISCOVERIES_PER_QUERY,
                            () -> !activeLease(context, personId));
                    requireActiveLease(context, personId);
                    sourceCount += results.size();
                    for (TavilyClient.Result result : results) {
                        requireActiveLease(context, personId);
                        if (store.add(
                                personId, speaker, result, plan.text, plan.category)) {
                            savedCount++;
                        }
                    }
                }
            } finally {
                store.close();
            }
            requireActiveLease(context, personId);
            ProactiveResearchReceiptStore.succeeded(
                    context, personId, trigger, plans.size(), sourceCount, savedCount,
                    startedAt, System.currentTimeMillis());
            requireActiveLease(context, personId);
            if (savedCount > 0) notify(context, speaker, savedCount);
            return savedCount;
        } catch (Exception failure) {
            ProactiveResearchReceiptStore.failed(
                    context, personId, trigger, plans.size(), sourceCount, savedCount,
                    startedAt, System.currentTimeMillis(), failure);
            throw failure;
        } finally {
            RUNNING.set(false);
        }
    }

    private static List<AdaptiveResearchPlan.Query> plans(
            Map<String, String> profile,
            List<Map<String, String>> trips,
            SarahLocationStore locations,
            String personId) {
        String destination = "";
        if (trips != null && !trips.isEmpty()) {
            destination = trips.get(0).getOrDefault("destination", "");
        }
        String interests = ProfileLearningContext.interests(profile);
        boolean nearby = locations.nearbyEnabled(personId);
        String area = nearby
                ? locations.freshArea(personId, System.currentTimeMillis()) : "";
        return new ArrayList<>(AdaptiveResearchPlan.build(
                destination, interests, area, nearby));
    }

    private static boolean hasValidatedInternet(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network network = manager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private static boolean activeLease(Context context, String expectedPersonId) {
        PersonProfileStore people = new PersonProfileStore(context.getApplicationContext());
        try {
            Map<String, String> active = people.getActiveProfile();
            String currentPersonId = active.getOrDefault(
                    "person_id", active.getOrDefault("name", ""));
            boolean enabled = new SarahLocationStore(context)
                    .backgroundResearchEnabled(expectedPersonId);
            return BackgroundResearchPolicy.leaseStillValid(
                    expectedPersonId,
                    currentPersonId,
                    enabled,
                    "yes".equals(active.getOrDefault("is_owner", "no")),
                    "yes".equals(active.getOrDefault("memory_consent", "no")),
                    Thread.currentThread().isInterrupted());
        } finally {
            people.close();
        }
    }

    private static void requireActiveLease(Context context, String expectedPersonId)
            throws InterruptedException {
        if (!activeLease(context, expectedPersonId)) {
            throw new InterruptedException(
                    "Automatic research stopped because its exact-profile consent lease ended");
        }
    }

    private static void notify(Context context, String speaker, int count) {
        NotificationManager manager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Sarah discoveries", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent intent = new Intent(context, DiscoveryActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 9901, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new android.app.Notification.Builder(context, CHANNEL)
                : new android.app.Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle("Sarah found something you may like")
                .setContentText(count + " possible match" + (count == 1 ? "" : "es")
                        + " for " + speaker + ". Tap to review the sources.")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);
        manager.notify(9901, builder.build());
    }
}
