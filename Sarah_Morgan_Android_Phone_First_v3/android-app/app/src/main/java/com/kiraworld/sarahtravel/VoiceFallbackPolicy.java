package com.kiraworld.sarahtravel;

/** Prevents a full generic replay after approved progressive audio already began. */
public final class VoiceFallbackPolicy {
    private VoiceFallbackPolicy() { }

    public static boolean shouldStartAndroidFallback(long approvedPlaybackStart, String failureReason) {
        if (approvedPlaybackStart > 0) return false;
        String reason = failureReason == null ? "" : failureReason;
        return !"interrupted_by_new_voice_request".equals(reason)
                && !"superseded_before_playback".equals(reason)
                && !"cancelled_by_lifecycle".equals(reason);
    }
}
