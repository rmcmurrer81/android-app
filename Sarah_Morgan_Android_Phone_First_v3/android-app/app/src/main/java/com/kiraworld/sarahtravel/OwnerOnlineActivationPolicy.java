package com.kiraworld.sarahtravel;

/** Pure owner-facing policy for the boundary between internet and authenticated Sarah access. */
public final class OwnerOnlineActivationPolicy {
    private OwnerOnlineActivationPolicy() { }

    public static boolean suggestedProtectedRoute(String backendUrl) {
        return backendUrl != null && backendUrl.trim().startsWith("https://");
    }

    public static boolean needsActivation(
            boolean validatedInternet,
            boolean confirmedOwner,
            String backendUrl,
            String backendToken) {
        return validatedInternet
                && confirmedOwner
                && suggestedProtectedRoute(backendUrl)
                && (backendToken == null || backendToken.trim().isEmpty());
    }

    public static String status(
            boolean validatedInternet,
            boolean confirmedOwner,
            String backendUrl,
            String backendToken) {
        if (!validatedInternet) return "Offline mind ready - no validated internet";
        if (!suggestedProtectedRoute(backendUrl)) {
            return "Internet is ready - Sarah's protected service address is missing";
        }
        if (backendToken == null || backendToken.trim().isEmpty()) {
            return confirmedOwner
                    ? "Internet is ready - enter your private Sarah access code once - tap to connect"
                    : "Internet is ready - the confirmed owner must connect Sarah online";
        }
        return "Internet is ready - checking Sarah's protected connection";
    }
}
