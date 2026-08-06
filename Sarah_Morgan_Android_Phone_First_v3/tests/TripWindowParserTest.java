import com.kiraworld.sarahtravel.CityVisitPlanner;
import com.kiraworld.sarahtravel.TripWindowParser;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TripWindowParserTest {
    public static void main(String[] args) {
        LocalDate wednesday = LocalDate.of(2026, 8, 5);
        TripWindowParser.TripWindow nextWeek = TripWindowParser.parse(
                "I am going to New York next week", wednesday);
        require(nextWeek.found(), "New York next week must be recognized");
        require("New York City".equals(nextWeek.destination), "New York must canonicalize to New York City");
        require(LocalDate.of(2026, 8, 10).equals(nextWeek.startDate), "next week must begin Monday");
        require(LocalDate.of(2026, 8, 16).equals(nextWeek.endDate), "next week must end Sunday");

        Map<String, String> profile = new LinkedHashMap<>();
        profile.put("name", "Robert");
        profile.put("age_group", "adult");
        profile.put("active_speaker_is_owner", "yes");
        profile.put("interests", "history, movies and technology");
        String reply = CityVisitPlanner.answer(nextWeek, profile, List.of());
        require(reply.contains("Free or inexpensive"), "Sarah must offer low-cost choices before asking about budget");
        require(reply.contains("If you have extra money and time"), "Sarah must offer optional paid ideas");
        require(reply.contains("history"), "saved interests should influence the starter plan");
        require(!reply.endsWith("?"), "starter plan must not become another questionnaire");

        TripWindowParser.TripWindow nextMonth = TripWindowParser.parse(
                "We are visiting Boston next month", wednesday);
        require(nextMonth.found(), "next month must be recognized");
        require(LocalDate.of(2026, 9, 1).equals(nextMonth.startDate), "next month must start on first day");
        require(LocalDate.of(2026, 9, 30).equals(nextMonth.endDate), "next month must end on last day");

        System.out.println("TripWindowParserTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
