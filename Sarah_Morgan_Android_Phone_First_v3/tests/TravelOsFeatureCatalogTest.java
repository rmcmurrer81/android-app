import com.kiraworld.sarahtravel.TravelOsFeatureCatalog;

public final class TravelOsFeatureCatalogTest {
    public static void main(String[] args) {
        require(TravelOsFeatureCatalog.countTrack(TravelOsFeatureCatalog.AI_TRIP_PLANNING) >= 6,
                "AI trip planning track needs broad planning, booking and transport coverage");
        require(TravelOsFeatureCatalog.countTrack(TravelOsFeatureCatalog.HOTEL_HOSPITALITY_OPS) >= 5,
                "hotel and hospitality operations track needs guest and back-of-house coverage");
        require(TravelOsFeatureCatalog.countTrack(TravelOsFeatureCatalog.LOCAL_EXPERIENCES) >= 5,
                "local experiences track needs discovery, media and ground transport coverage");
        require(TravelOsFeatureCatalog.countTrack(TravelOsFeatureCatalog.SUSTAINABILITY_ACCESSIBILITY) >= 5,
                "sustainability and accessibility track needs meaningful coverage");

        require(TravelOsFeatureCatalog.has(
                        TravelOsFeatureCatalog.AI_TRIP_PLANNING, "hotel_search"),
                "hotel search must be represented");
        require(TravelOsFeatureCatalog.has(
                        TravelOsFeatureCatalog.HOTEL_HOSPITALITY_OPS, "voice_concierge"),
                "voice concierge must be represented");
        require(TravelOsFeatureCatalog.has(
                        TravelOsFeatureCatalog.LOCAL_EXPERIENCES, "rides"),
                "local ride handoff must be represented");
        require(TravelOsFeatureCatalog.has(
                        TravelOsFeatureCatalog.SUSTAINABILITY_ACCESSIBILITY, "active_profile_needs"),
                "per-profile accessibility must be represented");

        System.out.println("TravelOsFeatureCatalogTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
