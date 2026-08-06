package com.kiraworld.sarahtravel;

/**
 * Team-owned ElevenLabs configuration for Sarah's premium online voice.
 *
 * The person installing Sarah is never asked for an ElevenLabs key. For a
 * private hackathon APK the build may inject a tightly restricted key. A
 * public release should use the protected backend fields instead.
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
        return clean(BuildConfig.SARAH_ELEVENLABS_API_KEY);
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
        return clean(BuildConfig.SARAH_ELEVENLABS_BACKEND_URL);
    }

    public static String backendToken() {
        return clean(BuildConfig.SARAH_ELEVENLABS_BACKEND_TOKEN);
    }

    public static boolean directConfigured() {
        return !apiKey().isEmpty() && !voiceId().isEmpty();
    }

    public static boolean backendConfigured() {
        return backendUrl().startsWith("https://") && !voiceId().isEmpty();
    }

    public static boolean isConfigured() {
        return backendConfigured() || directConfigured();
    }

    public static String statusLabel() {
        if (backendConfigured()) return "ElevenLabs voice through Sarah's protected backend";
        if (directConfigured()) return "ElevenLabs voice included in this private test build";
        return "Sarah Morgan voice selected • ElevenLabs service credential not included";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
