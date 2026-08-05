import com.kiraworld.sarahtravel.BookingLinkParser;

public final class BookingLinkParserTest {
    public static void main(String[] args) {
        BookingLinkParser.BookingLink expedia = BookingLinkParser.parse(
                "Here is my hotel booking https://www.expedia.com/trips/abc123");
        require(expedia.found(), "Expedia booking must be detected");
        require("Expedia".equals(expedia.provider), "Expedia provider must be identified");
        require("hotel".equals(expedia.bookingType), "hotel booking type must be identified");

        BookingLinkParser.BookingLink ordinaryEventPage = BookingLinkParser.parse(
                "Here is the CES information page https://www.ces.tech/attendee/overview");
        require(!ordinaryEventPage.found(), "ordinary event page must not become a booking");

        BookingLinkParser.BookingLink genericReservation = BookingLinkParser.parse(
                "My reservation is https://example.com/private/itinerary");
        require(genericReservation.found(), "explicit generic reservation link must be saved");

        System.out.println("BookingLinkParserTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
