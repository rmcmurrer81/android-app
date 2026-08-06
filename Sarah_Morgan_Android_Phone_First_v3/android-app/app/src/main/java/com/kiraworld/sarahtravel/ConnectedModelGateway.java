package com.kiraworld.sarahtravel;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single routing point for connected conversation providers.
 *
 * OpenAI is the included provider for the hackathon build. End users do not
 * choose a provider or enter a model key in Settings. The team changes the
 * provider in SarahModelConfig and this gateway.
 */
public final class ConnectedModelGateway {
    private ConnectedModelGateway() { }

    public static String respond(
            String providerId,
            String apiKey,
            String model,
            String systemPrompt,
            List<Map<String, String>> history,
            String message,
            boolean webSearch,
            byte[] imageJpeg) throws Exception {
        String normalized = providerId == null ? "openai" : providerId.trim().toLowerCase(Locale.US);
        boolean effectiveWebSearch = webSearch || LiveTravelIntent.needsCurrentSources(message);

        String backend = SarahModelConfig.backendUrl();
        if (!backend.isEmpty()) {
            return SarahBackendClient.respond(
                    backend,
                    normalized,
                    model,
                    systemPrompt,
                    history,
                    message,
                    effectiveWebSearch,
                    imageJpeg);
        }

        if (normalized.isEmpty() || "openai".equals(normalized)) {
            String effectiveKey = apiKey == null || apiKey.trim().isEmpty()
                    ? SarahModelConfig.apiKey()
                    : apiKey.trim();
            if (effectiveKey.isEmpty()) {
                throw new IllegalStateException(
                        "The team OpenAI connection is not present in this build. Public lookup and Local mode remain available.");
            }
            return OpenAIClient.respond(
                    effectiveKey,
                    model == null || model.trim().isEmpty() ? SarahModelConfig.MODEL_ID : model.trim(),
                    systemPrompt,
                    history,
                    message,
                    effectiveWebSearch,
                    imageJpeg);
        }

        throw new IllegalArgumentException(
                "Connected provider '" + normalized + "' is not installed. See README.md for the exact adapter files to change.");
    }
}
