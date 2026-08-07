package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opportunistically notices another Sarah on private Wi-Fi and asks the person
 * before opening the pairing flow. Discovery alone never transfers anything.
 */
public final class SarahAutoPairCoordinator {
    private static final String PREFS = "sarah_auto_pair";
    private static final AtomicBoolean SCANNING = new AtomicBoolean(false);
    private static final long RESCAN_DELAY = 30L * 60L * 1000L;
    private static final long DECLINE_DELAY = 12L * 60L * 60L * 1000L;

    private SarahAutoPairCoordinator() {}

    public static void maybePrompt(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        if (!SarahDeviceDiscovery.isOnWifi(activity)) return;
        SharedPreferences preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - preferences.getLong("last_scan", 0L) < RESCAN_DELAY) return;
        if (!SCANNING.compareAndSet(false, true)) return;
        preferences.edit().putLong("last_scan", now).apply();

        new Thread(() -> {
            try {
                List<SarahDeviceDiscovery.Peer> peers = SarahDeviceDiscovery.discover(activity, 2200);
                Set<String> trusted = new HashSet<>(TrustedDeviceStore.hosts(activity));
                SarahDeviceDiscovery.Peer candidate = null;
                for (SarahDeviceDiscovery.Peer peer : peers) {
                    if (!trusted.contains(peer.host)) {
                        candidate = peer;
                        break;
                    }
                }
                if (candidate == null) return;
                String lastHost = preferences.getString("last_declined_host", "");
                long lastDeclined = preferences.getLong("last_declined_at", 0L);
                if (candidate.host.equals(lastHost) && now - lastDeclined < DECLINE_DELAY) return;
                SarahDeviceDiscovery.Peer selected = candidate;
                activity.runOnUiThread(() -> showPrompt(activity, selected));
            } catch (Exception ignored) {
                // Local discovery can fail on guest Wi-Fi; Sarah remains fully usable.
            } finally {
                SCANNING.set(false);
            }
        }, "Sarah-Auto-Device-Discovery").start();
    }

    private static void showPrompt(Activity activity, SarahDeviceDiscovery.Peer peer) {
        if (activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
                .setTitle("Sarah found another one of your devices")
                .setMessage(peer.name + " is running Sarah on this Wi-Fi.\n\nDo you want to transfer and synchronize Sarah’s approved memories, trips, conversations, factual corrections, and recent trip photos? Nothing moves until that device shows the same code and you approve it there.")
                .setPositiveButton("Yes — verify my device", (dialog, which) -> {
                    Intent intent = new Intent(activity, TrustedSyncActivity.class);
                    intent.putExtra("peer_host", peer.host);
                    intent.putExtra("peer_name", peer.name);
                    intent.putExtra("peer_port", peer.port);
                    activity.startActivity(intent);
                })
                .setNegativeButton("Not now", (dialog, which) -> activity
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString("last_declined_host", peer.host)
                        .putLong("last_declined_at", System.currentTimeMillis())
                        .apply())
                .show();
    }
}
