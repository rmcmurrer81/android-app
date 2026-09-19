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
            boolean lastConnectedCallFailed,
            boolean connectedRouteProven) {
        return statusLabel(mode, validatedInternet, teamModelAvailable,
                lastConnectedCallFailed, connectedRouteProven, false, false);
    }

    public static String statusLabel(
            int mode,
            boolean validatedInternet,
            boolean teamModelAvailable,
            boolean lastConnectedCallFailed,
            boolean connectedRouteProven,
            boolean checkingConnection,
            boolean reconnecting) {
        if (mode == MODE_LOCAL_ONLY) return "Offline mind ready · local-only mode";
        if (!validatedInternet) return "Offline mind ready · no validated internet";
        if (reconnecting) return "Reconnecting… · offline mind remains ready";
        if (checkingConnection) return "Checking connection… · offline mind remains ready";
        if (!teamModelAvailable) return "Online unavailable · offline mind ready";
        if (lastConnectedCallFailed) return "Online unavailable · offline mind ready · next turn will retry";
        if (!connectedRouteProven) return "Online route verified · waiting for the first connected reply";
        return "Online mind ready · verified by a recent connected reply";
    }
}
