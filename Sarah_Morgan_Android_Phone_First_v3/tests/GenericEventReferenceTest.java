import com.kiraworld.sarahtravel.EventTripIntentParser;
import com.kiraworld.sarahtravel.GenericEventReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GenericEventReferenceTest {
    public static void main(String[] args) {
        String random = GenericEventReference.extract(
                "I am thinking about going to River City Collectors Con");
        require("River City Collectors Con".equals(random),
                "an unfamiliar convention must be kept as an event name");

        EventTripIntentParser.EventIntent intent = EventTripIntentParser.parse(
                "I am thinking about going to River City Collectors Con");
        require(intent.recognized(), "unknown event must be recognized");
        require(!intent.found(), "unknown event must not invent a destination");
        require(intent.destination.isEmpty(), "unknown event destination must remain empty until verified");

        List<Map<String, String>> history = new ArrayList<>();
        history.add(row("user", "I am thinking about going to River City Collectors Con"));
        history.add(row("assistant", "I will look for an official page."));
        history.add(row("user", "When is it?"));
        String followUp = GenericEventReference.recentEvent(history, "When is it?");
        require("River City Collectors Con".equals(followUp),
                "a short date follow-up must retain the recent unfamiliar event");

        String ordinary = GenericEventReference.extract("I am thinking about going to Austin");
        require(ordinary.isEmpty(), "ordinary cities must not be reclassified as events");

        System.out.println("GenericEventReferenceTest passed");
    }

    private static Map<String, String> row(String role, String content) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("role", role);
        row.put("content", content);
        return row;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
