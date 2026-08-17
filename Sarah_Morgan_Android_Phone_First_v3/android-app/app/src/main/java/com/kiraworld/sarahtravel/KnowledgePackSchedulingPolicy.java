package com.kiraworld.sarahtravel;

/** Pure fail-closed gates separating a saved request from runnable Android work. */
public final class KnowledgePackSchedulingPolicy {
    public static final String PENDING_NOT_SCHEDULED = "PENDING_NOT_SCHEDULED";
    public static final String PENDING_SCHEDULED = "PENDING_SCHEDULED";
    private KnowledgePackSchedulingPolicy() { }

    public static boolean canRequest(boolean memoryConsent, String destination) {
        return memoryConsent && destination != null && !destination.trim().isEmpty();
    }

    public static boolean canSchedule(
            boolean owner,
            boolean memoryConsent,
            boolean validatedInternet,
            boolean conversationConfigured,
            boolean sourceConfigured,
            boolean ownerOptIn) {
        return owner
                && memoryConsent
                && validatedInternet
                && conversationConfigured
                && sourceConfigured
                && ownerOptIn;
    }

    /**
     * Whether Settings may truthfully offer the background-research opt-in.
     * A stale saved true value is not availability: every prerequisite must
     * be true at the point the setting is shown or saved.
     */
    public static boolean settingsCanEnable(
            boolean owner,
            boolean memoryConsent,
            boolean validatedInternet,
            boolean conversationConfigured,
            boolean sourceConfigured,
            boolean webEnabled,
            boolean localOnly) {
        return canSchedule(
                owner,
                memoryConsent,
                validatedInternet,
                conversationConfigured,
                sourceConfigured,
                true)
                && webEnabled
                && !localOnly;
    }

    /** Persist true only when the requested setting is currently runnable. */
    public static boolean persistEnabled(
            boolean requested,
            boolean owner,
            boolean memoryConsent,
            boolean validatedInternet,
            boolean conversationConfigured,
            boolean sourceConfigured,
            boolean webEnabled,
            boolean localOnly) {
        return requested && settingsCanEnable(
                owner,
                memoryConsent,
                validatedInternet,
                conversationConfigured,
                sourceConfigured,
                webEnabled,
                localOnly);
    }

    /** Human-readable reason shown in Settings instead of a false active state. */
    public static String settingsLabel(
            boolean owner,
            boolean memoryConsent,
            boolean validatedInternet,
            boolean conversationConfigured,
            boolean sourceConfigured,
            boolean webEnabled,
            boolean localOnly) {
        if (!owner) {
            return "Background destination and event research is off - owner profile required";
        }
        if (!memoryConsent) {
            return "Background destination and event research is off - memory permission required";
        }
        if (!conversationConfigured || !sourceConfigured) {
            return "Background destination and event research is off - protected research setup required";
        }
        if (!webEnabled) {
            return "Background destination and event research is off - turn on public research above first";
        }
        if (localOnly) {
            return "Background destination and event research is off - choose Automatic or Connected mode first";
        }
        if (!validatedInternet) {
            return "Background destination and event research is off - validated internet connection required";
        }
        return "Automatically refresh destination and event knowledge when internet is available";
    }

    public static String pendingState(boolean schedulerAccepted) {
        return schedulerAccepted
                ? PENDING_SCHEDULED
                : PENDING_NOT_SCHEDULED;
    }
}
