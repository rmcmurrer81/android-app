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

    /**
     * One bounded first-run discovery decision before profile questions.
     * Failure, no Wi-Fi, no peer, decline, cancel, and timeout all continue
     * local setup. Accepting a peer opens TrustedSyncActivity for result; the
     * caller continues only after that screen returns.
     */
    public static void runBeforeProfileQuestions(
            Activity activity,
            int pairingRequestCode,
            Runnable pairingLaunched,
            Runnable continueLocalSetup) {
        if (activity == null || activity.isFinishing() || continueLocalSetup == null) return;
        AtomicBoolean completed = new AtomicBoolean(false);
        Runnable continueOnce = () -> {
            if (!completed.compareAndSet(false, true)) return;
            if (!activity.isFinishing()) continueLocalSetup.run();
        };
        if (!SarahDeviceDiscovery.isOnWifi(activity)) {
            continueOnce.run();
            return;
        }
        new Thread(() -> {
            try {
                List<SarahDeviceDiscovery.Peer> peers =
                        SarahDeviceDiscovery.discover(activity, 2200);
                Set<String> trusted = new HashSet<>(TrustedDeviceStore.hosts(activity));
                SarahDeviceDiscovery.Peer candidate = null;
                for (SarahDeviceDiscovery.Peer peer : peers) {
                    if (!trusted.contains(peer.host)) {
                        candidate = peer;
                        break;
                    }
                }
                SarahDeviceDiscovery.Peer selected = candidate;
                activity.runOnUiThread(() -> {
                    if (selected == null || activity.isFinishing()) {
                        continueOnce.run();
                        return;
                    }
                    AlertDialog prompt = new AlertDialog.Builder(activity)
                            .setTitle("I found Sarah on another device")
                            .setMessage("I see " + selected.name
                                    + " on this private Wi-Fi. Is that your device? Finding it proves nothing and transfers nothing. If you choose Yes, compare the short-lived code and approve on both devices before any device credential is saved.")
                            .setPositiveButton("Yes - verify both", (dialog, which) -> {
                                Intent intent = pairingIntent(activity, selected);
                                try {
                                    completed.set(true);
                                    if (pairingLaunched != null) pairingLaunched.run();
                                    activity.startActivityForResult(intent, pairingRequestCode);
                                } catch (Exception error) {
                                    completed.set(false);
                                    continueOnce.run();
                                }
                            })
                            .setNegativeButton("No - continue setup", (dialog, which) -> {
                                rememberDecline(activity, selected);
                                continueOnce.run();
                            })
                            .create();
                    prompt.setOnCancelListener(dialog -> continueOnce.run());
                    prompt.show();
                });
            } catch (Exception ignored) {
                activity.runOnUiThread(continueOnce);
            }
        }, "Sarah-First-Run-Device-Discovery").start();
    }

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
                .setTitle("Is this your device?")
                .setMessage(peer.name + " is running Sarah on this private Wi-Fi. Finding it does not prove ownership and transfers nothing. If you recognize it, Sarah will show a short-lived matching code on both screens and require approval on both.")
                .setPositiveButton("Yes - compare codes", (dialog, which) -> {
                    Intent intent = new Intent(activity, TrustedSyncActivity.class);
                    intent.putExtra("peer_host", peer.host);
                    intent.putExtra("peer_name", peer.name);
                    intent.putExtra("peer_instance_id", peer.instanceId);
                    intent.putExtra("peer_device_type", peer.deviceType);
                    intent.putExtra("peer_port", peer.pairingPort);
                    intent.putExtra("peer_expires_at", peer.expiresAt);
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

    private static Intent pairingIntent(Activity activity, SarahDeviceDiscovery.Peer peer) {
        Intent intent = new Intent(activity, TrustedSyncActivity.class);
        intent.putExtra("peer_host", peer.host);
        intent.putExtra("peer_name", peer.name);
        intent.putExtra("peer_instance_id", peer.instanceId);
        intent.putExtra("peer_device_type", peer.deviceType);
        intent.putExtra("peer_port", peer.pairingPort);
        intent.putExtra("peer_expires_at", peer.expiresAt);
        return intent;
    }

    private static void rememberDecline(Activity activity, SarahDeviceDiscovery.Peer peer) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("last_declined_host", peer.host)
                .putLong("last_declined_at", System.currentTimeMillis())
                .apply();
    }
}
