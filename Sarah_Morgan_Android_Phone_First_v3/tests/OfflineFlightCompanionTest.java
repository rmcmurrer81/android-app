import com.kiraworld.sarahtravel.CalmSupport;
import com.kiraworld.sarahtravel.OfflineSongCatalog;

import java.util.List;
import java.util.Map;

public final class OfflineFlightCompanionTest {
    public static void main(String[] args) {
        Map<String, String> child = Map.of("name", "Emma", "age", "7", "interests", "Miraculous");
        Map<String, String> adult = Map.of("name", "Robert", "age", "45", "interests", "history, Marvel films");

        String childTakeoff = CalmSupport.takeoffSupport(child).toLowerCase();
        require(childTakeoff.contains("belt"), "child takeoff support must mention the seat belt");
        require(childTakeoff.contains("flight crew"), "child takeoff support must defer to the flight crew");
        require(childTakeoff.contains("flower"), "young-child support should offer child-friendly breathing");

        String adultLanding = CalmSupport.landingSupport(adult).toLowerCase();
        require(adultLanding.contains("cannot assess"), "Sarah must not pretend to assess the aircraft");
        require(adultLanding.contains("seat belt"), "landing support must mention the seat belt");

        List<CalmSupport.Question> questions = CalmSupport.questions(adult, List.of(), List.of());
        require(questions.size() >= 6, "offline trivia needs enough questions for distraction");
        for (CalmSupport.Question question : questions) {
            String combined = (question.prompt + " " + question.explanation).toLowerCase();
            require(!combined.contains("john wick"), "unrequested John Wick references must not return");
            require(question.choices.length >= 3, "trivia must offer multiple choices");
        }

        require(CalmSupport.noticingPrompts(6).size() >= 5,
                "young-child noticing game needs several prompts");
        require(CalmSupport.noticingPrompts(45).size() >= 5,
                "adult noticing game needs several prompts");

        List<OfflineSongCatalog.Song> songs = OfflineSongCatalog.all();
        require(songs.size() >= 4, "offline child mode needs several sing-alongs");
        for (OfflineSongCatalog.Song song : songs) {
            require(!song.title.isEmpty(), "song title must be present");
            require(song.rightsNote.toLowerCase().contains("public domain"),
                    "every bundled sing-along needs an explicit public-domain note");
            require(!song.lines.isEmpty(), "every song needs locally available lines");
            for (OfflineSongCatalog.Line line : song.lines) {
                require(!line.text.isEmpty(), "song lines must not be empty");
                require(line.pitch >= 0.65f && line.pitch <= 1.45f, "song pitch must remain bounded");
                require(line.rate >= 0.55f && line.rate <= 1.15f, "song rate must remain bounded");
            }
        }

        require(OfflineSongCatalog.find("twinkle") != null, "Twinkle must be available offline");
        require(OfflineSongCatalog.find("row_boat") != null, "Row Your Boat must be available offline");
        require(OfflineSongCatalog.find("mary_lamb") != null, "Mary Had a Little Lamb must be available offline");

        System.out.println("OfflineFlightCompanionTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
