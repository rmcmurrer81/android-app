package com.kiraworld.sarahtravel;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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

public final class OpenAIClient {
    private static final String ENDPOINT = "https://api.openai.com/v1/responses";
    private static final ConcurrentHashMap<Thread, HttpURLConnection> ACTIVE_CONNECTIONS =
            new ConcurrentHashMap<>();

    private OpenAIClient() { }

    public static void cancel(Thread worker) {
        if (worker == null) return;
        HttpURLConnection active = ACTIVE_CONNECTIONS.remove(worker);
        if (active != null) active.disconnect();
    }

    public static String respond(String apiKey, String model, String systemPrompt, List<Map<String, String>> history, String message, boolean webSearch, byte[] imageJpeg) throws Exception {
        return respondDetailed(apiKey, model, systemPrompt, history, message, webSearch, imageJpeg).reply;
    }

    public static ConnectedModelResponse respondDetailed(String apiKey, String model, String systemPrompt, List<Map<String, String>> history, String message, boolean webSearch, byte[] imageJpeg) throws Exception {
        long requestStartedAt = System.currentTimeMillis();
        JSONObject payload = new JSONObject();
        payload.put("model", model == null || model.trim().isEmpty() ? "gpt-5-mini" : model.trim());
        payload.put("store", false);
        payload.put("instructions", systemPrompt);
        JSONArray input = new JSONArray();
        for (int index = 0; index < history.size(); index++) {
            Map<String, String> row = history.get(index);
            boolean duplicateCurrentUser = index == history.size() - 1
                    && "user".equals(row.getOrDefault("role", ""))
                    && message.equals(row.getOrDefault("content", ""));
            if (duplicateCurrentUser) continue;
            JSONObject item = new JSONObject();
            item.put("role", row.getOrDefault("role", "user"));
            item.put("content", row.getOrDefault("content", ""));
            input.put(item);
        }
        JSONObject user = new JSONObject();
        user.put("role", "user");
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "input_text").put("text", message));
        if (imageJpeg != null && imageJpeg.length > 0) {
            String dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(imageJpeg, Base64.NO_WRAP);
            content.put(new JSONObject().put("type", "input_image").put("image_url", dataUrl).put("detail", "auto"));
        }
        user.put("content", content);
        input.put(user);
        payload.put("input", input);
        if (webSearch) payload.put("tools", new JSONArray().put(new JSONObject().put("type", "web_search")));

        HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        Thread worker = Thread.currentThread();
        ACTIVE_CONNECTIONS.put(worker, connection);
        connection.setConnectTimeout(ConnectedTurnPolicy.CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(ConnectedTurnPolicy.READ_TIMEOUT_MS);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json");
        int code;
        String response;
        try {
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            response = readAll(stream);
        } finally {
            ACTIVE_CONNECTIONS.remove(worker, connection);
            connection.disconnect();
        }
        if (code < 200 || code >= 300) throw new IllegalStateException("Model service returned HTTP " + code + ": " + response.substring(0, Math.min(response.length(), 500)));
        JSONObject responseJson = new JSONObject(response);
        String reply = extractOutputText(responseJson);
        List<String> sourceUrls = new ArrayList<>();
        collectWebEvidenceUrls(responseJson, sourceUrls);
        boolean webApplied = webSearch && hasCompletedWebSearchCall(responseJson) && !sourceUrls.isEmpty();
        long responseCompletedAt = System.currentTimeMillis();
        return new ConnectedModelResponse(
                reply,
                "openai",
                payload.optString("model", ""),
                true,
                webSearch,
                webApplied,
                sourceUrls,
                requestStartedAt,
                responseCompletedAt);
    }

    private static boolean hasCompletedWebSearchCall(JSONObject response) {
        JSONArray output = response.optJSONArray("output");
        if (output == null) return false;
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null || !"web_search_call".equals(item.optString("type"))) continue;
            if ("completed".equalsIgnoreCase(item.optString("status", ""))) return true;
        }
        return false;
    }

    private static void collectWebEvidenceUrls(JSONObject response, List<String> urls) {
        JSONArray output = response.optJSONArray("output");
        if (output == null) return;
        for (int i = 0; i < output.length() && urls.size() < 20; i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null) continue;
            if ("message".equals(item.optString("type"))) {
                JSONArray content = item.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length() && urls.size() < 20; j++) {
                    JSONObject part = content.optJSONObject(j);
                    JSONArray annotations = part == null ? null : part.optJSONArray("annotations");
                    if (annotations == null) continue;
                    for (int k = 0; k < annotations.length() && urls.size() < 20; k++) {
                        JSONObject annotation = annotations.optJSONObject(k);
                        if (annotation != null && "url_citation".equals(annotation.optString("type"))) {
                            addHttpsUrl(annotation.optString("url", ""), urls);
                        }
                    }
                }
            } else if ("web_search_call".equals(item.optString("type"))
                    && "completed".equalsIgnoreCase(item.optString("status", ""))) {
                JSONObject action = item.optJSONObject("action");
                JSONArray sources = action == null ? null : action.optJSONArray("sources");
                if (sources == null) continue;
                for (int j = 0; j < sources.length() && urls.size() < 20; j++) {
                    JSONObject source = sources.optJSONObject(j);
                    if (source != null) addHttpsUrl(source.optString("url", ""), urls);
                }
            }
        }
    }

    private static void addHttpsUrl(String raw, List<String> urls) {
        String url = raw == null ? "" : raw.trim();
        if (url.startsWith("https://") && !urls.contains(url)) urls.add(url);
    }

    static String extractOutputText(JSONObject response) {
        String top = response.optString("output_text", "").trim();
        if (!top.isEmpty()) return top;
        StringBuilder b = new StringBuilder();
        JSONArray output = response.optJSONArray("output");
        if (output == null) return "I received a response, but I could not read its text.";
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("type"))) continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part != null && "output_text".equals(part.optString("type"))) {
                    if (b.length() > 0) b.append('\n');
                    b.append(part.optString("text", ""));
                }
            }
        }
        return b.length() == 0 ? "I received a response, but I could not read its text." : b.toString().trim();
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line);
            return b.toString();
        }
    }
}
