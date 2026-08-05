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
        System.out.println("ConversationModePolicyTest passed");
    }

    private static void expect(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
