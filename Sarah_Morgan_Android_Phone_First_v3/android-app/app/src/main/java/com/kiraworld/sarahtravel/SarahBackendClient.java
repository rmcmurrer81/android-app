package com.kiraworld.sarahtravel;

import android.util.Base64;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provider-neutral HTTPS client for a team-controlled Sarah backend.
 * The backend may use Workers AI or another provider without asking every app
 * user to supply provider credentials.
 */
public final class SarahBackendClient {
    private static final int MAX_RESPONSE_CHARS = 2_000_000;
    private static final ConcurrentHashMap<Thread, HttpURLConnection> ACTIVE_CONNECTIONS =
            new ConcurrentHashMap<>();
    private SarahBackendClient() { }

    public static void cancel(Thread worker) {
        if (worker == null) return;
        HttpURLConnection active = ACTIVE_CONNECTIONS.remove(worker);
        if (active != null) active.disconnect();
    }

    public static String respond(
            String endpoint,
            String providerId,
            String model,
            String systemPrompt,
            List<Map<String, String>> history,
            String message,
            boolean webSearch,
            byte[] imageJpeg) throws Exception {
        return respondDetailed(
                endpoint, providerId, model, systemPrompt, history,
                message, webSearch, imageJpeg).reply;
    }

    public static ConnectedModelResponse respondDetailed(
            String endpoint,
            String providerId,
            String model,
            String systemPrompt,
            List<Map<String, String>> history,
            String message,
            boolean webSearch,
            byte[] imageJpeg) throws Exception {
        return respondDetailed(
                endpoint, providerId, model, systemPrompt, history, message,
                webSearch, message, imageJpeg);
    }

    public static ConnectedModelResponse respondDetailed(
            String endpoint,
            String providerId,
            String model,
            String systemPrompt,
            List<Map<String, String>> history,
            String message,
            boolean webSearch,
            String searchQuery,
            byte[] imageJpeg) throws Exception {
        return respondDetailed(
                endpoint, providerId, model, systemPrompt, history, message,
                webSearch, searchQuery, imageJpeg, 1,
                ConnectedTurnPolicy.maxNetworkWaitMs(webSearch));
    }

    public static ConnectedModelResponse respondDetailed(
            String endpoint,
            String providerId,
            String model,
            String systemPrompt,
            List<Map<String, String>> history,
            String message,
            boolean webSearch,
            String searchQuery,
            byte[] imageJpeg,
            int attemptNumber,
            long remainingBudgetMs) throws Exception {
        long requestStartedAt = System.currentTimeMillis();
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
        if (webSearch) request.put("search_query", searchQuery == null ? "" : searchQuery);

        JSONArray messages = new JSONArray();
        if (history != null) {
            for (int index = 0; index < history.size(); index++) {
                Map<String, String> row = history.get(index);
                boolean duplicateCurrentUser = index == history.size() - 1
                        && "user".equals(row.getOrDefault("role", ""))
                        && (message == null ? "" : message).equals(row.getOrDefault("content", ""));
                if (duplicateCurrentUser) continue;
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

        Thread worker = Thread.currentThread();
        requireActive(worker);
        String attemptEndpoint = ConnectedTurnPolicy.endpointForAttempt(
                safeEndpoint, attemptNumber);
        HttpURLConnection connection = (HttpURLConnection) new URL(attemptEndpoint).openConnection();
        ACTIVE_CONNECTIONS.put(worker, connection);
        int status;
        String response;
        try {
            requireActive(worker);
            connection.setConnectTimeout(
                    ConnectedTurnPolicy.connectTimeoutMs(remainingBudgetMs, webSearch));
            connection.setReadTimeout(
                    ConnectedTurnPolicy.readTimeoutMs(remainingBudgetMs, webSearch));
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setRequestProperty("User-Agent", "SarahMorganTravel/" + BuildConfig.VERSION_NAME);
            String token = SarahModelConfig.backendToken();
            if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            requireActive(worker);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            requireActive(worker);
            status = connection.getResponseCode();
            if (connection.getContentLength() > MAX_RESPONSE_CHARS) {
                throw new IllegalStateException("Sarah backend response exceeded the bounded response limit");
            }
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            response = read(stream, worker);
        } finally {
            ACTIVE_CONNECTIONS.remove(worker, connection);
            connection.disconnect();
        }
        if (status < 200 || status >= 300) {
            throw new ConnectedTurnPolicy.HttpStatusException(
                    "Sarah backend", status, response.substring(0, Math.min(response.length(), 500)));
        }
        JSONObject json = new JSONObject(response);
        String reply = json.optString("reply", "").trim();
        if (reply.isEmpty()) throw new IllegalStateException("Sarah backend returned no reply.");
        String actualProvider = json.optString("provider", "").trim();
        String actualModel = json.optString("model", "").trim();
        Object onlineReceipt = json.opt("online");
        if (actualProvider.isEmpty() || actualModel.isEmpty()
                || !(onlineReceipt instanceof Boolean)
                || !((Boolean) onlineReceipt)) {
            throw new IllegalStateException(
                    "Sarah backend omitted its required actual provider/model/online route receipt.");
        }
        List<String> sourceUrls = new ArrayList<>();
        JSONArray sourceArray = json.optJSONArray("source_urls");
        if (sourceArray != null) {
            for (int i = 0; i < sourceArray.length(); i++) {
                String sourceUrl = sourceArray.optString(i, "").trim();
                if (sourceUrl.startsWith("https://")) sourceUrls.add(sourceUrl);
            }
        }
        long responseCompletedAt = System.currentTimeMillis();
        return new ConnectedModelResponse(
                reply,
                actualProvider,
                actualModel,
                true,
                webSearch,
                json.optBoolean("web_search_applied", false),
                sourceUrls,
                requestStartedAt,
                responseCompletedAt);
    }

    private static void requireActive(Thread worker) throws InterruptedException {
        if (worker == null || worker.isInterrupted()) {
            throw new InterruptedException("Sarah backend request cancelled");
        }
    }

    private static String read(InputStream stream, Thread worker) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                requireActive(worker);
                out.append(buffer, 0, count);
                if (out.length() > MAX_RESPONSE_CHARS) {
                    throw new IllegalStateException("Sarah backend response exceeded the bounded response limit");
                }
            }
        }
        return out.toString();
    }
}
