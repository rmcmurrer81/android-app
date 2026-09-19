package com.kiraworld.sarahtravel;

import java.net.URI;
import java.util.Locale;

/** Pure fail-closed rules for owner-selected event tickets and passes. */
public final class TicketPassPolicy {
    public static final int MAX_PASSES_PER_PROFILE = 12;
    public static final int MAX_ENCRYPTED_IMAGE_BYTES = 2_500_000;
    public static final int MAX_TITLE_CHARS = 160;
    public static final int MAX_DATE_CHARS = 80;

    public static final String VERIFIED_EVENT_SOURCE = "verified_event_source";
    public static final String OWNER_PROVIDED_SOURCE = "owner_provided_source";

    private TicketPassPolicy() { }

    public static boolean canStore(
            int currentCount,
            String title,
            int sanitizedImageBytes) {
        return currentCount >= 0
                && currentCount < MAX_PASSES_PER_PROFILE
                && !clean(title).isEmpty()
                && clean(title).length() <= MAX_TITLE_CHARS
                && sanitizedImageBytes > 0
                && sanitizedImageBytes <= MAX_ENCRYPTED_IMAGE_BYTES;
    }

    /**
     * Return the exact trimmed HTTPS URL or an empty string. Ticket links may
     * contain a query because official registration systems commonly use one.
     */
    public static String exactHttpsUrl(String value) {
        String clean = clean(value);
        if (clean.isEmpty()) return "";
        try {
            URI uri = new URI(clean);
            if (!"https".equals(uri.getScheme() == null
                    ? "" : uri.getScheme().toLowerCase(Locale.US))
                    || uri.getHost() == null
                    || uri.getHost().trim().isEmpty()
                    || uri.getUserInfo() != null) return "";
            return clean;
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String bounded(String value, int maximum) {
        String clean = clean(value);
        if (maximum < 1 || clean.length() <= maximum) return clean;
        return clean.substring(0, maximum);
    }

    public static String sourceStatus(boolean verifiedEventSource) {
        return verifiedEventSource ? VERIFIED_EVENT_SOURCE : OWNER_PROVIDED_SOURCE;
    }

    public static boolean isVerifiedEventSource(String value) {
        return VERIFIED_EVENT_SOURCE.equals(clean(value));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
