import com.kiraworld.sarahtravel.TravelBrainCore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TravelBrainCoreTest {
    public static void main(String[] args) {
        Map<String, String> profile = new LinkedHashMap<>();
        profile.put("name", "Robert");
        profile.put("hometown", "Newark, New Jersey");
        profile.put("age", "45");
        profile.put("age_group", "adult");

        List<Map<String, String>> history = new ArrayList<>();
        List<Map<String, String>> memories = new ArrayList<>();
        memory(memories, "travel_preference", "Travel dates are flexible");
        memory(memories, "travel_preference", "Usually travels light and prefers little or no checked luggage");

        say(history, "user", "I would love to travel to either Paris or London");
        String first = TravelBrainCore.answer("I would love to travel to either Paris or London", profile, history, memories, List.of(), List.of());
        require(first != null && first.contains("Paris") && first.contains("London"), "must compare both destinations");

        say(history, "assistant", first);
        say(history, "user", "The history");
        String historyReply = TravelBrainCore.answer("The history", profile, history, memories, List.of(), List.of());
        require(historyReply.contains("Paris:") && historyReply.contains("London:"), "must compare history for both");

        say(history, "assistant", historyReply);
        say(history, "user", "I love seeing it in different movies and shows");
        String media = TravelBrainCore.answer("I love seeing it in different movies and shows", profile, history, memories, List.of(), List.of());
        require(media != null && media.contains("Paris:") && media.contains("London:"), "must compare media for both");
        require(!media.toLowerCase().contains("john wick"), "must not force John Wick");

        say(history, "assistant", media);
        say(history, "user", "I don't care about watching stuff just looking for deals");
        String correction = TravelBrainCore.answer("I don't care about watching stuff just looking for deals", profile, history, memories, List.of(), List.of());
        require(correction.toLowerCase().contains("you want deals"), "must acknowledge correction");
        require(!correction.toLowerCase().contains("amélie"), "must stop media topic");

        say(history, "assistant", correction);
        say(history, "user", "Ok???? Just notify me about deals.");
        String alert = TravelBrainCore.answer("Ok???? Just notify me about deals.", profile, history, memories, List.of(), List.of());
        require(alert.contains("Newark, New Jersey to Paris and London"), "must preserve route context");
        require(alert.toLowerCase().contains("does not yet have a live airfare feed"), "must be honest about monitoring");

        System.out.println("TravelBrainCoreTest passed");
    }

    private static void say(List<Map<String, String>> history, String role, String content) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("role", role);
        row.put("content", content);
        history.add(row);
    }

    private static void memory(List<Map<String, String>> memories, String category, String summary) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("category", category);
        row.put("summary", summary);
        memories.add(row);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
