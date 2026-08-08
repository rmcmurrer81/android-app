package com.kiraworld.sarahtravel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** Retrieves one source-linked Wikimedia Commons thumbnail without an API key. */
public final class PublicMediaGateway {
    public static final class MediaItem {
        public final byte[] imageBytes;
        public final String title;
        public final String sourceUrl;

        MediaItem(byte[] imageBytes, String title, String sourceUrl) {
            this.imageBytes = imageBytes == null ? new byte[0] : imageBytes;
            this.title = title == null ? "" : title.trim();
            this.sourceUrl = sourceUrl == null ? "" : sourceUrl.trim();
        }

        public boolean found() {
            return imageBytes.length > 0;
        }
    }

    private PublicMediaGateway() { }

    public static MediaItem findFirst(String... queries) {
        if (queries == null) return empty();
        for (String query : queries) {
            String safe = query == null ? "" : query.trim();
            if (safe.length() < 2) continue;
            try {
                MediaItem item = searchOne(safe);
                if (item.found()) return item;
            } catch (Exception ignored) { }
        }
        return empty();
    }

    private static MediaItem searchOne(String query) throws Exception {
        String api = "https://commons.wikimedia.org/w/api.php?action=query&format=json"
                + "&generator=search&gsrnamespace=6&gsrlimit=8&gsrsearch=" + encode(query)
                + "&prop=imageinfo&iiprop=url&iiurlwidth=900";
        JSONObject root = new JSONObject(getText(api, "application/json"));
        JSONObject pages = root.optJSONObject("query") == null
                ? null : root.optJSONObject("query").optJSONObject("pages");
        if (pages == null) return empty();

        Iterator<String> keys = pages.keys();
        while (keys.hasNext()) {
            JSONObject page = pages.optJSONObject(keys.next());
            if (page == null) continue;
            JSONArray info = page.optJSONArray("imageinfo");
            if (info == null || info.length() == 0) continue;
            JSONObject image = info.optJSONObject(0);
            if (image == null) continue;
            String thumbnail = image.optString("thumburl", image.optString("url", "")).trim();
            if (!thumbnail.startsWith("https://")) continue;
            String source = image.optString("descriptionurl", "").trim();
            byte[] bytes = getBytes(thumbnail);
            if (bytes.length == 0) continue;
            return new MediaItem(bytes, page.optString("title", query), source);
        }
        return empty();
    }

    private static String getText(String url, String accept) throws Exception {
        HttpURLConnection connection = open(url, accept);
        try (InputStream in = connection.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(in, out, 3_000_000);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpURLConnection connection = open(url, "image/*");
        try (InputStream in = connection.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(in, out, 4_000_000);
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String accept) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "SarahMorganTravel/" + BuildConfig.VERSION_NAME
                + " (public media preview)");
        connection.setRequestProperty("Accept", accept);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 400) {
            connection.disconnect();
            throw new IllegalStateException("Public media source returned " + status);
        }
        return connection;
    }

    private static void copy(InputStream in, ByteArrayOutputStream out, int maximum) throws Exception {
        byte[] buffer = new byte[8192];
        int count;
        int total = 0;
        while ((count = in.read(buffer)) >= 0) {
            total += count;
            if (total > maximum) throw new IllegalStateException("Public media response was too large.");
            out.write(buffer, 0, count);
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static MediaItem empty() {
        return new MediaItem(new byte[0], "", "");
    }
}
