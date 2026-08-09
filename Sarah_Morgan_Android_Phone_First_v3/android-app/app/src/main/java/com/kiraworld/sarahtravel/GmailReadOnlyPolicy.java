package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pure contract for Sarah's bounded, read-only Gmail travel discovery. */
public final class GmailReadOnlyPolicy {
    public static final String SCOPE =
            "https://www.googleapis.com/auth/gmail.readonly";
    public static final int MAX_CANDIDATES = 10;
    public static final int MAX_RESPONSE_BYTES = 512 * 1024;
    public static final long ACCESS_TOKEN_CACHE_MILLIS = 45L * 60L * 1000L;
    public static final long AUTHORIZATION_ATTEMPT_MILLIS = 10L * 60L * 1000L;

    private static final String FIXED_TRAVEL_QUERY =
            "newer_than:365d {itinerary reservation booking confirmation ticket "
                    + "flight hotel train amtrak bus cruise rental event concert conference "
                    + "convention festival} -in:spam -in:trash";

    private GmailReadOnlyPolicy() { }

    public static String travelQuery() {
        return FIXED_TRAVEL_QUERY;
    }

    public static List<String> metadataHeaders() {
        List<String> headers = new ArrayList<>();
        headers.add("Subject");
        headers.add("From");
        headers.add("Date");
        return Collections.unmodifiableList(headers);
    }

    public static boolean exactReadOnlyGrant(List<String> grantedScopes) {
        return grantedScopes != null
                && grantedScopes.size() == 1
                && SCOPE.equals(grantedScopes.get(0));
    }

    public static boolean permittedRequest(String method, String endpoint) {
        if (!"GET".equals(method)) return false;
        if (endpoint == null || !endpoint.startsWith("https://gmail.googleapis.com/")) {
            return false;
        }
        String lower = endpoint.toLowerCase(Locale.US);
        if (lower.contains("/send")
                || lower.contains("/modify")
                || lower.contains("/trash")
                || lower.contains("/untrash")
                || lower.contains("/batchdelete")
                || lower.contains("/batchmodify")) {
            return false;
        }
        return endpoint.contains("/gmail/v1/users/me/profile")
                || endpoint.contains("/gmail/v1/users/me/messages");
    }

    public static boolean usableCachedToken(
            String token,
            String exactScope,
            long expiresAtEpochMillis,
            long nowEpochMillis) {
        return token != null
                && !token.trim().isEmpty()
                && SCOPE.equals(exactScope)
                && expiresAtEpochMillis > nowEpochMillis + 30_000L;
    }

    public static String safeReceiptLabel(
            String subject,
            String sender,
            String messageDate) {
        return clean(subject, 180) + "\nFrom: " + clean(sender, 180)
                + "\nMessage date: " + clean(messageDate, 100);
    }

    private static String clean(String value, int limit) {
        String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
                .replaceAll("\\s+", " ").trim();
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
