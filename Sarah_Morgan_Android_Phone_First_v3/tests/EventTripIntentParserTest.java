import com.kiraworld.sarahtravel.EventTripIntentParser;

public final class EventTripIntentParserTest {
    public static void main(String[] args) {
        EventTripIntentParser.EventIntent ces = EventTripIntentParser.parse(
                "I am going to Vegas for CES");
        require(ces.found(), "CES event must be found");
        require("CES".equals(ces.eventName), "CES name must be canonical");
        require(ces.destination.toLowerCase().contains("las vegas"), "CES destination must be Las Vegas");
        require(!ces.monitoringRequested,
                "mentioning or attending CES must save facts without silently creating a monitor");

        EventTripIntentParser.EventIntent monitoredCes = EventTripIntentParser.parse(
                "Monitor CES updates because I am going to Vegas");
        require(monitoredCes.found(), "explicitly monitored CES must still resolve");
        require(monitoredCes.monitoringRequested,
                "explicit monitor language must be carried separately from event recognition");

        require(!EventTripIntentParser.parse("Don't monitor CES").monitoringRequested,
                "negated monitor language must never enable monitoring");
        require(!EventTripIntentParser.parse("Stop monitoring CES").monitoringRequested,
                "a stop request must never be reinterpreted as enabling monitoring");
        require(EventTripIntentParser.parse("Stop monitoring CES").monitoringCancellationRequested,
                "a stop request must carry an exact cancellation intent");
        EventTripIntentParser.EventIntent dontNotify = EventTripIntentParser.parse(
                "Don't let me know if there are new details about CES");
        require(!dontNotify.monitoringRequested,
                "negated let-me-know language must never enable monitoring");
        require(dontNotify.monitoringCancellationRequested,
                "negated let-me-know language must carry cancellation intent");
        require(!EventTripIntentParser.parse(
                "What are the new details for CES?").monitoringRequested,
                "asking for current details is not a background-monitor request");
        require(!EventTripIntentParser.parse(
                "Do you monitor CES?").monitoringRequested,
                "asking about capability is not a monitoring request");
        require(EventTripIntentParser.parse(
                "Please notify me if there are new details for CES").monitoringRequested,
                "an explicit notification request must enable the request flag");
        require(!EventTripIntentParser.parse(
                "Do not forget to monitor CES").monitoringCancellationRequested,
                "a reminder to keep monitoring must not cancel it");
        require(!EventTripIntentParser.parse(
                "I do not want to stop monitoring CES").monitoringCancellationRequested,
                "double-negated stop language must not cancel monitoring");
        require(!EventTripIntentParser.parse(
                "Never stop monitoring CES").monitoringCancellationRequested,
                "never-stop language must not cancel monitoring");
        require(!EventTripIntentParser.parse(
                "Do not disable monitoring CES").monitoringCancellationRequested,
                "an instruction not to disable must not cancel monitoring");
        require(!EventTripIntentParser.parse(
                "Stop talking about monitoring CES").monitoringCancellationRequested,
                "stopping discussion is not stopping the monitor");
        require(!EventTripIntentParser.parse(
                "Donut monitoring is not a command").monitoringCancellationRequested,
                "ordinary words must not match a contraction wildcard");
        EventTripIntentParser.EventIntent unfamiliarCancellation = EventTripIntentParser.parse(
                "Stop monitoring Travel Hack NYC");
        require(unfamiliarCancellation.monitoringCancellationRequested,
                "an unfamiliar exact event must carry cancellation intent");
        require(unfamiliarCancellation.recognized(),
                "an unfamiliar exact cancellation target must be retained");
        require("Travel Hack NYC".equals(unfamiliarCancellation.eventName),
                "the unfamiliar cancellation target must be exact and not invented");
        EventTripIntentParser.EventIntent cancellationWithOf = EventTripIntentParser.parse(
                "Cancel monitoring of Travel Hack NYC");
        require(cancellationWithOf.monitoringCancellationRequested,
                "cancel-monitoring-of grammar must carry cancellation intent");
        require("Travel Hack NYC".equals(cancellationWithOf.eventName),
                "cancel-monitoring-of must not retain the preposition in the event name");
        require(EventTripIntentParser.parse(
                "Cancel the CES monitor").monitoringCancellationRequested,
                "a named monitor cancellation must be recognized");
        require(EventTripIntentParser.parse(
                "Hey Sarah, stop monitoring CES").monitoringCancellationRequested,
                "a natural direct address must be recognized");

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
        require(!popCon.monitoringRequested,
                "known-event recognition is not an automatic monitoring request");

        System.out.println("EventTripIntentParserTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
