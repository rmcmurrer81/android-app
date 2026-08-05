import com.kiraworld.sarahtravel.TravelContextResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TravelContextResolverTest {
    public static void main(String[] args) {
        List<Map<String, String>> history = new ArrayList<>();
        say(history, "user", "I always wanted to visit Paris");
        say(history, "assistant", "Paris is on the list.");

        List<String> route = TravelContextResolver.resolveDestinations(
                "I would love to take a cross-country train trip from New York to California",
                history);
        require(route.size() == 2, "current route must have two endpoints");
        require(route.contains("New York City"), "must keep New York origin");
        require(route.contains("California"), "must keep California destination");
        require(!route.contains("Paris"), "must not drag old Paris into current route");

        List<String> cleared = TravelContextResolver.resolveDestinations("I don't know yet", history);
        require(cleared.isEmpty(), "undecided answer must clear travel context");

        List<Map<String, String>> comparison = new ArrayList<>();
        say(comparison, "user", "I am deciding between Paris or London");
        say(comparison, "assistant", "They are different trips.");
        List<String> followUp = TravelContextResolver.resolveDestinations("The history", comparison);
        require(followUp.size() == 2 && followUp.contains("Paris") && followUp.contains("London"),
                "direct short follow-up must keep the most recent comparison only");

        List<Map<String, String>> eventHistory = new ArrayList<>();
        say(eventHistory, "user", "Paris might be nice someday");
        say(eventHistory, "assistant", "Okay.");
        List<String> event = TravelContextResolver.resolveDestinations(
                "I was thinking about taking metro to New York Comic Con", eventHistory);
        require(event.size() == 1 && event.contains("New York City"),
                "NYCC turn must replace old Paris context");

        System.out.println("TravelContextResolverTest passed");
    }

    private static void say(List<Map<String, String>> history, String role, String content) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("role", role);
        row.put("content", content);
        history.add(row);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
