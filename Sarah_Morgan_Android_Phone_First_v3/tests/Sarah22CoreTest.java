import com.kiraworld.sarahtravel.IdentityIntent;
import com.kiraworld.sarahtravel.SarahChannelResponse;
import com.kiraworld.sarahtravel.TrustedSyncProtocol;
import com.kiraworld.sarahtravel.UniversalCalmSupport;

public final class Sarah22CoreTest {
    public static void main(String[] args) throws Exception {
        require(IdentityIntent.isStressOrFear("I am stressing"), "stressing should be emotional state");
        require(IdentityIntent.looksLikeStateNotName("Stressing"), "Stressing must never become a profile name");
        require("Robert".equals(IdentityIntent.correctedName("No, I am Robert but I am stressed out")), "identity correction");
        require("train".equals(IdentityIntent.transport("This fast train is making me nervous")), "train context");
        String calm=UniversalCalmSupport.reply("Robert","adult","train");
        require(calm.contains("Robert")&&calm.toLowerCase().contains("train")&&calm.toLowerCase().contains("trivia"), "universal calm response");
        SarahChannelResponse response=SarahChannelResponse.parse("<SPOKEN>Hello.</SPOKEN><PRIVATE_MIND>private</PRIVATE_MIND><FACTUAL_TRUTH>fact</FACTUAL_TRUTH><CLASSIFICATION>TRUTHFUL_STATEMENT</CLASSIFICATION>");
        require("Hello.".equals(response.spoken), "spoken channel"); require(!response.spoken.contains("private"), "private must not leak");
        String token="1234567890abcdef"; String encrypted=TrustedSyncProtocol.encrypt(token,"phone and computer");
        require("phone and computer".equals(TrustedSyncProtocol.decrypt(token,encrypted)), "trusted sync encryption round trip");
        require(!TrustedSyncProtocol.signature(token,encrypted).isEmpty(), "signed sync payload");
        System.out.println("Sarah22CoreTest passed");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
