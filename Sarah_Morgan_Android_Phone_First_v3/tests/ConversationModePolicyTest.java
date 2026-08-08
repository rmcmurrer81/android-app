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
        require(publicOnline.contains("Public web online"),
                "internet without the team model must advertise public web access instead of implying total offline mode");
        require(publicOnline.contains("online mind not included in this build"),
                "status must explain that the team build, not the app user, controls online-mind availability");

        String smartOnline = ConversationModePolicy.statusLabel(
                ConversationModePolicy.MODE_AUTO, true, true, false);
        require(smartOnline.contains("Online mind connected"),
                "a team-connected build must identify the protected online mind as active");

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
