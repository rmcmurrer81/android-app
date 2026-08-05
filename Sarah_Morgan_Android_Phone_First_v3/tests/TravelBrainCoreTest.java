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

        say(history, "user", "I would love to travel to either Paris or London");
        String first = TravelBrainCore.answer(
                "I would love to travel to either Paris or London",
                profile, history, memories, List.of(), List.of());
        require(first != null && first.contains("Paris") && first.contains("London"),
                "explicit comparison must include both destinations");

        say(history, "assistant", first);
        say(history, "user", "The history");
        String historyReply = TravelBrainCore.answer(
                "The history", profile, history, memories, List.of(), List.of());
        require(historyReply.contains("Paris:") && historyReply.contains("London:"),
                "direct comparison follow-up must preserve both places");

        say(history, "assistant", historyReply);
        say(history, "user", "I don't care about watching stuff just looking for deals");
        String correction = TravelBrainCore.answer(
                "I don't care about watching stuff just looking for deals",
                profile, history, memories, List.of(), List.of());
        require(correction.toLowerCase().contains("followed the wrong subject"),
                "must acknowledge the topic correction");
        require(!correction.toLowerCase().contains("amélie"), "must stop media topic");

        List<Map<String, String>> stale = new ArrayList<>();
        say(stale, "user", "I always wanted to visit Paris");
        say(stale, "assistant", "Paris is on your list.");
        String train = TravelBrainCore.answer(
                "I would love to take a cross-country train trip from New York to California",
                profile, stale, memories, List.of(), List.of());
        require(train.toLowerCase().contains("amtrak"), "cross-country train must get rail guidance");
        require(train.contains("New York City") && train.contains("California"),
                "route endpoints must be preserved");
        require(!train.toLowerCase().contains("paris"), "old Paris must not leak into route reply");

        String unknown = TravelBrainCore.answer(
                "I don't know yet", profile, stale, memories, List.of(), List.of());
        require(unknown.toLowerCase().contains("undecided"),
                "uncertain answer must close the travel topic cleanly");
        require(!unknown.endsWith("?"), "uncertain answer must not ask another question");

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
