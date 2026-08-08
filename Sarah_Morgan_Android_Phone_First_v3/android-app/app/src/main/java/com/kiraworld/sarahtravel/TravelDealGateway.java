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

/** Calls a team-configured backend that normalizes fare and weather results. */
public final class TravelDealGateway {
    private TravelDealGateway() { }

    public static boolean isConfigured(Context context) {
        return endpoint(context).startsWith("https://");
    }

    public static TravelDealResult check(Context context, Map<String, String> watch) throws Exception {
        String endpoint = endpoint(context);
        if (endpoint.isEmpty()) return TravelDealResult.unconfigured();

        JSONObject request = new JSONObject();
        request.put("watch_id", longValue(watch, "id", 0));
        request.put("origin", watch.getOrDefault("origin", ""));
        request.put("destination", watch.getOrDefault("destination", ""));
        request.put("trip_type", watch.getOrDefault("trip_type", "round_trip"));
        request.put("travelers", intValue(watch, "travelers", 1));
        request.put("bag_mode", watch.getOrDefault("bag_mode", "carry_on"));
        request.put("flexible_dates", intValue(watch, "flexible_dates", 1) == 1);
        request.put("nearby_airports", intValue(watch, "nearby_airports", 1) == 1);
        request.put("min_trip_days", intValue(watch, "min_trip_days", 3));
        request.put("max_trip_days", intValue(watch, "max_trip_days", 14));
        request.put("horizon_days", intValue(watch, "horizon_days", 365));
        request.put("last_notified_price", doubleValue(watch, "last_notified_price", 0));
        request.put("currency", watch.getOrDefault("currency", "USD"));
        request.put("include_weather_context", true);

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(90000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        String token = SecureStore.loadDealBackendToken(context);
        if (token.isEmpty()) token = TravelCommerceConfig.token();
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String response = readAll(stream);
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("Deal backend returned HTTP " + code + ": " + abbreviate(response));
        }
        return TravelDealResult.fromJson(new JSONObject(response));
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            return text.toString();
        }
    }

    private static int intValue(Map<String, String> row, String key, int fallback) {
        try { return Integer.parseInt(row.getOrDefault(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }

    private static long longValue(Map<String, String> row, String key, long fallback) {
        try { return Long.parseLong(row.getOrDefault(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }

    private static double doubleValue(Map<String, String> row, String key, double fallback) {
        try { return Double.parseDouble(row.getOrDefault(key, String.valueOf(fallback))); }
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
