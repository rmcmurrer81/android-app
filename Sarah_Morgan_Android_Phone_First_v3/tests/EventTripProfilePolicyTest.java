package com.kiraworld.sarahtravel;

public final class EventTripProfilePolicyTest {
    public static void main(String[] args) {
        require("person_1".equals(EventTripProfilePolicy.profileKey("1")),
                "positive durable PersonProfileStore IDs produce stable opaque keys");
        require("person_42".equals(EventTripProfilePolicy.profileKey(" 42 ")),
                "harmless surrounding whitespace does not change the durable key");
        for (String rejected : new String[]{"", "0", "-1", "Robert", "person_1", "../1"}) {
            require(EventTripProfilePolicy.profileKey(rejected).isEmpty(),
                    "non-durable or path-like identity must fail closed: " + rejected);
        }
        require(!EventTripProfilePolicy.isVisibleProfileKey(
                        EventTripProfilePolicy.LEGACY_OWNER_UNASSIGNED),
                "unclaimed v1 rows remain hidden from normal constructors and reads");
        require(EventTripProfilePolicy.sameProfile("person_7", "7"),
                "exact active profile keeps its lease");
        require(!EventTripProfilePolicy.sameProfile("person_7", "8"),
                "profile switch rejects a stale reader or writer");
        require(EventTripProfilePolicy.collisionEventKey("nycc", 9)
                        .equals(EventTripProfilePolicy.collisionEventKey("nycc", 9)),
                "collision preservation key is deterministic for crash-resumable migration");
        require(!EventTripProfilePolicy.collisionEventKey("nycc", 9)
                        .equals(EventTripProfilePolicy.collisionEventKey("nycc", 10)),
                "distinct colliding source rows remain distinct");

        require(EventTripMonitoringPolicy.canEnable(true, true, true, true),
                "only an explicit owner request with opt-in and a source route enables monitoring");
        require(!EventTripMonitoringPolicy.canEnable(false, true, true, true),
                "event recognition or attendance alone cannot enable monitoring");
        require(!EventTripMonitoringPolicy.canEnable(true, false, true, true),
                "a guest cannot enable the owner background monitor");
        require(!EventTripMonitoringPolicy.canEnable(true, true, false, true),
                "owner opt-out remains authoritative");
        require(!EventTripMonitoringPolicy.canEnable(true, true, true, false),
                "missing source route fails closed");
        require(EventTripMonitoringPolicy.leaseStillValid("7", "7", true, true, false),
                "an uninterrupted exact-profile monitoring lease remains valid");
        require(!EventTripMonitoringPolicy.leaseStillValid("7", "8", true, true, false),
                "switching profiles invalidates a late/background commit");
        require(!EventTripMonitoringPolicy.leaseStillValid("7", "7", true, false, false),
                "opting out invalidates a late/background commit");
        require(!EventTripMonitoringPolicy.leaseStillValid("7", "7", true, true, true),
                "interruption invalidates a late/background commit");
        require(EventTripMonitoringPolicy.leaseStillValidForProfileKey(
                        "person_7", "7", true, true, false),
                "store-bound profile keys are validated without lossy reverse parsing");

        System.out.println("EVENT_TRIP_PROFILE_POLICY_TEST_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
