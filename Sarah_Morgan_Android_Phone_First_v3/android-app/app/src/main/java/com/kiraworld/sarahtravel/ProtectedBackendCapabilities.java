package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded authenticated capability probe and short-lived cache for protected service truth. */
public final class ProtectedBackendCapabilities {
    private static final String PREFS = "sarah_protected_backend_capabilities";
    // Part of identityKey so an older public-/health cache can never survive
    // the transition to an authenticated capability proof.
    private static final String CACHE_CONTRACT = "authenticated-capabilities-v1";
    private static final long MAX_AGE_MS = 5 * 60 * 1000L;
    private static final long FAILED_MAX_AGE_MS = 20 * 1000L;
    private static final int MAX_BYTES = 64 * 1024;
    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);
    private static final Object CALLBACK_LOCK = new Object();
    private static final List<RefreshCallback> CALLBACKS = new ArrayList<>();

    public interface RefreshCallback {
        void onComplete(ProtectedBackendCapabilityPolicy.Decision decision);
    }

    private ProtectedBackendCapabilities() { }

    public static void refreshAsync(Context context) {
        refreshAsync(context, null);
    }

    /** All concurrent callers share one bounded probe and receive its result. */
    public static void refreshAsync(Context context, RefreshCallback callback) {
        if (context == null) return;
        if (callback != null) {
            synchronized (CALLBACK_LOCK) { CALLBACKS.add(callback); }
        }
        if (!REFRESHING.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            ProtectedBackendCapabilityPolicy.Decision decision;
            try { decision = refresh(app); }
            finally { REFRESHING.set(false); }
            List<RefreshCallback> callbacks;
            synchronized (CALLBACK_LOCK) {
                callbacks = new ArrayList<>(CALLBACKS);
                CALLBACKS.clear();
            }
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            for (RefreshCallback queued : callbacks) {
                main.post(() -> queued.onComplete(decision));
            }
        }, "Sarah-Protected-Capability-Refresh");
        worker.setDaemon(true);
        worker.start();
    }

    public static boolean isChecking() {
        return REFRESHING.get();
    }

    /** Remove only derived capability truth after the owner changes a route. */
    public static void clearCached(Context context) {
        if (context == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public static ProtectedBackendCapabilityPolicy.Decision cached(Context context) {
        ProtectedBackendCapabilityPolicy.Decision unverified = evaluate(null);
        if (context == null) return unverified;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long checkedAt = prefs.getLong("checked_at", 0L);
        long maxAge = prefs.getBoolean("contract_verified", false)
                ? MAX_AGE_MS : FAILED_MAX_AGE_MS;
        if (checkedAt <= 0L || System.currentTimeMillis() - checkedAt > maxAge
                || !identityKey().equals(prefs.getString("identity_key", ""))) {
            return unverified;
        }
        ProtectedBackendCapabilityPolicy.Health health = new ProtectedBackendCapabilityPolicy.Health(
                prefs.getBoolean("http_ok", false),
                prefs.getBoolean("ok", false),
                prefs.getString("service", ""),
                prefs.getString("contract_version", ""),
                prefs.getBoolean("deployment_ready", false),
                prefs.getString("deployment_id", ""),
                prefs.getString("source_sha256", ""),
                prefs.getString("config_sha256", ""),
                prefs.getString("provider", ""),
                prefs.getString("model", ""),
                prefs.getBoolean("route_rate_limits_ready", false),
                prefs.getBoolean("current_source_ready", false),
                prefs.getBoolean("voice_ready", false));
        return evaluate(health);
    }

    public static boolean currentSourceReady(Context context) {
        if (protectedRouteConfigured()) return cached(context).currentSourceReady;
        return directOpenAiReady();
    }

    public static boolean voiceReady(Context context) {
        return protectedRouteConfigured() && cached(context).voiceReady;
    }

    public static boolean conversationReady(Context context) {
        if (protectedRouteConfigured()) return cached(context).contractVerified;
        return directOpenAiReady();
    }

    public static boolean protectedRouteConfigured() {
        return SarahModelConfig.backendUrl().startsWith("https://")
                && !SarahModelConfig.backendToken().isEmpty();
    }

    static ProtectedBackendCapabilityPolicy.Decision refresh(Context context) {
        HttpURLConnection connection = null;
        ProtectedBackendCapabilityPolicy.Health health = null;
        try {
            String endpoint = capabilitiesEndpoint();
            if (endpoint.isEmpty()) return evaluate(null);
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            // Never forward the embedded bearer across an HTTP redirect.
            // The expected protected endpoint must answer directly.
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(8_000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty(
                    "Authorization", "Bearer " + SarahModelConfig.backendToken());
            connection.setRequestProperty(
                    "User-Agent", "SarahMorganTravel/" + BuildConfig.VERSION_NAME);
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            byte[] payload = bounded(stream);
            JSONObject body = payload.length == 0
                    ? new JSONObject() : new JSONObject(new String(payload, StandardCharsets.UTF_8));
            health = new ProtectedBackendCapabilityPolicy.Health(
                    status >= 200 && status < 300,
                    body.optBoolean("ok", false),
                    body.optString("service", ""),
                    body.optString("contract_version", ""),
                    body.optBoolean("deployment_ready", false),
                    body.optString("deployment_id", ""),
                    body.optString("source_sha256", ""),
                    body.optString("config_sha256", ""),
                    body.optString("provider", ""),
                    body.optString("model", body.optString("model_override", "")),
                    body.optBoolean("route_rate_limits_ready", false),
                    body.optBoolean("current_source_ready", false),
                    body.optBoolean("voice_ready", false));
        } catch (Exception ignored) {
            health = null;
        } finally {
            if (connection != null) connection.disconnect();
        }
        ProtectedBackendCapabilityPolicy.Decision decision = evaluate(health);
        store(context, health, decision);
        return decision;
    }

    private static ProtectedBackendCapabilityPolicy.Decision evaluate(
            ProtectedBackendCapabilityPolicy.Health health) {
        return ProtectedBackendCapabilityPolicy.evaluate(
                SarahModelConfig.backendUrl(),
                SarahModelConfig.backendToken(),
                SarahModelConfig.buildCommit(),
                SarahModelConfig.expectedDeploymentId(),
                SarahModelConfig.expectedWorkerSourceSha256(),
                SarahModelConfig.expectedWorkerConfigSha256(),
                SarahModelConfig.PROVIDER_ID,
                SarahModelConfig.MODEL_ID,
                health);
    }

    private static void store(
            Context context,
            ProtectedBackendCapabilityPolicy.Health health,
            ProtectedBackendCapabilityPolicy.Decision decision) {
        if (context == null) return;
        SharedPreferences.Editor edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .putLong("checked_at", System.currentTimeMillis())
                .putString("identity_key", identityKey())
                .putBoolean("contract_verified", decision != null && decision.contractVerified);
        if (health != null) {
            edit.putBoolean("http_ok", health.httpOk)
                    .putBoolean("ok", health.ok)
                    .putString("service", health.service)
                    .putString("contract_version", health.contractVersion)
                    .putBoolean("deployment_ready", health.deploymentReady)
                    .putString("deployment_id", health.deploymentId)
                    .putString("source_sha256", health.sourceSha256)
                    .putString("config_sha256", health.configSha256)
                    .putString("provider", health.provider)
                    .putString("model", health.model)
                    .putBoolean("route_rate_limits_ready", health.routeRateLimitsReady)
                    .putBoolean("current_source_ready", health.currentSourceReady)
                    .putBoolean("voice_ready", health.voiceReady);
        }
        edit.apply();
    }

    private static String identityKey() {
        return CACHE_CONTRACT + "|"
                + SarahModelConfig.backendUrl() + "|"
                + tokenFingerprint(SarahModelConfig.backendToken()) + "|"
                + SarahModelConfig.buildCommit() + "|"
                + SarahModelConfig.expectedDeploymentId() + "|"
                + SarahModelConfig.expectedWorkerSourceSha256() + "|"
                + SarahModelConfig.expectedWorkerConfigSha256() + "|"
                + SarahModelConfig.PROVIDER_ID + "|"
                + SarahModelConfig.MODEL_ID;
    }

    private static boolean directOpenAiReady() {
        return SarahModelConfig.backendUrl().isEmpty()
                && "openai".equals(SarahModelConfig.PROVIDER_ID)
                && !SarahModelConfig.openAiApiKey().isEmpty();
    }

    private static String tokenFingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((token == null ? "" : token).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) value.append(String.format(java.util.Locale.US, "%02x", item));
            return value.toString();
        } catch (Exception ignored) {
            return "TOKEN_FINGERPRINT_UNAVAILABLE";
        }
    }

    private static String capabilitiesEndpoint() {
        String base = SarahModelConfig.backendUrl().trim();
        if (!base.startsWith("https://") || SarahModelConfig.backendToken().isEmpty()) return "";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        for (String suffix : new String[]{"/chat", "/search", "/voice"}) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
                break;
            }
        }
        return base + "/capabilities";
    }

    private static byte[] bounded(InputStream stream) throws Exception {
        if (stream == null) return new byte[0];
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > MAX_BYTES) {
                    throw new IllegalStateException("Protected capability response exceeded limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }
}
