package com.kiraworld.sarahtravel;

import java.util.Map;

/** Unknown or malformed maturity data always follows the non-adult safe path. */
public final class MaturityAccessPolicy {
    public static final String UNKNOWN_SAFE = "unknown_use_child_safe_mode";

    private MaturityAccessPolicy() { }

    public static String ageGroup(Map<String, String> profile) {
        String group = profile == null ? "" : profile.getOrDefault("age_group", "").trim();
        if ("adult".equals(group) || "teen".equals(group) || "child".equals(group)) return group;
        return UNKNOWN_SAFE;
    }

    public static boolean requiresNonAdultSafeContent(Map<String, String> profile) {
        return !"adult".equals(ageGroup(profile));
    }
}
