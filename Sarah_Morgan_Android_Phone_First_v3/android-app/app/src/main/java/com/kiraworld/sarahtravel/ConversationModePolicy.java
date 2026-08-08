package com.kiraworld.sarahtravel;

/** Pure routing rules for Sarah's connected/local conversation modes. */
public final class ConversationModePolicy {
    public static final int MODE_AUTO = 0;
    public static final int MODE_SMART_PREFERRED = 1;
    public static final int MODE_LOCAL_ONLY = 2;

    public static final String ROUTE_SMART = "smart";
    public static final String ROUTE_LOCAL = "local";

    private ConversationModePolicy() { }

    public static String route(int mode, boolean validatedInternet, boolean teamModelAvailable) {
        if (mode == MODE_LOCAL_ONLY) return ROUTE_LOCAL;
        return validatedInternet && teamModelAvailable ? ROUTE_SMART : ROUTE_LOCAL;
    }

    public static boolean prefersConnectedModel(int mode) {
        return mode == MODE_AUTO || mode == MODE_SMART_PREFERRED;
    }

    public static String statusLabel(
            int mode,
            boolean validatedInternet,
            boolean teamModelAvailable,
            boolean lastConnectedCallFailed) {
        if (mode == MODE_LOCAL_ONLY) return validatedInternet ? "Local only" : "Local only • offline";

        String prefix = mode == MODE_AUTO ? "Automatic" : "Online mind preferred";
        if (!validatedInternet) return prefix + " • Local • offline";
        if (!teamModelAvailable) return prefix + " • Public web online • online mind not included in this build";
        if (lastConnectedCallFailed) return prefix + " • Public/local fallback";
        return prefix + " • Online mind connected";
    }
}
