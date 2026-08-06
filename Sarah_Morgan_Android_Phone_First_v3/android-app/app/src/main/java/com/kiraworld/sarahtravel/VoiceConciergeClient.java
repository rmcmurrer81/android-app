package com.kiraworld.sarahtravel;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Sends a confirmed hotel-contact request to a team-owned voice service. */
public final class VoiceConciergeClient {
    public static final class Result {
        public final String callId;
        public final String status;
        public final String summary;

        Result(String callId, String status, String summary) {
            this.callId = clean(callId);
            this.status = clean(status);
            this.summary = clean(summary);
        }
    }

    private VoiceConciergeClient() { }

    public static Result start(
            String personName,
            String hotelName,
            String hotelPhone,
            String script) throws Exception {
        if (!VoiceConciergeConfig.isConfigured()) {
            throw new IllegalStateException("Voice concierge backend is not configured.");
        }
        JSONObject request = new JSONObject();
        request.put("action", "start_supervised_hotel_call");
        request.put("person_name", clean(personName));
        request.put("hotel_name", clean(hotelName));
        request.put("hotel_phone", clean(hotelPhone));
        request.put("script", clean(script));
        request.put("require_human_confirmation", true);
        request.put("do_not_purchase", true);
        request.put("do_not_change_booking_without_confirmation", true);

        HttpURLConnection connection = (HttpURLConnection) new URL(
                VoiceConciergeConfig.endpoint()).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(90000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "SarahTravelOS/2.0");
        String token = VoiceConciergeConfig.token();
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String response = read(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Voice concierge returned " + status);
        }
        JSONObject root = new JSONObject(response);
        return new Result(
                root.optString("call_id", ""),
                root.optString("status", "submitted"),
                root.optString("summary", "The supervised call request was submitted."));
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                out.append(buffer, 0, count);
                if (out.length() > 2_000_000) break;
            }
        }
        return out.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
