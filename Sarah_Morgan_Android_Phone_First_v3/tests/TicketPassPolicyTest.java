import com.kiraworld.sarahtravel.TicketPassPolicy;

public final class TicketPassPolicyTest {
    public static void main(String[] args) {
        require(TicketPassPolicy.canStore(0, "PopCon Indy admission", 42_000),
                "a bounded owner-selected pass should be accepted");
        require(!TicketPassPolicy.canStore(
                        TicketPassPolicy.MAX_PASSES_PER_PROFILE,
                        "Another pass",
                        42_000),
                "the per-profile count must fail closed");
        require(!TicketPassPolicy.canStore(0, "", 42_000),
                "a title is required");
        require(!TicketPassPolicy.canStore(
                        0,
                        "Oversized pass",
                        TicketPassPolicy.MAX_ENCRYPTED_IMAGE_BYTES + 1),
                "oversized sanitized images must be rejected");

        String exact = "https://example.org/tickets?id=ABC123&date=2027-03-26";
        require(exact.equals(TicketPassPolicy.exactHttpsUrl(exact)),
                "an exact HTTPS ticket URL and query must be preserved");
        require(TicketPassPolicy.exactHttpsUrl("http://example.org/tickets").isEmpty(),
                "unencrypted event links must be rejected");
        require(TicketPassPolicy.exactHttpsUrl("https://user@example.org/tickets").isEmpty(),
                "URLs containing user information must be rejected");
        require(TicketPassPolicy.isVerifiedEventSource(
                        TicketPassPolicy.sourceStatus(true)),
                "verified event provenance must remain explicit");
        require(!TicketPassPolicy.isVerifiedEventSource(
                        TicketPassPolicy.sourceStatus(false)),
                "owner-entered links must not be relabeled as verified");

        System.out.println("TicketPassPolicyTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
