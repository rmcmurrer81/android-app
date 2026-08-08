package com.kiraworld.sarahtravel;

/**
 * Public build identity plus owner-activated protected-route configuration.
 *
 * The team selects a provider/model and may include a non-secret suggested
 * HTTPS route. A confirmed owner supplies the separate revocable Sarah access
 * code after installation; no reusable credential is compiled into the APK.
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

    /** Direct provider credentials are intentionally unsupported in artifacts. */
    public static String openAiApiKey() {
        return "";
    }

    /**
     * Compatibility accessor retained for existing activity code.
     *
     * If Sarah's protected voice is configured, this returns a harmless
     * non-secret marker so the
     * legacy voice branch runs. SecureStore deliberately uses openAiApiKey()
     * instead, so this marker is never sent to the conversation model.
     */
    public static String apiKey() {
        String modelKey = openAiApiKey();
        if (!modelKey.isEmpty()) return modelKey;
        return ElevenLabsVoiceConfig.isConfigured() ? VOICE_READY_MARKER : "";
    }

    /**
     * Owner-activated protected Sarah backend. The encrypted runtime route wins
     * over an optional non-secret address suggested by the build.
     */
    public static String backendUrl() {
        android.content.Context context = SarahApplication.appContext();
        String activated = context == null ? "" : SecureStore.loadSarahBackendUrl(context);
        if (!activated.isEmpty()) return activated;
        return clean(BuildConfig.SARAH_MODEL_BACKEND_URL);
    }

    /** Revocable app access code loaded only from Android Keystore storage. */
    public static String backendToken() {
        android.content.Context context = SarahApplication.appContext();
        return context == null ? "" : SecureStore.loadSarahBackendToken(context);
    }

    public static String expectedDeploymentId() {
        return clean(BuildConfig.SARAH_EXPECTED_DEPLOYMENT_ID);
    }

    public static String expectedWorkerSourceSha256() {
        return clean(BuildConfig.SARAH_EXPECTED_WORKER_SOURCE_SHA256).toLowerCase(java.util.Locale.US);
    }

    public static String expectedWorkerConfigSha256() {
        return clean(BuildConfig.SARAH_EXPECTED_WORKER_CONFIG_SHA256).toLowerCase(java.util.Locale.US);
    }

    public static String buildCommit() {
        return clean(BuildConfig.SARAH_BUILD_COMMIT);
    }

    /** Build configuration exists, but this does not claim that the route is healthy. */
    public static boolean conversationConfigured() {
        if (!backendUrl().isEmpty()) return protectedBackendConfigured();
        return directOpenAiConfigured();
    }

    public static boolean protectedBackendConfigured() {
        return backendUrl().startsWith("https://") && !backendToken().isEmpty();
    }

    public static boolean directOpenAiConfigured() {
        return false;
    }

    /** A route is available only after the exact protected contract is verified. */
    public static boolean fullConversationAvailable() {
        if (!backendUrl().isEmpty()) {
            android.content.Context context = SarahApplication.appContext();
            return protectedBackendConfigured()
                    && context != null
                    && ProtectedBackendCapabilities.conversationReady(context);
        }
        return directOpenAiConfigured();
    }

    public static String providerLabel() {
        if ("workers-ai".equals(PROVIDER_ID)) return "Cloudflare Workers AI";
        if ("openai".equals(PROVIDER_ID)) return "OpenAI";
        return "Connected online mind";
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
