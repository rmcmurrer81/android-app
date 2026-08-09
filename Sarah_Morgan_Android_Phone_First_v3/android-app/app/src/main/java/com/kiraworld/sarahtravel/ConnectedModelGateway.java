package com.kiraworld.sarahtravel;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single routing point for connected conversation providers.
 *
 * A team-owned protected backend is the normal hackathon path. End users do
 * not choose a provider or enter a model key in Settings.
 */
public final class ConnectedModelGateway {
    private ConnectedModelGateway() { }

    /** Cancels only the exact worker thread that owns the timed-out attempt. */
    public static void cancel(Thread worker) {
        SarahBackendClient.cancel(worker);
        OpenAIClient.cancel(worker);
    }

    public static String respond(
            String providerId,
            String apiKey,
            String model,
            String systemPrompt,
            List<Map<String, String>> history,
            String message,
            boolean webSearch,
            byte[] imageJpeg) throws Exception {
        return respondDetailed(
                providerId, apiKey, model, systemPrompt, history,
                message, webSearch, imageJpeg).reply;
    }

    public static ConnectedModelResponse respondDetailed(
            String providerId,
            String apiKey,
            String model,
            String systemPrompt,
            List<Map<String, String>> history,
            String message,
            boolean webSearch,
            byte[] imageJpeg) throws Exception {
        return respondDetailed(
                providerId, apiKey, model, systemPrompt, history, message,
                webSearch, message, imageJpeg);
    }

    public static ConnectedModelResponse respondDetailed(
            String providerId,
            String apiKey,
            String model,
            String systemPrompt,
            List<Map<String, String>> history,
            String message,
            boolean webSearch,
            String searchQuery,
            byte[] imageJpeg) throws Exception {
        return respondDetailed(
                providerId, apiKey, model, systemPrompt, history, message,
                webSearch, searchQuery, imageJpeg, 1,
                ConnectedTurnPolicy.MAX_NETWORK_WAIT_MS);
    }

    public static ConnectedModelResponse respondDetailed(
            String providerId,
            String apiKey,
            String model,
            String systemPrompt,
            List<Map<String, String>> history,
            String message,
            boolean webSearch,
            String searchQuery,
            byte[] imageJpeg,
            int attemptNumber,
            long remainingBudgetMs) throws Exception {
        String normalized = providerId == null ? SarahModelConfig.PROVIDER_ID : providerId.trim().toLowerCase(Locale.US);
        boolean effectiveWebSearch = webSearch;

        String backend = SarahModelConfig.backendUrl();
        if (!backend.isEmpty()) {
            return SarahBackendClient.respondDetailed(
                    backend,
                    normalized,
                    model,
                    systemPrompt,
                    history,
                    message,
                    effectiveWebSearch,
                    searchQuery,
                    imageJpeg,
                    attemptNumber,
                    remainingBudgetMs);
        }

        if (normalized.isEmpty() || "openai".equals(normalized)) {
            String effectiveKey = apiKey == null || apiKey.trim().isEmpty()
                    ? SarahModelConfig.apiKey()
                    : apiKey.trim();
            if (effectiveKey.isEmpty()) {
                throw new IllegalStateException(
                        "The connected mind is not available in this build. Supported public lookups and offline conversation remain available.");
            }
            return OpenAIClient.respondDetailed(
                    effectiveKey,
                    model == null || model.trim().isEmpty() ? SarahModelConfig.MODEL_ID : model.trim(),
                    effectiveWebSearch && searchQuery != null && !searchQuery.trim().isEmpty()
                            ? systemPrompt + "\nCURRENT SOURCE SEARCH CONTEXT: " + searchQuery.trim()
                            : systemPrompt,
                    history,
                    message,
                    effectiveWebSearch,
                    imageJpeg,
                    attemptNumber,
                    remainingBudgetMs);
        }

        if ("workers-ai".equals(normalized)
                || "cloudflare".equals(normalized)
                || "cloudflare-workers-ai".equals(normalized)) {
            throw new IllegalStateException(
                    "Sarah’s connected mind is unavailable. Supported public lookups and offline conversation remain available.");
        }

        throw new IllegalArgumentException("The selected connected conversation is unavailable.");
    }
}
