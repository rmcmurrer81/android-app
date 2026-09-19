package com.kiraworld.sarahtravel;

import java.net.URLEncoder;
import java.time.LocalDate;

/**
 * Pure-Java validation and request policy for Stay22's keyless Direct Travel
 * API demo. This class deliberately has no API-key field: the demo request is
 * authenticated only by Stay22's documented per-IP demo allowance.
 */
public final class Stay22SearchPolicy {
    static final String DEMO_ENDPOINT = "https://api.stay22.com/v2/accommodations";
    static final int PAGE_SIZE = 8;
    static final long MIN_DEMO_REQUEST_INTERVAL_SECONDS = 12;
    static final long DEFAULT_RATE_LIMIT_WAIT_SECONDS = 60;
    private static final long MAX_RATE_LIMIT_WAIT_SECONDS = 300;

    public static final class RequestSpec {
        public final String url;
        public final String destination;
        public final String checkIn;
        public final String checkOut;
        public final int adults;
        public final int rooms;
        public final boolean dated;

        private RequestSpec(
                String url,
                String destination,
                String checkIn,
                String checkOut,
                int adults,
                int rooms,
                boolean dated) {
            this.url = url;
            this.destination = destination;
            this.checkIn = checkIn;
            this.checkOut = checkOut;
            this.adults = adults;
            this.rooms = rooms;
            this.dated = dated;
        }
    }

    private Stay22SearchPolicy() { }

    /**
     * Builds one first-page demo request. No credential, affiliate ID, or
     * traveler identity is placed in the URL.
     */
    public static RequestSpec prepare(
            String destination,
            String checkIn,
            String checkOut,
            int adults,
            int rooms) {
        String place = clean(destination);
        String start = clean(checkIn);
        String end = clean(checkOut);
        if (place.isEmpty()) {
            throw new IllegalArgumentException("Enter a destination before searching Stay22.");
        }
        if (adults < 1 || adults > 20) {
            throw new IllegalArgumentException("Travelers must be between 1 and 20 for this Stay22 demo search.");
        }
        if (rooms < 1 || rooms > 20) {
            throw new IllegalArgumentException("Rooms must be between 1 and 20 for this Stay22 demo search.");
        }

        boolean hasStart = !start.isEmpty();
        boolean hasEnd = !end.isEmpty();
        if (hasStart != hasEnd) {
            throw new IllegalArgumentException(
                    "Set both check-in and check-out, or clear both for an unpriced discovery search.");
        }

        boolean dated = hasStart;
        if (dated) {
            final LocalDate arrival;
            final LocalDate departure;
            try {
                arrival = LocalDate.parse(start);
                departure = LocalDate.parse(end);
            } catch (Exception error) {
                throw new IllegalArgumentException("Use YYYY-MM-DD for both hotel dates.");
            }
            LocalDate today = LocalDate.now();
            LocalDate latest = today.plusYears(2);
            if (arrival.isBefore(today) || departure.isAfter(latest)) {
                throw new IllegalArgumentException(
                        "Stay22 dates must be from today through two years from today.");
            }
            if (!departure.isAfter(arrival)) {
                throw new IllegalArgumentException("Check-out must be after check-in.");
            }
        }

        StringBuilder url = new StringBuilder(DEMO_ENDPOINT)
                .append("?address=").append(encode(place))
                .append("&pageSize=").append(PAGE_SIZE)
                .append("&adults=").append(adults)
                .append("&rooms=").append(rooms);
        if (dated) {
            url.append("&checkin=").append(encode(start))
                    .append("&checkout=").append(encode(end));
        }
        return new RequestSpec(url.toString(), place, start, end, adults, rooms, dated);
    }

    /**
     * Converts Stay22's rate-limit headers into a bounded local cooldown.
     * The caller does not automatically retry; it can tell the traveler when
     * a new user-initiated request is reasonable.
     */
    public static long retryDelaySeconds(
            String retryAfterHeader,
            String resetMillisecondsHeader,
            long nowEpochMilliseconds) {
        Long retryAfter = positiveLong(retryAfterHeader);
        if (retryAfter != null) return clampSeconds(retryAfter);

        Long reset = positiveLong(resetMillisecondsHeader);
        if (reset != null) {
            long delayMilliseconds = reset > 10_000_000_000L
                    ? reset - Math.max(0, nowEpochMilliseconds)
                    : reset;
            if (delayMilliseconds > 0) {
                long seconds = (delayMilliseconds + 999L) / 1000L;
                return clampSeconds(seconds);
            }
        }
        return DEFAULT_RATE_LIMIT_WAIT_SECONDS;
    }

    private static long clampSeconds(long value) {
        return Math.max(1, Math.min(MAX_RATE_LIMIT_WAIT_SECONDS, value));
    }

    private static Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(clean(value));
            return parsed > 0 ? parsed : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception impossibleForUtf8) {
            throw new IllegalStateException("UTF-8 URL encoding is unavailable", impossibleForUtf8);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
