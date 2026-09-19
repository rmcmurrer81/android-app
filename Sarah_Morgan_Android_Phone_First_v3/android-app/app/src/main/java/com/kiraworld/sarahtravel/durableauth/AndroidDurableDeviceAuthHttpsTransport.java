package com.kiraworld.sarahtravel.durableauth;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * Staged HTTPS-only transport for the future durable-auth client.
 *
 * <p>Status: STAGED_NOT_CONNECTED. It uses the platform trust store, rejects
 * redirects, never logs requests or responses, and has no persistence API.</p>
 */
public final class AndroidDurableDeviceAuthHttpsTransport
        implements DurableDeviceAuthClientCore.Transport {
    public static final String IMPLEMENTATION_STATUS = "STAGED_NOT_CONNECTED";

    private final String apiOrigin;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    public AndroidDurableDeviceAuthHttpsTransport(
            String apiOrigin,
            int connectTimeoutMillis,
            int readTimeoutMillis) {
        URI uri = URI.create(apiOrigin);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawAuthority() == null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || apiOrigin.endsWith("/")) {
            throw new IllegalArgumentException("apiOrigin must be a path-free HTTPS origin");
        }
        if (connectTimeoutMillis < 1 || readTimeoutMillis < 1) {
            throw new IllegalArgumentException("timeouts must be positive");
        }
        this.apiOrigin = apiOrigin;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public DurableDeviceAuthClientCore.Response execute(
            DurableDeviceAuthClientCore.Request request) throws IOException, JSONException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(
                apiOrigin + request.path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(readTimeoutMillis);
        connection.setRequestMethod(request.method);
        connection.setUseCaches(false);
        for (Map.Entry<String, String> header : request.headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        if (!request.body.isEmpty() && !"GET".equals(request.method)) {
            connection.setDoOutput(true);
            byte[] encoded = new JSONObject(request.body).toString()
                    .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(encoded.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(encoded);
            }
        }
        int status = connection.getResponseCode();
        if (status >= 300 && status < 400) {
            connection.disconnect();
            throw new IOException("Durable-auth redirect rejected");
        }
        InputStream stream = status >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        String text = readBounded(stream, 256 * 1024);
        Map<String, Object> body = text.isEmpty()
                ? Collections.<String, Object>emptyMap()
                : toMap(new JSONObject(text));
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        copyHeader(connection, responseHeaders, "Date");
        copyHeader(connection, responseHeaders, "Retry-After");
        connection.disconnect();
        return new DurableDeviceAuthClientCore.Response(status, responseHeaders, body);
    }

    private static void copyHeader(
            HttpsURLConnection connection,
            Map<String, String> destination,
            String name) {
        String value = connection.getHeaderField(name);
        if (value != null && !value.isEmpty()) destination.put(name, value);
    }

    private static String readBounded(InputStream stream, int maximumBytes) throws IOException {
        if (stream == null) return "";
        StringBuilder text = new StringBuilder();
        int count = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                count += read;
                if (count > maximumBytes) throw new IOException("Auth response exceeded limit");
                text.append(buffer, 0, read);
            }
        }
        return text.toString();
    }

    private static Map<String, Object> toMap(JSONObject source) throws JSONException {
        Map<String, Object> result = new LinkedHashMap<>();
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, toJava(source.get(key)));
        }
        return result;
    }

    private static Object toJava(Object value) throws JSONException {
        if (value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return toMap((JSONObject) value);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<Object> result = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                result.add(toJava(array.get(index)));
            }
            return result;
        }
        return value;
    }
}
