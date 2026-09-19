import com.kiraworld.sarahtravel.AgenticTravelPlanner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EventTripPlannerTest {
    public static void main(String[] args) {
        Map<String, String> profile = new LinkedHashMap<>();
        profile.put("hometown", "Newark, New Jersey");

        AgenticTravelPlanner.Plan ces = AgenticTravelPlanner.plan(
                "I am going to Vegas for CES",
                profile,
                List.of(),
                List.of());
        require(ces.handled(), "CES statement must be handled");
        require(hasAction(ces, AgenticTravelPlanner.CREATE_EVENT_TRIP), "CES must create event trip");
        require(!eventAction(ces).monitoringRequested,
                "attending CES must create a static event without an inferred monitor");
        require(ces.reply.contains("without silently turning on background monitoring"),
                "save-only event reply must state the non-monitoring truth");
        require(hasAction(ces, AgenticTravelPlanner.QUEUE_KNOWLEDGE_PACK), "CES must queue destination research");
        require(!ces.reply.endsWith("?"), "CES reply must not become another questionnaire");

        AgenticTravelPlanner.Plan comicCon = AgenticTravelPlanner.plan(
                "I am going to San Diego for comic con",
                profile,
                List.of(),
                List.of());
        require(hasAction(comicCon, AgenticTravelPlanner.CREATE_EVENT_TRIP), "Comic-Con must create event trip");
        require(comicCon.reply.contains("nearby"), "Comic-Con reply should include nearby planning");

        AgenticTravelPlanner.Plan monitoredComicCon = AgenticTravelPlanner.plan(
                "Monitor updates for San Diego Comic-Con",
                profile,
                List.of(),
                List.of());
        require(eventAction(monitoredComicCon).monitoringRequested,
                "explicit monitoring language must survive into the executable action");

        AgenticTravelPlanner.Plan booking = AgenticTravelPlanner.plan(
                "My Expedia hotel booking is https://www.expedia.com/trips/abc123",
                profile,
                List.of(),
                List.of());
        require(hasAction(booking, AgenticTravelPlanner.SAVE_BOOKING_LINK), "booking link must be saved");
        require(booking.reply.toLowerCase().contains("pending"), "booking link must remain pending review");

        System.out.println("EventTripPlannerTest passed");
    }

    private static boolean hasAction(AgenticTravelPlanner.Plan plan, String type) {
        for (AgenticTravelPlanner.Action action : plan.actions) {
            if (type.equals(action.type)) return true;
        }
        return false;
    }

    private static AgenticTravelPlanner.Action eventAction(AgenticTravelPlanner.Plan plan) {
        for (AgenticTravelPlanner.Action action : plan.actions) {
            if (AgenticTravelPlanner.CREATE_EVENT_TRIP.equals(action.type)) return action;
        }
        throw new AssertionError("event action missing");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
