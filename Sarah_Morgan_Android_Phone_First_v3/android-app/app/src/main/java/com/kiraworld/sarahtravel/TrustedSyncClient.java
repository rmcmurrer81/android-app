package com.kiraworld.sarahtravel;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class TrustedSyncClient {
    private static final int MAX_RESPONSE_BYTES = 8_000_000;
    private TrustedSyncClient() {}

    /**
     * R2 deliberately ships trusted-device transport disabled. The preserved
     * HTTP prototype reused its bearer token as payload key material and did
     * not yet provide TLS or an authenticated key agreement.
     */
    public static boolean isTransportAccepted() { return false; }

    private static void requireAcceptedTransport() {
        throw new SecurityException(
                "Trusted device sync is disabled until a TLS or authenticated key-agreement transport is accepted.");
    }

    /** Manual six-digit fallback retained for networks where UDP discovery is blocked. */
    public static String pair(Context context, String host, String code) throws Exception {
        requireAcceptedTransport();
        host = cleanHost(host);
        JSONObject body = identity(context);
        body.put("code", code == null ? "" : code.trim());
        JSONObject response = post(baseUrl(host) + "/pair", body.toString(), "");
        String token = response.getString("token");
        TrustedDeviceStore.savePeer(context, host, token);
        return token;
    }

    /**
     * Requests pairing, waits for the already-running Sarah device to approve the
     * named phone and matching code, and then performs the first encrypted sync.
     */
    public static JSONObject requestPairAndSync(
            Context context,
            String host,
            int port,
            String verificationCode) throws Exception {
        requireAcceptedTransport();
        String peer = cleanHost(host);
        if (port > 0 && port != TrustedLanEndpointPolicy.PORT) {
            throw new IllegalArgumentException(
                    "Trusted Sarah sync uses only LAN port " + TrustedLanEndpointPolicy.PORT + ".");
        }
        JSONObject body = identity(context);
        body.put("verification_code", verificationCode);
        JSONObject requested = post(baseUrl(peer) + "/pair/request", body.toString(), "");
        String requestId = requested.getString("request_id");
        long deadline = System.currentTimeMillis() + 150_000L;

        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1200L);
            JSONObject poll = new JSONObject();
            poll.put("request_id", requestId);
            poll.put("device_id", TrustedDeviceStore.localDeviceId(context));
            JSONObject state = post(baseUrl(peer) + "/pair/status", poll.toString(), "");
            String status = state.optString("status", "pending");
            if ("pending".equals(status)) continue;
            if ("approved".equals(status)) {
                String token = state.getString("token");
                TrustedDeviceStore.savePeer(context, peer, token);
                JSONObject result = syncHost(context, peer);
                result.put("pairing_message", "The trusted device approved this phone. Sarah completed her first encrypted two-way sync.");
                return result;
            }
            if ("denied".equals(status)) throw new SecurityException("The existing Sarah device denied this pairing request.");
            if ("expired".equals(status)) throw new SecurityException("The pairing request expired. Scan again and approve the new code.");
            throw new SecurityException(state.optString("message", "The pairing request was not approved."));
        }
        throw new SecurityException("The pairing request timed out without approval on the existing device.");
    }

    public static JSONObject sync(Context context) throws Exception {
        return syncHost(context, TrustedDeviceStore.host(context));
    }

    public static JSONObject syncHost(Context context, String host) throws Exception {
        requireAcceptedTransport();
        host = cleanHost(host);
        String token = TrustedDeviceStore.tokenFor(context, host);
        if (host.isEmpty() || token.isEmpty()) throw new IllegalStateException("Approve and pair this Sarah device first.");
        String encrypted = TrustedSyncProtocol.encrypt(token, SarahSyncExporter.export(context).toString());
        JSONObject body = new JSONObject();
        body.put("payload", encrypted);
        body.put("signature", TrustedSyncProtocol.signature(token, encrypted));
        JSONObject response = post(baseUrl(host) + "/sync", body.toString(), token);
        String incoming = response.optString("payload", "");
        if (!incoming.isEmpty()) {
            String signature = response.optString("signature", "");
            if (!TrustedSyncProtocol.signature(token, incoming).equals(signature)) {
                throw new SecurityException("The trusted device reply signature failed.");
            }
            int imported = SarahSyncImporter.importPayload(
                    context,
                    new JSONObject(TrustedSyncProtocol.decrypt(token, incoming)));
            response.put(
                    "message",
                    response.optString("message", "Sync completed.")
                            + " Android imported " + imported + " new item(s).");
        }
        return response;
    }

    public static JSONObject syncAll(Context context) {
        JSONArray successes = new JSONArray();
        JSONArray failures = new JSONArray();
        if (!isTransportAccepted()) {
            JSONObject disabled = new JSONObject();
            try {
                disabled.put("successes", successes);
                disabled.put("failures", failures);
                disabled.put("message", "Device sync is disabled in this R2 candidate; secure transport setup is required.");
            } catch (Exception ignored) { }
            return disabled;
        }
        for (String host : TrustedDeviceStore.hosts(context)) {
            try {
                JSONObject result = syncHost(context, host);
                JSONObject row = new JSONObject();
                row.put("host", host);
                row.put("message", result.optString("message", "Synced"));
                successes.put(row);
            } catch (Exception error) {
                JSONObject row = new JSONObject();
                try {
                    row.put("host", host);
                    row.put("error", error.getMessage());
                } catch (Exception ignored) {}
                failures.put(row);
            }
        }
        JSONObject result = new JSONObject();
        try {
            result.put("successes", successes);
            result.put("failures", failures);
            result.put("message", "Synced " + successes.length() + " device(s); " + failures.length() + " unavailable.");
        } catch (Exception ignored) {}
        return result;
    }

    public static void syncAllAsync(Context context) {
        if (!isTransportAccepted()
                || !TrustedDeviceStore.hasPeers(context)
                || !context.getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean("auto_device_sync", true)) return;
        Context app = context.getApplicationContext();
        new Thread(() -> syncAll(app), "Sarah-Multi-Device-Sync").start();
    }

    private static JSONObject identity(Context context) throws Exception {
        JSONObject body = new JSONObject();
        body.put("device_id", TrustedDeviceStore.localDeviceId(context));
        String manufacturer = android.os.Build.MANUFACTURER == null ? "Android" : android.os.Build.MANUFACTURER.trim();
        String model = android.os.Build.MODEL == null ? "phone" : android.os.Build.MODEL.trim();
        body.put("device_name", (manufacturer + " " + model).trim());
        body.put("device_type", "android-phone");
        return body;
    }

    private static String cleanHost(String host) {
        return TrustedLanEndpointPolicy.requireLocalHost(host);
    }

    private static String baseUrl(String host) {
        requireAcceptedTransport();
        return "";
    }

    private static JSONObject post(String endpoint, String body, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(165_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        if (!token.isEmpty()) connection.setRequestProperty("X-Sarah-Device-Token", token);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        if (connection.getContentLengthLong() > MAX_RESPONSE_BYTES) {
            connection.disconnect();
            throw new IllegalStateException("Trusted device response exceeded the bounded response limit.");
        }
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            if (stream != null) {
                try (InputStream input = stream) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        if (bytes.size() + count > MAX_RESPONSE_BYTES) {
                            throw new IllegalStateException(
                                    "Trusted device response exceeded the bounded response limit.");
                        }
                        bytes.write(buffer, 0, count);
                    }
                }
            }
        } finally {
            connection.disconnect();
        }
        String response = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        if (status < 200 || status >= 300) {
            String detail = response;
            try { detail = new JSONObject(response).optString("error", response); }
            catch (Exception ignored) {}
            throw new IllegalStateException("Sarah device returned " + status + ": " + detail);
        }
        return new JSONObject(response.isEmpty() ? "{}" : response);
    }
}
