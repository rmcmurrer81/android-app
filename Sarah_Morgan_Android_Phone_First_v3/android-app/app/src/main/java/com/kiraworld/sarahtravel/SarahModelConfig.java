package com.kiraworld.sarahtravel;

/**
 * Build-owned conversation model configuration.
 *
 * End users do not choose a provider, model, or API key in Sarah's Settings.
 * The hackathon team controls those choices here, through build variables,
 * and in ConnectedModelGateway.
 */
public final class SarahModelConfig {
    /** Provider selected by the team build; ordinary users never enter provider keys. */
    public static final String PROVIDER_ID = configuredProviderId();

    /** Safe repository default; the online-judge workflow can override this without editing Java. */
    public static final String DEFAULT_MODEL_ID = "@cf/google/gemma-4-26b-a4b-it";

    /** Provider model selected by SARAH_MODEL_ID for this build. */
    public static final String MODEL_ID = configuredModelId();

    private static final String VOICE_READY_MARKER = "__SARAH_ELEVENLABS_VOICE_READY__";

    private SarahModelConfig() { }

    /** Actual direct OpenAI credential, if a private test build injects one. */
    public static String openAiApiKey() {
        return clean(BuildConfig.SARAH_OPENAI_API_KEY);
    }

    /**
     * Compatibility accessor retained for existing activity code.
     *
     * It returns the real OpenAI key when present. If only Sarah's ElevenLabs
     * voice is configured, it returns a harmless non-secret marker so the
     * legacy voice branch runs. SecureStore deliberately uses openAiApiKey()
     * instead, so this marker is never sent to the conversation model.
     */
    public static String apiKey() {
        String modelKey = openAiApiKey();
        if (!modelKey.isEmpty()) return modelKey;
        return ElevenLabsVoiceConfig.isConfigured() ? VOICE_READY_MARKER : "";
    }

    /**
     * Optional protected Sarah backend. When configured, the team routes the
     * selected provider through server-side bindings instead of embedding a key.
     */
    public static String backendUrl() {
        return clean(BuildConfig.SARAH_MODEL_BACKEND_URL);
    }

    /** Optional build-owned bearer token used by SarahBackendClient. */
    public static String backendToken() {
        return clean(BuildConfig.SARAH_MODEL_BACKEND_TOKEN);
    }

    public static boolean fullConversationAvailable() {
        return !backendUrl().isEmpty() || !openAiApiKey().isEmpty();
    }

    public static String providerLabel() {
        if ("workers-ai".equals(PROVIDER_ID)) return "Cloudflare Workers AI";
        if ("openai".equals(PROVIDER_ID)) return "OpenAI";
        return "Team online mind";
    }

    public static String modelLabel() {
        return MODEL_ID;
    }

    private static String configuredModelId() {
        String configured = clean(BuildConfig.SARAH_MODEL_ID);
        if (configured.isEmpty()) configured = clean(BuildConfig.SARAH_OPENAI_MODEL);
        return configured.isEmpty() ? DEFAULT_MODEL_ID : configured;
    }

    private static String configuredProviderId() {
        String configured = clean(BuildConfig.SARAH_MODEL_PROVIDER).toLowerCase(java.util.Locale.US);
        if ("cloudflare".equals(configured) || "cloudflare-workers-ai".equals(configured)) {
            return "workers-ai";
        }
        return configured.isEmpty() ? "workers-ai" : configured;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
