package com.kiraworld.sarahtravel;

/** Optional supervised hotel-contact voice service owned by the team. */
public final class VoiceConciergeConfig {
    private VoiceConciergeConfig() { }

    public static String endpoint() {
        return clean(BuildConfig.SARAH_VOICE_CONCIERGE_URL);
    }

    public static String token() {
        return clean(BuildConfig.SARAH_VOICE_CONCIERGE_TOKEN);
    }

    public static boolean isConfigured() {
        return endpoint().startsWith("https://");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
