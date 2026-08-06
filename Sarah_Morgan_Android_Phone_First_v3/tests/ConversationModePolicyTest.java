import com.kiraworld.sarahtravel.ConversationModePolicy;

public final class ConversationModePolicyTest {
    public static void main(String[] args) {
        expect(ConversationModePolicy.ROUTE_SMART,
                ConversationModePolicy.route(ConversationModePolicy.MODE_AUTO, true, true));
        expect(ConversationModePolicy.ROUTE_LOCAL,
                ConversationModePolicy.route(ConversationModePolicy.MODE_AUTO, false, true));
        expect(ConversationModePolicy.ROUTE_LOCAL,
                ConversationModePolicy.route(ConversationModePolicy.MODE_AUTO, true, false));
        expect(ConversationModePolicy.ROUTE_LOCAL,
                ConversationModePolicy.route(ConversationModePolicy.MODE_LOCAL_ONLY, true, true));

        String publicOnline = ConversationModePolicy.statusLabel(
                ConversationModePolicy.MODE_AUTO, true, false, false);
        require(publicOnline.contains("Public lookup online"),
                "internet without a model key must advertise public lookup instead of implying total offline mode");
        require(publicOnline.contains("Smart setup needed"),
                "status must still explain why broad Smart conversation is unavailable");

        String offline = ConversationModePolicy.statusLabel(
                ConversationModePolicy.MODE_AUTO, false, false, false);
        require(offline.contains("offline"), "no internet must remain clearly offline");

        System.out.println("ConversationModePolicyTest passed");
    }

    private static void expect(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
