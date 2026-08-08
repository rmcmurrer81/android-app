package com.kiraworld.sarahtravel;

/**
 * Team-owned ElevenLabs configuration for Sarah's premium online voice.
 *
 * Provider keys are never accepted by the app or compiled into an artifact.
 * Voice uses the same owner-activated, revocable Sarah backend access as text.
 */
public final class ElevenLabsVoiceConfig {
    /** Voice Design ID for the original Sarah Morgan voice. Voice IDs are not credentials. */
    public static final String DEFAULT_VOICE_ID = "WcGvc9xxaOYbKswm3NBx";
    public static final String DEFAULT_MODEL_ID = "eleven_multilingual_v2";
    public static final String OUTPUT_FORMAT = "mp3_44100_128";

    // These match the Sarah Morgan Voice Design samples supplied for testing.
    public static final double STABILITY = 0.50;
    public static final double SIMILARITY_BOOST = 0.75;
    public static final double STYLE = 0.0;
    public static final double SPEED = 1.0;
    public static final boolean SPEAKER_BOOST = true;

    private ElevenLabsVoiceConfig() { }

    public static String apiKey() {
        return "";
    }

    public static String voiceId() {
        String configured = clean(BuildConfig.SARAH_ELEVENLABS_VOICE_ID);
        return configured.isEmpty() ? DEFAULT_VOICE_ID : configured;
    }

    public static String modelId() {
        String value = clean(BuildConfig.SARAH_ELEVENLABS_MODEL_ID);
        return value.isEmpty() ? DEFAULT_MODEL_ID : value;
    }

    public static String backendUrl() {
        String root = protectedRoot(SarahModelConfig.backendUrl());
        return root.isEmpty() ? "" : root + "/voice";
    }

    public static String backendToken() {
        return SarahModelConfig.backendToken();
    }

    public static boolean directConfigured() {
        return !apiKey().isEmpty() && !voiceId().isEmpty();
    }

    public static boolean backendConfigured() {
        return backendUrl().startsWith("https://")
                && !backendToken().isEmpty()
                && !voiceId().isEmpty()
                && backendToken().equals(SarahModelConfig.backendToken())
                && protectedRoot(backendUrl()).equalsIgnoreCase(
                        protectedRoot(SarahModelConfig.backendUrl()));
    }

    public static boolean isConfigured() {
        return backendConfigured() || directConfigured();
    }

    public static String statusLabel() {
        if (backendConfigured()) return "ElevenLabs voice through Sarah's protected backend";
        if (directConfigured()) return "ElevenLabs voice included in this private test build";
        return "Sarah Morgan voice selected • ElevenLabs service credential not included";
    }

    public static String humanModelLabel() {
        String model = modelId();
        if ("eleven_flash_v2_5".equals(model)) return "Eleven Flash v2.5";
        if ("eleven_multilingual_v2".equals(model)) return "Eleven Multilingual v2";
        if ("eleven_turbo_v2_5".equals(model)) return "Eleven Turbo v2.5";
        return "configured ElevenLabs model";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String protectedRoot(String value) {
        String root = clean(value);
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        for (String suffix : new String[]{"/voice", "/chat", "/search"}) {
            if (root.endsWith(suffix)) {
                root = root.substring(0, root.length() - suffix.length());
                break;
            }
        }
        return root;
    }
}
