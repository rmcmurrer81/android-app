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
import java.util.List;
import java.util.Map;

public final class OpenAIClient {
    private static final String ENDPOINT = "https://api.openai.com/v1/responses";

    private OpenAIClient() { }

    public static String respond(String apiKey, String model, String systemPrompt, List<Map<String, String>> history, String message, boolean webSearch, byte[] imageJpeg) throws Exception {
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
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(180000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Content-Type", "application/json");
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = connection.getOutputStream()) { out.write(body); }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String response = readAll(stream);
        if (code < 200 || code >= 300) throw new IllegalStateException("Model service returned HTTP " + code + ": " + response.substring(0, Math.min(response.length(), 500)));
        return extractOutputText(new JSONObject(response));
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
