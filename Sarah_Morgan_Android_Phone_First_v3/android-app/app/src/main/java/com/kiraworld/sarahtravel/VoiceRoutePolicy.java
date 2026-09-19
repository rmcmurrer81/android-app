package com.kiraworld.sarahtravel;

/** Pure fail-closed route selection for normal-chat speech. */
public final class VoiceRoutePolicy {
    private VoiceRoutePolicy() { }

    public static boolean shouldAttemptPremium(
            int voiceMode,
            boolean validatedInternet,
            boolean protectedBackendConfigured,
            boolean directCredentialConfigured) {
        return voiceMode == 1
                && validatedInternet
                && (protectedBackendConfigured || directCredentialConfigured);
    }

    public static String fallbackReason(
            int voiceMode,
            boolean validatedInternet,
            boolean protectedBackendConfigured,
            boolean directCredentialConfigured) {
        if (voiceMode != 1) return "owner_selected_android_voice";
        if (!validatedInternet) return "validated_internet_unavailable";
        if (!protectedBackendConfigured && !directCredentialConfigured) {
            return "approved_elevenlabs_route_not_configured";
        }
        return "premium_route_runtime_failure";
    }
}
