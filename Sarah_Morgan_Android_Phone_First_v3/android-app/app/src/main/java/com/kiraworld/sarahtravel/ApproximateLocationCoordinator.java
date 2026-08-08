package com.kiraworld.sarahtravel;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One-shot coarse city/area resolver with bounded waiting and no coordinate persistence. */
public final class ApproximateLocationCoordinator implements AutoCloseable {
    public interface Callback {
        void onResolved(String area, long capturedAt);
        void onUnavailable(String reason);
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LocationListener activeListener;
    private LocationManager activeManager;
    private Runnable timeout;
    private volatile int generation;
    private volatile boolean closed;

    public ApproximateLocationCoordinator(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean hasPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public void resolve(Callback callback) {
        if (closed) return;
        int requestGeneration = ++generation;
        cancelActive();
        Callback guarded = new Callback() {
            @Override public void onResolved(String area, long capturedAt) {
                if (isActive(requestGeneration)) callback.onResolved(area, capturedAt);
            }

            @Override public void onUnavailable(String reason) {
                if (isActive(requestGeneration)) callback.onUnavailable(reason);
            }
        };
        if (!hasPermission()) {
            guarded.onUnavailable("permission_denied");
            return;
        }
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null || !anyProviderEnabled(manager)) {
            guarded.onUnavailable("services_off");
            return;
        }
        Location best = bestLastKnown(manager);
        long now = System.currentTimeMillis();
        if (best != null && CurrentLocationPolicy.fresh(best.getTime(), now)) {
            reverse(best, guarded);
            return;
        }
        requestOne(manager, guarded);
    }

    private void requestOne(LocationManager manager, Callback callback) {
        final boolean[] completed = {false};
        activeManager = manager;
        activeListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (completed[0]) return;
                completed[0] = true;
                cancel(manager);
                reverse(location, callback);
            }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        };
        timeout = () -> {
            if (completed[0]) return;
            completed[0] = true;
            cancel(manager);
            callback.onUnavailable("stale");
        };
        try {
            String provider = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    ? LocationManager.NETWORK_PROVIDER
                    : LocationManager.GPS_PROVIDER;
            manager.requestSingleUpdate(provider, activeListener, Looper.getMainLooper());
            main.postDelayed(timeout, 8_000L);
        } catch (SecurityException denied) {
            cancel(manager);
            callback.onUnavailable("permission_denied");
        } catch (Exception unavailable) {
            cancel(manager);
            callback.onUnavailable("unavailable");
        }
    }

    private void reverse(Location location, Callback callback) {
        executor.submit(() -> {
            try {
                Geocoder geocoder = new Geocoder(context, Locale.getDefault());
                List<Address> rows = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                if (rows == null || rows.isEmpty()) {
                    main.post(() -> callback.onUnavailable("unavailable"));
                    return;
                }
                Address address = rows.get(0);
                String locality = clean(address.getLocality());
                if (locality.isEmpty()) locality = clean(address.getSubAdminArea());
                String region = clean(address.getAdminArea());
                String country = clean(address.getCountryName());
                String area = locality;
                if (!region.isEmpty() && !region.equalsIgnoreCase(locality)) area += (area.isEmpty() ? "" : ", ") + region;
                if (area.isEmpty()) area = country;
                final String resolved = area;
                long capturedAt = location.getTime() > 0 ? location.getTime() : System.currentTimeMillis();
                main.post(() -> {
                    if (resolved.isEmpty()) callback.onUnavailable("unavailable");
                    else callback.onResolved(resolved, capturedAt);
                });
            } catch (Exception failure) {
                main.post(() -> callback.onUnavailable("unavailable"));
            }
        });
    }

    private Location bestLastKnown(LocationManager manager) {
        Location best = null;
        for (String provider : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER}) {
            try {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) best = candidate;
            } catch (Exception ignored) { }
        }
        return best;
    }

    private static boolean anyProviderEnabled(LocationManager manager) {
        try {
            return manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void cancel(LocationManager manager) {
        if (timeout != null) main.removeCallbacks(timeout);
        if (activeListener != null) {
            try { manager.removeUpdates(activeListener); }
            catch (Exception ignored) { }
        }
        activeListener = null;
        activeManager = null;
        timeout = null;
    }

    private void cancelActive() {
        LocationManager manager = activeManager;
        if (manager != null) cancel(manager);
        else if (timeout != null) {
            main.removeCallbacks(timeout);
            timeout = null;
        }
    }

    private boolean isActive(int requestGeneration) {
        return !closed && generation == requestGeneration;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    @Override public void close() {
        closed = true;
        generation++;
        cancelActive();
        executor.shutdownNow();
    }
}
