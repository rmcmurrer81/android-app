package com.kiraworld.sarahtravel;

/**
 * Build-owned conversation model configuration.
 *
 * End users do not choose a provider, model, or API key in Sarah's Settings.
 * The hackathon team controls those choices here and in ConnectedModelGateway.
 */
public final class SarahModelConfig {
    /** Included provider adapter. Change with the provider branch in ConnectedModelGateway. */
    public static final String PROVIDER_ID = "openai";

    /** Higher-intelligence OpenAI model used by the hackathon build. */
    public static final String MODEL_ID = "gpt-5.1";

    private SarahModelConfig() { }

    /**
     * Private hackathon builds may inject an OpenAI key through the
     * SARAH_OPENAI_API_KEY GitHub Actions secret. A public release should use
     * a protected backend instead of embedding a shared provider key in an APK.
     */
    public static String apiKey() {
        return clean(BuildConfig.SARAH_OPENAI_API_KEY);
    }

    /**
     * Optional protected Sarah backend. When configured, the team may route
     * OpenAI through server-side credentials instead of embedding a key.
     */
    public static String backendUrl() {
        return clean(BuildConfig.SARAH_MODEL_BACKEND_URL);
    }

    /** Optional build-owned bearer token used by SarahBackendClient. */
    public static String backendToken() {
        return clean(BuildConfig.SARAH_MODEL_BACKEND_TOKEN);
    }

    public static boolean fullConversationAvailable() {
        return !backendUrl().isEmpty() || !apiKey().isEmpty();
    }

    public static String providerLabel() {
        return "OpenAI";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
