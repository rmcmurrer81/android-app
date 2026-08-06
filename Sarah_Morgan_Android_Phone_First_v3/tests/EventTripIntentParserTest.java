import com.kiraworld.sarahtravel.EventTripIntentParser;

public final class EventTripIntentParserTest {
    public static void main(String[] args) {
        EventTripIntentParser.EventIntent ces = EventTripIntentParser.parse(
                "I am going to Vegas for CES");
        require(ces.found(), "CES event must be found");
        require("CES".equals(ces.eventName), "CES name must be canonical");
        require(ces.destination.toLowerCase().contains("las vegas"), "CES destination must be Las Vegas");
        require(ces.monitoringRequested, "CES should create a monitor");

        EventTripIntentParser.EventIntent comicCon = EventTripIntentParser.parse(
                "I am going to San Diego for comic con");
        require(comicCon.found(), "Comic-Con event must be found");
        require(comicCon.eventName.toLowerCase().contains("comic"), "Comic-Con name must be retained");
        require(comicCon.destination.toLowerCase().contains("san diego"), "Comic-Con destination must be San Diego");

        EventTripIntentParser.EventIntent generic = EventTripIntentParser.parse(
                "I am traveling to Austin for the Future of Travel Conference");
        require(generic.found(), "generic named event must be found");
        require("Austin".equals(generic.destination), "generic event city must be Austin");
        require(generic.eventName.toLowerCase().contains("future of travel"), "generic event name must be retained");

        EventTripIntentParser.EventIntent bellTypo = EventTripIntentParser.parse(
                "I am thinking about going to bell country comic con");
        require(bellTypo.found(), "Bell Country typo must still resolve the event");
        require("Bell County Comic Con".equals(bellTypo.eventName),
                "the event name must be corrected to Bell County Comic Con");
        require("Belton, Texas".equals(bellTypo.destination),
                "Bell County Comic Con must resolve to Belton, Texas");

        EventTripIntentParser.EventIntent popCon = EventTripIntentParser.parse(
                "I am thinking about going to indy pop con");
        require(popCon.found(), "Indy Pop Con must resolve without a separate city question");
        require("PopCon Indy".equals(popCon.eventName), "official PopCon name must be used");
        require("Indianapolis, Indiana".equals(popCon.destination),
                "PopCon must resolve to Indianapolis, Indiana");

        System.out.println("EventTripIntentParserTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
