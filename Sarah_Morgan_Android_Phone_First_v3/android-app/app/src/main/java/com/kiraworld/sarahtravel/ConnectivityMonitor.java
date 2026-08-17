package com.kiraworld.sarahtravel;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Watches for a validated internet connection while Sarah is open. */
public final class ConnectivityMonitor {
    public interface Listener {
        void onConnectivityChanged(boolean validatedInternet);
    }

    private final ConnectivityManager manager;
    private final Listener listener;
    private boolean started;

    private final ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            publishCurrentState();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            listener.onConnectivityChanged(isValidated(capabilities));
        }

        @Override
        public void onLost(Network network) {
            publishCurrentState();
        }

        @Override
        public void onUnavailable() {
            listener.onConnectivityChanged(false);
        }
    };

    public ConnectivityMonitor(Context context, Listener listener) {
        this.manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener = listener;
    }

    public void start() {
        if (started || manager == null) return;
        started = true;
        publishCurrentState();
        try {
            manager.registerDefaultNetworkCallback(callback);
        } catch (RuntimeException ignored) {
            publishCurrentState();
        }
    }

    public void stop() {
        if (!started || manager == null) return;
        started = false;
        try {
            manager.unregisterNetworkCallback(callback);
        } catch (RuntimeException ignored) {
            // Callback may already have been removed by the system.
        }
    }

    public boolean currentValidatedInternet() {
        return hasValidatedInternet(manager);
    }

    public static boolean hasValidatedInternet(Context context) {
        ConnectivityManager connectivity = context == null ? null
                : (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return hasValidatedInternet(connectivity);
    }

    private static boolean hasValidatedInternet(ConnectivityManager manager) {
        if (manager == null) return false;
        Network active = manager.getActiveNetwork();
        if (active == null) return false;
        return isValidated(manager.getNetworkCapabilities(active));
    }

    private void publishCurrentState() {
        listener.onConnectivityChanged(currentValidatedInternet());
    }

    private static boolean isValidated(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
