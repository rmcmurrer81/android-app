import com.kiraworld.sarahtravel.Stay22SearchPolicy;

import java.time.LocalDate;

public final class Stay22SearchPolicyTest {
    public static void main(String[] args) {
        Stay22SearchPolicy.RequestSpec staticSearch = Stay22SearchPolicy.prepare(
                " New York City ", "", "", 1, 1);
        require(!staticSearch.dated, "an undated request must remain an unpriced discovery search");
        require(staticSearch.url.startsWith("https://api.stay22.com/v2/accommodations?"),
                "the request must use the official HTTPS Direct Travel API endpoint");
        require(staticSearch.url.contains("address=New%20York%20City"),
                "the destination must be encoded without changing its meaning");
        require(staticSearch.url.contains("pageSize=8"),
                "the demo request must stay bounded to one small page");
        require(staticSearch.url.contains("adults=1"),
                "the exact traveler count must be sent rather than accepting a provider default");
        require(staticSearch.url.contains("rooms=1"),
                "the exact room count must be sent rather than accepting a provider default");
        require(!staticSearch.url.contains("checkin="),
                "an undated request must not manufacture dates");
        require(!staticSearch.url.toLowerCase().contains("key="),
                "the keyless demo URL must not embed an API key");
        require(!staticSearch.url.toLowerCase().contains("token="),
                "the keyless demo URL must not embed a backend token");
        require(!staticSearch.url.toLowerCase().contains("person"),
                "the request must not include a Sarah person/profile identifier");

        String checkIn = LocalDate.now().plusDays(30).toString();
        String checkOut = LocalDate.now().plusDays(33).toString();
        Stay22SearchPolicy.RequestSpec datedSearch = Stay22SearchPolicy.prepare(
                "Paris", checkIn, checkOut, 3, 2);
        require(datedSearch.dated, "a complete valid window must be marked dated");
        require(datedSearch.url.contains("checkin=" + checkIn),
                "the exact check-in date must be sent");
        require(datedSearch.url.contains("checkout=" + checkOut),
                "the exact check-out date must be sent");
        require(datedSearch.url.contains("adults=3") && datedSearch.url.contains("rooms=2"),
                "dated quotes must remain bound to the exact traveler and room counts");

        rejects("", "", "", 1, 1, "empty destination must fail closed");
        rejects("Paris", checkIn, "", 1, 1, "a partial date window must fail closed");
        rejects("Paris", "", checkOut, 1, 1, "a partial date window must fail closed");
        rejects("Paris", "09/12/2026", "09/15/2026", 1, 1, "ambiguous date formats must fail closed");
        rejects("Paris", checkOut, checkIn, 1, 1, "a reversed date window must fail closed");
        rejects("Paris", checkIn, checkIn, 1, 1, "a zero-night date window must fail closed");
        rejects("Paris", LocalDate.now().minusDays(2).toString(), LocalDate.now().minusDays(1).toString(),
                1, 1, "past dates must fail locally instead of consuming demo quota");
        rejects("Paris", LocalDate.now().plusYears(2).plusDays(1).toString(),
                LocalDate.now().plusYears(2).plusDays(2).toString(),
                1, 1, "dates beyond Stay22's two-year boundary must fail locally");
        rejects("Paris", "", "", 0, 1, "zero travelers must fail closed");
        rejects("Paris", "", "", 1, 0, "zero rooms must fail closed");

        long now = 1_800_000_000_000L;
        require(Stay22SearchPolicy.retryDelaySeconds("12", "", now) == 12,
                "Retry-After must take priority when present");
        require(Stay22SearchPolicy.retryDelaySeconds("", "61000", now) == 61,
                "a duration-style millisecond reset must round up to seconds");
        require(Stay22SearchPolicy.retryDelaySeconds("", String.valueOf(now + 43_000), now) == 43,
                "an epoch-millisecond reset must become a local cooldown");
        require(Stay22SearchPolicy.retryDelaySeconds("bad", "bad", now) == 60,
                "missing or malformed headers must use a conservative one-minute cooldown");
        require(Stay22SearchPolicy.retryDelaySeconds("9999", "", now) == 300,
                "an untrusted wait header must be bounded");

        System.out.println("Stay22SearchPolicyTest passed");
    }

    private static void rejects(
            String destination,
            String checkIn,
            String checkOut,
            int adults,
            int rooms,
            String message) {
        try {
            Stay22SearchPolicy.prepare(destination, checkIn, checkOut, adults, rooms);
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected fail-closed behavior.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
