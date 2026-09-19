import com.kiraworld.sarahtravel.TravelAffiliateLinks;

public final class TravelAffiliateLinksTest {
    public static void main(String[] args) {
        testNoAffiliateIdMeansNoMonetizedLink();
        testHotelLinkCarriesOnlyTravelIntentAndTracking();
        testActivityLinkUsesGetYourGuideAndSafeCampaign();
        testInvalidDatesAreNotForwarded();
        testDisclosureIsPlainAndRankingNeutral();
        System.out.println("TravelAffiliateLinksTest passed");
    }

    private static void testNoAffiliateIdMeansNoMonetizedLink() {
        assertEquals("", TravelAffiliateLinks.hotels(
                "", "Salem, MA", "2026-10-01", "2026-10-03", 1, "hotel_search"));
        assertEquals("", TravelAffiliateLinks.activities(
                "", "Salem, MA", "local_experiences"));
    }

    private static void testHotelLinkCarriesOnlyTravelIntentAndTracking() {
        String url = TravelAffiliateLinks.hotels(
                "demo aid",
                "Salem, MA",
                "2026-10-01",
                "2026-10-03",
                2,
                "Sarah Hotel-Search");
        assertContains(url, "https://www.stay22.com/allez/roam?");
        assertContains(url, "aid=demo%20aid");
        assertContains(url, "address=Salem%2C%20MA");
        assertContains(url, "checkin=2026-10-01");
        assertContains(url, "checkout=2026-10-03");
        assertContains(url, "adults=2");
        assertContains(url, "campaign=sarah_hotel_search");
        assertNotContains(url, "person_id");
        assertNotContains(url, "email");
    }

    private static void testActivityLinkUsesGetYourGuideAndSafeCampaign() {
        String url = TravelAffiliateLinks.activities(
                "demo",
                "New York, NY",
                "Sarah Local-Experiences 2026");
        assertContains(url, "https://www.stay22.com/allez/getyourguide?");
        assertContains(url, "address=New%20York%2C%20NY");
        assertContains(url, "campaign=sarah_local_experiences_2026");
    }

    private static void testInvalidDatesAreNotForwarded() {
        String url = TravelAffiliateLinks.hotels(
                "demo",
                "Boston",
                "2026-10-03",
                "2026-10-01",
                1,
                "hotel_search");
        assertNotContains(url, "checkin=");
        assertNotContains(url, "checkout=");
        assertContains(url, "adults=1");
    }

    private static void testDisclosureIsPlainAndRankingNeutral() {
        String text = TravelAffiliateLinks.DISCLOSURE.toLowerCase();
        assertContains(text, "may earn a commission");
        assertContains(text, "no extra cost");
        assertContains(text, "does not change");
        assertContains(text, "recommendation order");
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void assertContains(String value, String fragment) {
        if (!value.contains(fragment)) {
            throw new AssertionError("Expected fragment " + fragment + " in " + value);
        }
    }

    private static void assertNotContains(String value, String fragment) {
        if (value.contains(fragment)) {
            throw new AssertionError("Unexpected fragment " + fragment + " in " + value);
        }
    }
}
