package com.kiraworld.sarahtravel;

import java.net.URLEncoder;
import java.time.LocalDate;

/**
 * Builds optional, user-initiated affiliate handoff URLs without storing a
 * credential or changing Sarah's recommendation/ranking logic.
 *
 * <p>The Stay22 AID is a public attribution identifier, not an API secret.
 * Links are returned only when an AID and destination are present. No person
 * name, profile ID, email, precise location, or other Sarah memory is used as
 * an affiliate tracking value.</p>
 */
public final class TravelAffiliateLinks {
    private static final String STAY22_ROAM = "https://www.stay22.com/allez/roam";
    private static final String STAY22_GET_YOUR_GUIDE =
            "https://www.stay22.com/allez/getyourguide";

    public static final String DISCLOSURE =
            "If you book through this partner link, Sarah Travel OS may earn a "
            + "commission at no extra cost to you. Commission does not change "
            + "Sarah's recommendation order.";

    private TravelAffiliateLinks() { }

    public static boolean stay22Configured(String aid) {
        return !clean(aid).isEmpty();
    }

    public static String hotels(
            String aid,
            String destination,
            String checkIn,
            String checkOut,
            int adults,
            String campaign) {
        String affiliateId = clean(aid);
        String place = clean(destination);
        if (affiliateId.isEmpty() || place.isEmpty()) return "";

        StringBuilder url = base(STAY22_ROAM, affiliateId, place, campaign);
        if (validDatePair(checkIn, checkOut)) {
            url.append("&checkin=").append(enc(clean(checkIn)))
                    .append("&checkout=").append(enc(clean(checkOut)));
        }
        if (adults >= 1 && adults <= 20) {
            url.append("&adults=").append(adults);
        }
        return url.toString();
    }

    public static String activities(
            String aid,
            String destination,
            String campaign) {
        String affiliateId = clean(aid);
        String place = clean(destination);
        if (affiliateId.isEmpty() || place.isEmpty()) return "";
        return base(STAY22_GET_YOUR_GUIDE, affiliateId, place, campaign).toString();
    }

    private static StringBuilder base(
            String endpoint,
            String aid,
            String destination,
            String campaign) {
        StringBuilder url = new StringBuilder(endpoint)
                .append("?aid=").append(enc(aid))
                .append("&address=").append(enc(destination));
        String tracking = campaign(clean(campaign));
        if (!tracking.isEmpty()) {
            url.append("&campaign=").append(enc(tracking));
        }
        return url;
    }

    private static boolean validDatePair(String checkIn, String checkOut) {
        String start = clean(checkIn);
        String end = clean(checkOut);
        if (start.isEmpty() || end.isEmpty()) return false;
        try {
            LocalDate arrival = LocalDate.parse(start);
            LocalDate departure = LocalDate.parse(end);
            return departure.isAfter(arrival);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String campaign(String value) {
        String normalized = clean(value)
                .toLowerCase()
                .replace('-', '_')
                .replaceAll("[^a-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.length() > 80) {
            normalized = normalized.substring(0, 80).replaceAll("_+$", "");
        }
        return normalized;
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(clean(value), "UTF-8").replace("+", "%20");
        } catch (Exception impossibleForUtf8) {
            throw new IllegalStateException("UTF-8 URL encoding is unavailable", impossibleForUtf8);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
