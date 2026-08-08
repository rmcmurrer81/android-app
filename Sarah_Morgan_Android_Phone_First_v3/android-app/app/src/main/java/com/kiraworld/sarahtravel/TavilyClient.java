package com.kiraworld.sarahtravel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Search client that keeps the Tavily provider credential in Sarah's protected proxy. */
public final class TavilyClient {
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final ConcurrentHashMap<Thread, HttpURLConnection> ACTIVE =
            new ConcurrentHashMap<>();
    public interface CancellationCheck { boolean cancelled(); }
    public static final class Result {
        public final String title, url, summary;
        Result(String title, String url, String summary) { this.title=title; this.url=url; this.summary=summary; }
    }
    private TavilyClient() { }
    public static boolean configured() {
        return SarahModelConfig.backendUrl().startsWith("https://")
                && !SarahModelConfig.backendToken().isEmpty();
    }
    public static List<Result> search(String query, int limit) throws Exception {
        return search(query, limit, () -> Thread.currentThread().isInterrupted());
    }
    public static List<Result> search(
            String query,
            int limit,
            CancellationCheck cancellation) throws Exception {
        if (!configured()) return Collections.emptyList();
        requireActive(cancellation);
        HttpURLConnection c = (HttpURLConnection) new URL(searchEndpoint()).openConnection();
        ACTIVE.put(Thread.currentThread(), c);
        try {
        c.setConnectTimeout(20000); c.setReadTimeout(60000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + SarahModelConfig.backendToken());
        JSONObject body = new JSONObject();
        body.put("query", query);
        body.put("max_results", Math.max(1, Math.min(8, limit)));
        requireActive(cancellation);
        try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        requireActive(cancellation);
        int status = c.getResponseCode();
        int declaredLength = c.getContentLength();
        if (declaredLength > MAX_RESPONSE_BYTES) {
            c.disconnect();
            throw new IllegalStateException("Tavily response exceeded the bounded response limit");
        }
        InputStream stream = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            if (stream != null) try (InputStream in = stream) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) >= 0) {
                    requireActive(cancellation);
                    if (bytes.size() + n > MAX_RESPONSE_BYTES) {
                        throw new IllegalStateException("Tavily response exceeded the bounded response limit");
                    }
                    bytes.write(b, 0, n);
                }
            }
        } finally {
            c.disconnect();
        }
        if (status < 200 || status >= 300) throw new IllegalStateException("Tavily returned " + status);
        requireActive(cancellation);
        JSONArray array = new JSONObject(
                new String(bytes.toByteArray(), StandardCharsets.UTF_8)).optJSONArray("results");
        List<Result> results = new ArrayList<>();
        if (array != null) for (int i=0; i<array.length(); i++) {
            JSONObject row=array.optJSONObject(i); if(row==null) continue;
            String url=row.optString("url", ""); if(!url.startsWith("https://")) continue;
            results.add(new Result(row.optString("title", "Possible travel match"), url,
                    row.optString("content", "").replaceAll("\\s+", " ").trim()));
        }
        return results;
        } finally {
            ACTIVE.remove(Thread.currentThread(), c);
            c.disconnect();
        }
    }

    public static void cancel(Thread worker) {
        if (worker == null) return;
        HttpURLConnection active = ACTIVE.remove(worker);
        if (active != null) active.disconnect();
    }

    private static void requireActive(CancellationCheck cancellation) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()
                || cancellation != null && cancellation.cancelled()) {
            throw new InterruptedException("Current-source research was cancelled");
        }
    }

    private static String searchEndpoint() {
        String base = SarahModelConfig.backendUrl().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (base.endsWith("/chat")) base = base.substring(0, base.length() - 5);
        return base + "/search";
    }
}
