package com.kiraworld.sarahtravel;

/** Pure, stable ownership rules for event trips and imported booking material. */
public final class EventTripProfilePolicy {
    public static final String LEGACY_OWNER_UNASSIGNED = "legacy_owner_unassigned";

    private EventTripProfilePolicy() { }

    /** PersonProfileStore IDs are positive SQLite integers and remain stable across name changes. */
    public static String profileKey(String personId) {
        String clean = personId == null ? "" : personId.trim();
        if (!clean.matches("[1-9][0-9]*")) return "";
        return "person_" + clean;
    }

    public static boolean isVisibleProfileKey(String profileKey) {
        return profileKey != null && profileKey.matches("person_[1-9][0-9]*");
    }

    public static boolean sameProfile(String expectedProfileKey, String activePersonId) {
        return isVisibleProfileKey(expectedProfileKey)
                && expectedProfileKey.equals(profileKey(activePersonId));
    }

    /** Deterministic internal key used only when a profile-move collision must remain lossless. */
    public static String collisionEventKey(String originalEventKey, long sourceRowId) {
        String base = originalEventKey == null ? "event" : originalEventKey.trim();
        if (base.isEmpty()) base = "event";
        return base + "_migrated_" + Math.max(0L, sourceRowId);
    }
}
