import com.kiraworld.sarahtravel.DestinationParser;
import com.kiraworld.sarahtravel.GenericEventReference;
import com.kiraworld.sarahtravel.KnownEventCatalog;
import com.kiraworld.sarahtravel.TravelContextResolver;

import java.util.List;

public final class Sarah25ConversationContextTest {
    public static void main(String[] args) {
        require(KnownEventCatalog.find("I need plane ticket prices") == null,
                "CES must not match inside the word prices");
        require(KnownEventCatalog.find("Show me places to visit") == null,
                "CES must not match inside the word places");
        require(KnownEventCatalog.find("I am going to CES") != null,
                "CES must still match as a complete event name");

        List<String> parisTexas = DestinationParser.extractDestinations("Where is Paris Texas?");
        require(parisTexas.size() == 1 && "Paris, Texas".equals(parisTexas.get(0)),
                "Paris, Texas must not be confused with Paris, France");
        require("Paris".equals(DestinationParser.extractDestinations("I am going to Paris").get(0)),
                "Paris by itself must remain Paris, France");

        require(!GenericEventReference.isFollowUp("Where is Paris Texas?"),
                "an explicit new destination must not inherit an old event");
        require(!GenericEventReference.isFollowUp("How much is a ticket to Paris Texas?"),
                "a ticket request with a new destination must not inherit an old event");
        require(!GenericEventReference.isFollowUp("Can you help me find a hotel?"),
                "a general hotel request must not silently inherit an old event");
        require(GenericEventReference.isFollowUp("Where is it?"),
                "a genuinely elliptical event question should keep recent event context");

        require(TravelContextResolver.clearsTravelContext("I am not going to New York"),
                "a direct cancellation must clear old travel context");
        require(TravelContextResolver.clearsTravelContext("clear that trip"),
                "an explicit clear command must clear old travel context");

        System.out.println("Sarah25ConversationContextTest passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
