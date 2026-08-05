import com.kiraworld.sarahtravel.DestinationPackResponder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DestinationPackResponderTest {
    public static void main(String[] args) {
        List<Map<String, String>> history = new ArrayList<>();
        history.add(row("user", "I am planning on going to Austin"));

        Map<String, String> pack = new LinkedHashMap<>();
        pack.put("destination", "Austin");
        pack.put("status", "ready");
        pack.put("overview", "Austin combines live music, Texas history, food, neighborhoods, and outdoor spaces.");
        pack.put("recommendations", "South Congress; a live-music evening; a history museum; a weather-aware outdoor backup.");
        pack.put("transport", "Choose lodging around the areas you expect to visit and verify current transit options.");
        pack.put("accessibility", "Heat, walking distance, sound levels, and venue seating can matter.");
        pack.put("seasonal", "Summer heat can substantially affect outdoor plans.");
        pack.put("events", "Verify current festivals and venue schedules for the travel dates.");
        pack.put("source_note", "Connected research refreshed today.");

        String places = DestinationPackResponder.answer(
                "What places do you recommend?", history, List.of(pack));
        require(places != null && places.contains("South Congress"),
                "recommendation answer must use saved pack");
        require(!places.endsWith("?"), "pack answer must not add a follow-up question");

        String events = DestinationPackResponder.answer(
                "Are there any events?", history, List.of(pack));
        require(events != null && events.contains("festivals"),
                "event answer must use dated/current pack field");
        require(events.contains("Seasonal context"),
                "event answer should include weather/season context");

        System.out.println("DestinationPackResponderTest passed");
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
