package com.kiraworld.sarahtravel;

/** Pure gates for bounded, opt-in background research and travel monitoring. */
public final class BackgroundResearchPolicy {
    public static final int MAX_PACKS_PER_RUN = 2;
    public static final int MAX_DISCOVERIES_PER_QUERY = 4;
    public static final boolean DEFAULT_BACKGROUND_MONITORING_ENABLED = false;

    private BackgroundResearchPolicy() { }

    public static boolean canRun(
            boolean validatedInternet,
            boolean connectedBackend,
            boolean enabled,
            boolean owner,
            boolean memoryConsent,
            String destination,
            String interests) {
        return validatedInternet
                && connectedBackend
                && enabled
                && owner
                && memoryConsent
                && (!clean(destination).isEmpty() || !clean(interests).isEmpty());
    }

    public static String unavailableStatus() {
        return "Saved request · automatic checking is not connected";
    }

    public static boolean leaseStillValid(
            String expectedPersonId,
            String currentPersonId,
            boolean enabled,
            boolean owner,
            boolean memoryConsent,
            boolean interrupted) {
        return !interrupted
                && enabled
                && owner
                && memoryConsent
                && !clean(expectedPersonId).isEmpty()
                && clean(expectedPersonId).equals(clean(currentPersonId));
    }

    /** A saved watch alone never opts the owner into background monitoring. */
    public static boolean monitoringCanRun(
            boolean explicitOptIn,
            boolean monitoringRouteConfigured,
            boolean eligibleWorkExists) {
        return explicitOptIn && monitoringRouteConfigured && eligibleWorkExists;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
