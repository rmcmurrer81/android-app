package com.kiraworld.sarahtravel;

/** Pure fail-closed policy separating saved event facts from background monitoring. */
public final class EventTripMonitoringPolicy {
    private EventTripMonitoringPolicy() { }

    public static boolean canEnable(
            boolean explicitRequest,
            boolean confirmedOwner,
            boolean ownerMonitoringOptIn,
            boolean sourceRouteAvailable) {
        return explicitRequest
                && confirmedOwner
                && ownerMonitoringOptIn
                && sourceRouteAvailable;
    }

    public static boolean leaseStillValid(
            String expectedPersonId,
            String currentPersonId,
            boolean confirmedOwner,
            boolean ownerMonitoringOptIn,
            boolean interrupted) {
        return leaseStillValidForProfileKey(
                EventTripProfilePolicy.profileKey(expectedPersonId),
                currentPersonId,
                confirmedOwner,
                ownerMonitoringOptIn,
                interrupted);
    }

    public static boolean leaseStillValidForProfileKey(
            String expectedProfileKey,
            String currentPersonId,
            boolean confirmedOwner,
            boolean ownerMonitoringOptIn,
            boolean interrupted) {
        return !interrupted
                && confirmedOwner
                && ownerMonitoringOptIn
                && EventTripProfilePolicy.isVisibleProfileKey(expectedProfileKey)
                && expectedProfileKey.equals(EventTripProfilePolicy.profileKey(currentPersonId));
    }
}
