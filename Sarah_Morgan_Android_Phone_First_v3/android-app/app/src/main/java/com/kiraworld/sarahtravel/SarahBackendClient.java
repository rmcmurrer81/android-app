package com.kiraworld.sarahtravel;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Provider-neutral HTTPS client for a team-controlled Sarah backend.
 * The backend may use Workers AI or another provider without asking every app
 * user to supply provider credentials.
 */
public final class SarahBackendClient {
    private SarahBackendClient() { }

    public static String respond(
            String endpoint,
            String providerId,
            String model,
            String systemPrompt,
            List<Map<String, String>> history,
            String message,
            boolean webSearch,
            byte[] imageJpeg) throws Exception {
        String safeEndpoint = endpoint == null ? "" : endpoint.trim();
        if (!safeEndpoint.startsWith("https://")) {
            throw new IllegalArgumentException("Sarah's model backend must use HTTPS.");
        }

        JSONObject request = new JSONObject();
        request.put("provider", providerId == null ? "openai" : providerId);
        request.put("model", model == null ? "" : model);
        request.put("system_prompt", systemPrompt == null ? "" : systemPrompt);
        request.put("message", message == null ? "" : message);
        request.put("web_search", webSearch);

        JSONArray messages = new JSONArray();
        if (history != null) {
            for (Map<String, String> row : history) {
                JSONObject item = new JSONObject();
                item.put("role", row.getOrDefault("role", "user"));
                item.put("content", row.getOrDefault("content", ""));
                messages.put(item);
            }
        }
        request.put("history", messages);
        if (imageJpeg != null && imageJpeg.length > 0) {
            request.put("image_jpeg_base64", Base64.encodeToString(imageJpeg, Base64.NO_WRAP));
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(safeEndpoint).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(90000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "SarahMorganTravel/1.5");
        String token = SarahModelConfig.backendToken();
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);

        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String response = read(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Sarah backend returned " + status);
        }
        JSONObject json = new JSONObject(response);
        String reply = json.optString("reply", "").trim();
        if (reply.isEmpty()) throw new IllegalStateException("Sarah backend returned no reply.");
        return reply;
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                out.append(buffer, 0, count);
                if (out.length() > 4_000_000) break;
            }
        }
        return out.toString();
    }
}
