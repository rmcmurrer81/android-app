package com.kiraworld.sarahtravel;

/** Pure version boundary for the one-time R1 event-trip database upgrade. */
public final class EventTripPreUpgradeVersionPolicy {
    private EventTripPreUpgradeVersionPolicy() { }

    public static boolean requiresBackup(int existingVersion) {
        return existingVersion == 1;
    }

    public static boolean mayOpenV2(int existingVersion, boolean verifiedBackup) {
        return existingVersion == 2 || (existingVersion == 1 && verifiedBackup);
    }

    public static boolean unexpected(int existingVersion) {
        return existingVersion < 1 || existingVersion > 2;
    }
}
