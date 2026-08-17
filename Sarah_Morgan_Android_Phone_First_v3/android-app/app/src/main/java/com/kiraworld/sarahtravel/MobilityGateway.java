package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Reuses the team travel backend for rail, air, bus, transit, driving, and ferry checks. */
public final class MobilityGateway {
    private static final ConcurrentHashMap<Thread, HttpURLConnection> ACTIVE_CONNECTIONS =
            new ConcurrentHashMap<>();

    private MobilityGateway() { }

    public static void cancel(Thread worker) {
        if (worker == null) return;
        HttpURLConnection active = ACTIVE_CONNECTIONS.remove(worker);
        if (active != null) active.disconnect();
    }

    public static boolean isConfigured(Context context) {
        return endpoint(context).startsWith("https://");
    }

    public static MobilityResult check(
            Context context,
            Map<String, String> watch,
            ConfirmedOwnerLease lease) throws Exception {
        if (lease == null) throw new IllegalStateException("CONFIRMED_OWNER_LEASE_REQUIRED");
        Thread worker = Thread.currentThread();
        lease.requireActive();
        String endpoint = endpoint(context);
        if (endpoint.isEmpty()) return MobilityResult.unconfigured();

        JSONObject request = new JSONObject();
        request.put("watch_kind", "multimodal");
        request.put("watch_id", longValue(watch, "id", 0));
        request.put("origin", watch.getOrDefault("origin", ""));
        request.put("destination", watch.getOrDefault("destination", ""));
        request.put("event_name", watch.getOrDefault("event_name", ""));
        request.put("modes", watch.getOrDefault("modes", "air,rail,intercity_bus"));
        request.put("purpose", watch.getOrDefault("purpose", "options"));
        request.put("include_price", true);
        request.put("include_schedule", true);
        request.put("include_service_alerts", true);
        request.put("include_station_airport_access", true);
        request.put("include_local_connection", true);
        request.put("include_weather_context", true);

        HttpURLConnection connection = null;
        try {
            lease.requireActive();
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(90000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Content-Type", "application/json");
            String token = SecureStore.loadDealBackendToken(context);
            if (token.isEmpty()) token = TravelCommerceConfig.token();
            if (!token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            lease.requireActive();
            if (ACTIVE_CONNECTIONS.putIfAbsent(worker, connection) != null) {
                throw new IllegalStateException("MOBILITY_CONNECTION_ALREADY_REGISTERED");
            }
            lease.requireActive();
            try (OutputStream out = connection.getOutputStream()) {
                lease.requireActive();
                out.write(body);
                lease.requireActive();
                out.flush();
                lease.requireActive();
            }
            lease.requireActive();
            int code = connection.getResponseCode();
            lease.requireActive();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String response = readAll(stream, lease);
            lease.requireActive();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException(
                        "Mobility backend returned HTTP " + code + ": "
                                + abbreviate(response));
            }
            return MobilityResult.fromJson(new JSONObject(response));
        } finally {
            if (connection != null) {
                ACTIVE_CONNECTIONS.remove(worker, connection);
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream stream, ConfirmedOwnerLease lease)
            throws Exception {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while (true) {
                lease.requireActive();
                line = reader.readLine();
                lease.requireActive();
                if (line == null) break;
                text.append(line);
            }
            return text.toString();
        }
    }

    private static long longValue(Map<String, String> row, String key, long fallback) {
        try { return Long.parseLong(row.getOrDefault(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }

    private static String abbreviate(String value) {
        if (value == null) return "";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static String endpoint(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE);
        String configured = prefs.getString("deal_backend_url", "").trim();
        return configured.isEmpty() ? TravelCommerceConfig.endpoint() : configured;
    }
}
