package com.kiraworld.sarahtravel;

/** Pure ordering/lifecycle gate for one owner-visible conversation turn at a time. */
public final class TurnLifecyclePolicy {
    private TurnLifecyclePolicy() { }

    public static boolean canSubmit(boolean destroyed, boolean turnInFlight) {
        return !destroyed && !turnInFlight;
    }

    public static boolean completionMayApply(
            boolean destroyed,
            long submittedGeneration,
            long currentGeneration) {
        return !destroyed && submittedGeneration == currentGeneration;
    }

    /** A completion also belongs to the same exact active person and display speaker. */
    public static boolean speakerCompletionMayApply(
            boolean destroyed,
            long submittedGeneration,
            long currentGeneration,
            String submittedPersonId,
            String activePersonId,
            String submittedSpeaker,
            String activeSpeaker) {
        return completionMayApply(destroyed, submittedGeneration, currentGeneration)
                && sameNonEmpty(submittedPersonId, activePersonId)
                && sameNonEmpty(submittedSpeaker, activeSpeaker);
    }

    public static boolean nextTurnCanSeePriorReply(
            boolean turnInFlight,
            boolean priorAssistantCommitted) {
        return !turnInFlight && priorAssistantCommitted;
    }

    private static boolean sameNonEmpty(String left, String right) {
        String safeLeft = left == null ? "" : left.trim();
        String safeRight = right == null ? "" : right.trim();
        return !safeLeft.isEmpty()
                && !safeRight.isEmpty()
                && safeLeft.equalsIgnoreCase(safeRight);
    }
}
