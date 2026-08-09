package com.kiraworld.sarahtravel;

import java.util.Locale;

/** Fail-closed, profile-scoped rules for approximate current-area requests. */
public final class CurrentLocationPolicy {
    public static final long MAX_AGE_MS = 15L * 60L * 1000L;
    public static final String SOURCE_DEVICE_RESOLVED = "device_resolved";
    public static final String SOURCE_MANUAL = "manual_owner_entry";
    public static final String SOURCE_UNKNOWN = "legacy_source_unknown";

    private CurrentLocationPolicy() { }

    public static boolean asksForCurrentArea(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.US);
        return lower.matches(
                ".*\\b(near me|nearby|near my location|near my current location|around here|around me|close to me|close by|"
                        + "where i am|my current location|current location|current area|in my area)\\b.*");
    }

    public static boolean fresh(long capturedAt, long now) {
        return capturedAt > 0 && now >= capturedAt && now - capturedAt <= MAX_AGE_MS;
    }

    public static String profileKey(String personId) {
        String safe = personId == null ? "" : personId.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        return safe.isEmpty() ? "unknown_profile" : safe;
    }

    public static String unavailableReply(String reason) {
        if ("permission_denied".equals(reason)) {
            return "I don’t have permission to use your approximate location. Tell me a city or ZIP code and I can continue without asking again.";
        }
        if ("services_off".equals(reason)) {
            return "Location services are off, so I can’t establish your current area. Tell me a city or ZIP code and I can continue.";
        }
        if ("stale".equals(reason)) {
            return "The phone’s last area is too old to call current. Tell me a city or ZIP code, or try Use my current location again.";
        }
        return "I couldn’t establish a current area. Tell me a city or ZIP code and I can continue without guessing.";
    }

    public static String settingsStatus(String area, String source) {
        String cleanArea = area == null ? "" : area.trim();
        if (SOURCE_DEVICE_RESOLVED.equals(source)) {
            return "Device-resolved approximate current area: " + cleanArea
                    + ". This is profile-specific and is not your saved home.";
        }
        if (SOURCE_MANUAL.equals(source)) {
            return "Area entered by you: " + cleanArea
                    + ". Sarah will not describe it as device-resolved current location.";
        }
        return "Saved approximate area: " + cleanArea
                + ". Its older record did not preserve whether it was entered or device-resolved.";
    }
}
