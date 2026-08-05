package com.kiraworld.sarahtravel;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Single routing point for connected conversation providers.
 *
 * OpenAI Responses is the included adapter. Teams can add Claude, Gemini,
 * Bedrock, or another provider here without changing MainActivity.
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
        if (normalized.isEmpty() || "openai".equals(normalized)) {
            return OpenAIClient.respond(
                    apiKey,
                    model,
                    systemPrompt,
                    history,
                    message,
                    webSearch,
                    imageJpeg);
        }
        throw new IllegalArgumentException(
                "Connected provider '" + normalized + "' is not installed. See README.md for adding an adapter.");
    }
}
