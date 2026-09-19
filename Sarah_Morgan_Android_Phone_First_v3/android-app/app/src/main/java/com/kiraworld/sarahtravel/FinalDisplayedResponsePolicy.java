package com.kiraworld.sarahtravel;

import java.util.Locale;

/** Final bounded selection gate for route truth and follow-up destination continuity. */
public final class FinalDisplayedResponsePolicy {
    public static final class Selection {
        public final String reply;
        public final String route;
        public final boolean usedConnectedReply;
        public final String reason;

        Selection(String reply, String route, boolean usedConnectedReply, String reason) {
            this.reply = reply == null ? "" : reply.trim();
            this.route = route;
            this.usedConnectedReply = usedConnectedReply;
            this.reason = reason;
        }
    }

    private FinalDisplayedResponsePolicy() { }

    public static Selection select(
            String message,
            String destination,
            String plannerReply,
            String connectedReply,
            String connectedRoute,
            boolean webRequested,
            boolean verifiedWebReceipt) {
        String local = clean(plannerReply);
        String online = clean(connectedReply);
        if (webRequested && !verifiedWebReceipt) {
            String safe = local.isEmpty()
                    ? "That needs current sources. The connected reply did not include a verified search receipt, so I will not present it as current information."
                    : local + " I did not receive a verified current-source receipt for this turn, so I will not invent live prices or availability.";
            return new Selection(safe, TurnRoute.TOOL_UNAVAILABLE, false, "missing_verified_web_receipt");
        }

        boolean continuityCritical = TravelPlanningConversationPolicy.asksForShortTripRecommendation(message)
                || TravelPlanningConversationPolicy.asksForCurrentCost(message);
        String place = clean(destination);
        if (continuityCritical
                && !place.isEmpty()
                && !containsIgnoreCase(online, place)
                && !local.isEmpty()) {
            return new Selection(
                    local,
                    webRequested ? TurnRoute.TOOL_UNAVAILABLE : TurnRoute.LOCAL_TOOL_RESULT,
                    false,
                    "connected_reply_dropped_active_destination");
        }
        return new Selection(online, connectedRoute, true, "connected_reply_retained");
    }

    private static boolean containsIgnoreCase(String value, String expected) {
        return value.toLowerCase(Locale.US).contains(expected.toLowerCase(Locale.US));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
