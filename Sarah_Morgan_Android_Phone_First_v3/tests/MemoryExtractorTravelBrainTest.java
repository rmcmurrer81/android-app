import com.kiraworld.sarahtravel.DestinationParser;
import com.kiraworld.sarahtravel.MemoryExtractor;

import java.util.List;

public final class MemoryExtractorTravelBrainTest {
    public static void main(String[] args) {
        String choices = "I would love to travel to either Paris or London";
        List<String> possibleDestinations = DestinationParser.extractDestinations(choices);
        require(possibleDestinations.contains("Paris"), "Paris destination missing");
        require(possibleDestinations.contains("London"), "London destination missing");
        require(!hasCategory(MemoryExtractor.extract(choices), "wish_list"),
                "tentative destinations must not become permanent wishes");

        String thinking = "I am thinking about visiting Brazil";
        require(DestinationParser.extractDestinations(thinking).contains("Brazil"),
                "natural-language Brazil destination missing");
        require(!hasCategory(MemoryExtractor.extract(thinking), "wish_list"),
                "thinking about Brazil must not silently create a permanent wish");

        List<MemoryExtractor.Candidate> vague = MemoryExtractor.extract("I love seeing it in different movies and shows");
        require(!hasCategory(vague, "interest"), "vague pronoun interest should not be hardened");

        List<MemoryExtractor.Candidate> alerts = MemoryExtractor.extract("Just notify me about flight deals");
        require(has(alerts, "Wants travel deal alerts"), "deal alert request missing");

        List<MemoryExtractor.Candidate> preferences = MemoryExtractor.extract("I don't care of dates and I always travel light");
        require(has(preferences, "Travel dates are flexible"), "flexible dates missing");
        require(has(preferences, "Usually travels light and prefers little or no checked luggage"), "light luggage missing");

        System.out.println("MemoryExtractorTravelBrainTest passed");
    }

    private static boolean has(List<MemoryExtractor.Candidate> values, String summary) {
        for (MemoryExtractor.Candidate value : values) if (value.summary.equals(summary)) return true;
        return false;
    }

    private static boolean hasCategory(List<MemoryExtractor.Candidate> values, String category) {
        for (MemoryExtractor.Candidate value : values) if (value.category.equals(category)) return true;
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
