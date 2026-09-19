package com.kiraworld.sarahtravel;

/** Pure classification for writes backed by owner-global, unscoped stores. */
public final class AgenticGlobalActionPolicy {
    private AgenticGlobalActionPolicy() { }

    public static boolean requiresExactConfirmedOwner(String actionType) {
        return AgenticTravelPlanner.SAVE_WISH.equals(actionType)
                || AgenticTravelPlanner.CREATE_DEAL_WATCH.equals(actionType)
                || AgenticTravelPlanner.UPDATE_DESTINATION_FOCUS.equals(actionType)
                || AgenticTravelPlanner.SET_FLEXIBLE_DATES.equals(actionType)
                || AgenticTravelPlanner.SAVE_JOURNEY_PLAN.equals(actionType)
                || AgenticTravelPlanner.CREATE_MOBILITY_WATCH.equals(actionType);
    }

    public static String rejectedReceipt(String actionType, String destination) {
        String item = destination == null || destination.trim().isEmpty()
                ? "the requested travel change"
                : "the requested travel change for " + destination.trim();
        return "No global travel data changed: " + item
                + " requires the exact active confirmed owner.";
    }
}
