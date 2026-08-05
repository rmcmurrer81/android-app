import com.kiraworld.sarahtravel.EventTripIntentParser;

public final class EventTripIntentParserTest {
    public static void main(String[] args) {
        EventTripIntentParser.EventIntent ces = EventTripIntentParser.parse(
                "I am going to Vegas for CES");
        require(ces.found(), "CES event must be found");
        require("CES".equals(ces.eventName), "CES name must be canonical");
        require("Las Vegas".equals(ces.destination), "CES destination must be Las Vegas");
        require(ces.monitoringRequested, "CES should create a monitor");

        EventTripIntentParser.EventIntent comicCon = EventTripIntentParser.parse(
                "I am going to San Diego for comic con");
        require(comicCon.found(), "Comic-Con event must be found");
        require(comicCon.eventName.toLowerCase().contains("comic"), "Comic-Con name must be retained");
        require("San Diego".equals(comicCon.destination), "Comic-Con destination must be San Diego");

        EventTripIntentParser.EventIntent generic = EventTripIntentParser.parse(
                "I am traveling to Austin for the Future of Travel Conference");
        require(generic.found(), "generic named event must be found");
        require("Austin".equals(generic.destination), "generic event city must be Austin");
        require(generic.eventName.toLowerCase().contains("future of travel"), "generic event name must be retained");

        System.out.println("EventTripIntentParserTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
