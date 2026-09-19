package com.kiraworld.sarahtravel;

/** Auditable timing for every displayed text path, including local and fallback replies. */
public final class TextTurnReceipt {
    private TextTurnReceipt() { }

    public static String build(
            String route,
            String provider,
            String model,
            long turnSubmittedAt,
            long textCompletedAt) {
        return build(
                "legacy-turn-" + turnSubmittedAt,
                route,
                provider,
                model,
                turnSubmittedAt,
                textCompletedAt);
    }

    public static String build(
            String turnId,
            String route,
            String provider,
            String model,
            long turnSubmittedAt,
            long textCompletedAt) {
        long completed = Math.max(turnSubmittedAt, textCompletedAt);
        return "Text turn receipt: turn_id=" + clean(turnId, "unknown-turn")
                + "; route=" + clean(route, TurnRoute.UNKNOWN_LEGACY)
                + "; provider=" + clean(provider, "unknown")
                + "; model=" + clean(model, "unknown")
                + "; turn_submitted_at=" + turnSubmittedAt
                + "; model_load_start_at=UNAVAILABLE_ROUTE_DOES_NOT_EXPOSE"
                + "; model_load_end_at=UNAVAILABLE_ROUTE_DOES_NOT_EXPOSE"
                + "; first_token_at=UNAVAILABLE_NON_STREAMING_OR_LOCAL"
                + "; text_completed_at=" + completed
                + "; text_latency_ms=" + Math.max(0L, completed - turnSubmittedAt)
                + ".";
    }

    private static String clean(String value, String fallback) {
        String exact = value == null ? "" : value.trim();
        return exact.isEmpty() ? fallback : exact;
    }
}
