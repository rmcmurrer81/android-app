import com.kiraworld.sarahtravel.KnownEventCatalog;

public final class KnownEventCatalogTest {
    public static void main(String[] args) {
        KnownEventCatalog.Entry bell = KnownEventCatalog.find("bell country comic con");
        require(bell != null, "Bell Country typo must resolve");
        require("Bell County Comic Con".equals(bell.eventName), "event name must be corrected");
        require("Belton, Texas".equals(bell.destination), "event must resolve to Belton, Texas");
        require(bell.officialUrl.contains("bellcountycomiccon.com"), "official URL must be stored");

        KnownEventCatalog.Entry unknown = KnownEventCatalog.find("an imaginary convention");
        require(unknown == null, "unknown events must not be forced into the official catalog");

        System.out.println("KnownEventCatalogTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
