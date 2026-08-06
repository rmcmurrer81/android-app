import com.kiraworld.sarahtravel.JourneyIntentParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JourneyIntentParserTest {
    public static void main(String[] args) {
        Map<String, String> profile = new LinkedHashMap<>();
        profile.put("hometown", "Newark, New Jersey");

        JourneyIntentParser.JourneyIntent train = JourneyIntentParser.parse(
                "I would love to take a cross-country train trip from New York to California",
                profile,
                List.of());
        require(train.found(), "cross-country train must parse");
        require("New York City".equals(train.origin), "New York must be the origin");
        require("California".equals(train.destination), "California must be the destination");
        require(train.modes.contains(JourneyIntentParser.RAIL), "must identify rail");
        require(train.crossCountry, "must identify cross-country journey");

        JourneyIntentParser.JourneyIntent nycc = JourneyIntentParser.parse(
                "I was thinking about taking metro to New York Comic Con",
                profile,
                List.of());
        require(nycc.found(), "NYCC transit trip must parse");
        require("New York City".equals(nycc.destination), "NYCC destination must be New York City");
        require("New York Comic Con".equals(nycc.eventName), "must preserve event purpose");
        require(nycc.modes.contains(JourneyIntentParser.TRANSIT), "must identify local transit");
        require(nycc.origin.startsWith("Newark"), "profile hometown must be the origin");

        JourneyIntentParser.JourneyIntent watch = JourneyIntentParser.parse(
                "Monitor travel deals to Paris",
                profile,
                new ArrayList<>());
        require(watch.found(), "broad watch must parse");
        require(watch.monitorRequested, "must identify monitoring request");
        require(watch.modes.contains(JourneyIntentParser.AIR), "broad watch includes air");
        require(watch.modes.contains(JourneyIntentParser.RAIL), "broad watch includes rail");
        require(watch.modes.contains(JourneyIntentParser.BUS), "broad watch includes intercity bus");

        System.out.println("JourneyIntentParserTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
