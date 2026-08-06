package com.kiraworld.sarahtravel;

/** Build-owned configuration for hotel, transport, and experience inventory. */
public final class TravelCommerceConfig {
    private TravelCommerceConfig() { }

    public static String endpoint() {
        return clean(BuildConfig.SARAH_TRAVEL_COMMERCE_URL);
    }

    public static String token() {
        return clean(BuildConfig.SARAH_TRAVEL_COMMERCE_TOKEN);
    }

    public static boolean isConfigured() {
        return endpoint().startsWith("https://");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
