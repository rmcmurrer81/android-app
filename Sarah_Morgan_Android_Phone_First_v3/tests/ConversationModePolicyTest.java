import com.kiraworld.sarahtravel.ConversationModePolicy;
import com.kiraworld.sarahtravel.OwnerOnlineActivationPolicy;

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
                ConversationModePolicy.MODE_AUTO, true, false, false, false);
        require(publicOnline.contains("Online unavailable"),
                "validated internet without a configured conversation route must not claim online readiness");

        String configuredOnly = ConversationModePolicy.statusLabel(
                ConversationModePolicy.MODE_AUTO, true, true, false, false);
        require(configuredOnly.contains("verified") && configuredOnly.contains("first connected reply"),
                "a verified contract still must not claim a successful conversation turn");

        String checking = ConversationModePolicy.statusLabel(
                ConversationModePolicy.MODE_AUTO, true, false, false, false,
                true, false);
        require(checking.startsWith("Checking connection"),
                "a health probe must have an explicit checking state");

        String reconnecting = ConversationModePolicy.statusLabel(
                ConversationModePolicy.MODE_AUTO, true, false, true, false,
                false, true);
        require(reconnecting.startsWith("Reconnecting"),
                "a restored network must not claim readiness before verification");

        String smartOnline = ConversationModePolicy.statusLabel(
                ConversationModePolicy.MODE_AUTO, true, true, false, true);
        require(smartOnline.contains("verified by a recent connected reply"),
                "online readiness requires a real successful connected reply");

        String failedOnline = ConversationModePolicy.statusLabel(
                ConversationModePolicy.MODE_AUTO, true, true, true, false);
        require(failedOnline.contains("next turn will retry"),
                "a validated network callback must not erase a recorded backend failure");

        String offline = ConversationModePolicy.statusLabel(
                ConversationModePolicy.MODE_AUTO, false, false, false, false);
        require(offline.startsWith("Offline mind ready"),
                "no internet must remain clearly offline");

        require(OwnerOnlineActivationPolicy.needsActivation(
                        true, true, "https://sarah.example.test", ""),
                "validated internet plus a suggested route must request one owner activation");
        require(!OwnerOnlineActivationPolicy.needsActivation(
                        true, true, "https://sarah.example.test", "saved-token"),
                "a saved access code must not keep prompting");
        require(!OwnerOnlineActivationPolicy.needsActivation(
                        true, false, "https://sarah.example.test", ""),
                "a non-owner must never receive the activation prompt");
        require(OwnerOnlineActivationPolicy.status(
                        true, true, "https://sarah.example.test", "")
                        .contains("access code once"),
                "missing activation must be actionable instead of saying only online unavailable");

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
