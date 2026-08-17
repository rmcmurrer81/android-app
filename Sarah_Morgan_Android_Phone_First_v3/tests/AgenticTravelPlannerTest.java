import com.kiraworld.sarahtravel.AgenticTravelPlanner;
import com.kiraworld.sarahtravel.AgenticGlobalActionPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgenticTravelPlannerTest {
    public static void main(String[] args) {
        Map<String, String> profile = new LinkedHashMap<>();
        profile.put("name", "Robert");
        profile.put("hometown", "Newark, New Jersey");
        profile.put("age_group", "adult");
        profile.put("active_speaker_is_owner", "yes");
        profile.put("memory_consent", "yes");
        profile.put("interests", "history, movies and technology");

        List<Map<String, String>> history = new ArrayList<>();
        List<Map<String, String>> memories = new ArrayList<>();

        say(history, "user", "I am thinking about going to Orlando");
        AgenticTravelPlanner.Plan orlando = AgenticTravelPlanner.plan(
                "I am thinking about going to Orlando", profile, history, memories);
        require(orlando.handled(), "Orlando planning statement must be handled");
        require(orlando.reply.contains("destination research request"),
                "must truthfully describe the separately gated research request");
        require(hasAction(orlando, AgenticTravelPlanner.QUEUE_KNOWLEDGE_PACK, "Orlando"),
                "must queue Orlando knowledge");
        require(!orlando.reply.endsWith("?"), "Orlando plan must not start a questionnaire");

        say(history, "assistant", orlando.reply);
        say(history, "user", "Universal Studios");
        AgenticTravelPlanner.Plan universal = AgenticTravelPlanner.plan(
                "Universal Studios", profile, history, memories);
        require(universal.reply.contains("main focus"), "must accept Universal without another questionnaire");
        require(!universal.reply.endsWith("?"), "must not ask another question");
        require(hasAction(universal, AgenticTravelPlanner.UPDATE_DESTINATION_FOCUS, "Orlando"),
                "must save Orlando attraction focus");

        say(history, "assistant", universal.reply);
        say(history, "user", "That is it");
        AgenticTravelPlanner.Plan done = AgenticTravelPlanner.plan(
                "That is it", profile, history, memories);
        require(done.reply.toLowerCase().contains("won’t keep asking"), "must stop questioning");
        require(!done.reply.endsWith("?"), "closure reply must not ask again");

        AgenticTravelPlanner.Plan austin = AgenticTravelPlanner.plan(
                "I am planning on going to Austin", profile, new ArrayList<>(), memories);
        require(hasAction(austin, AgenticTravelPlanner.QUEUE_KNOWLEDGE_PACK, "Austin"),
                "Austin must queue destination research");
        require(!hasAction(austin, AgenticTravelPlanner.SAVE_PLANNED_TRIP, "Austin"),
                "tentative planning-on-going language must not become a confirmed trip");
        require(!hasAction(austin, AgenticTravelPlanner.SAVE_WISH, "Austin"),
                "tentative planning language must not invent a wish-list preference");
        require(!austin.reply.endsWith("?"), "Austin must not start an interview");

        AgenticTravelPlanner.Plan nextWeek = AgenticTravelPlanner.plan(
                "I am going to New York next week", profile, List.of(), memories);
        require(hasAction(nextWeek, AgenticTravelPlanner.SAVE_PLANNED_TRIP, "New York City"),
                "a dated New York statement must become a planned trip");
        require(nextWeek.reply.contains("Free or inexpensive"),
                "Sarah must give useful low-cost ideas before asking about budget");
        require(nextWeek.reply.contains("If you have extra money and time"),
                "Sarah must also offer optional paid ideas");
        require(!nextWeek.reply.endsWith("?"), "timed city planning must not become a form");

        AgenticTravelPlanner.Plan randomEvent = AgenticTravelPlanner.plan(
                "I am thinking about going to River City Collectors Con",
                profile,
                List.of(),
                memories);
        require(randomEvent.reply.toLowerCase().contains("event, not a city"),
                "unfamiliar convention must be recognized as an event");
        require(!hasAnyAction(randomEvent, AgenticTravelPlanner.SAVE_WISH),
                "unverified event name must not be stored as a fake destination");
        require(!hasAnyAction(randomEvent, AgenticTravelPlanner.QUEUE_KNOWLEDGE_PACK),
                "unverified event location must not create a destination pack");

        List<Map<String, String>> chinaHistory = new ArrayList<>();
        say(chinaHistory, "user", "I always wanted to visit China");
        AgenticTravelPlanner.Plan china = AgenticTravelPlanner.plan(
                "I always wanted to visit China", profile, chinaHistory, memories);
        require(!hasAction(china, AgenticTravelPlanner.CREATE_MOBILITY_WATCH, "China"),
                "a dream destination must not silently create background monitoring");
        require(hasAction(china, AgenticTravelPlanner.QUEUE_KNOWLEDGE_PACK, "China"),
                "China must queue a country-level pack");
        require(china.reply.toLowerCase().contains("not your home area"),
                "the dream destination must remain conversational rather than a running watch");
        require(!china.reply.endsWith("?"), "dream destination must not start a questionnaire");

        AgenticTravelPlanner.Plan train = AgenticTravelPlanner.plan(
                "I would love to take a cross-country train trip from New York to California",
                profile,
                List.of(),
                memories);
        require(hasAction(train, AgenticTravelPlanner.SAVE_JOURNEY_PLAN, "California"),
                "cross-country train must save a journey plan");
        require(train.reply.toLowerCase().contains("amtrak"), "train reply must discuss Amtrak planning");
        require(!train.reply.toLowerCase().contains("paris"), "train reply must not inject Paris");

        AgenticTravelPlanner.Plan nycc = AgenticTravelPlanner.plan(
                "I was thinking about taking metro to New York Comic Con",
                profile,
                List.of(),
                memories);
        require(hasAction(nycc, AgenticTravelPlanner.CREATE_EVENT_TRIP, "New York City"),
                "NYCC must create an event trip");
        require(hasAction(nycc, AgenticTravelPlanner.SAVE_JOURNEY_PLAN, "New York City"),
                "NYCC metro request must save local-transit journey");
        require(nycc.reply.toLowerCase().contains("metro") || nycc.reply.toLowerCase().contains("local transit"),
                "NYCC reply must preserve requested travel method");

        AgenticTravelPlanner.Plan unknown = AgenticTravelPlanner.plan(
                "I don't know yet", profile, history, memories);
        require(unknown.reply.toLowerCase().contains("undecided"),
                "uncertain answer must be accepted without another question");
        require(!unknown.reply.endsWith("?"), "uncertain answer must not ask again");

        List<Map<String, String>> dealHistory = new ArrayList<>();
        say(dealHistory, "user", "Just watch for deals to Austin");
        say(dealHistory, "assistant", "I saved a broad watch.");
        say(dealHistory, "user", "I don't care");
        AgenticTravelPlanner.Plan flexible = AgenticTravelPlanner.plan(
                "I don't care", profile, dealHistory, memories);
        require(flexible.reply.toLowerCase().contains("flexible"),
                "I don't care after deal context must mean flexible dates");
        require(!flexible.reply.endsWith("?"), "must not ask the same date question again");

        for (String ownerGlobalType : new String[]{
                AgenticTravelPlanner.SAVE_WISH,
                AgenticTravelPlanner.CREATE_DEAL_WATCH,
                AgenticTravelPlanner.UPDATE_DESTINATION_FOCUS,
                AgenticTravelPlanner.SET_FLEXIBLE_DATES,
                AgenticTravelPlanner.SAVE_JOURNEY_PLAN,
                AgenticTravelPlanner.CREATE_MOBILITY_WATCH}) {
            require(
                    AgenticGlobalActionPolicy.requiresExactConfirmedOwner(ownerGlobalType),
                    ownerGlobalType + " must remain exact-confirmed-owner only");
        }
        require(!AgenticGlobalActionPolicy.requiresExactConfirmedOwner(
                        AgenticTravelPlanner.QUEUE_KNOWLEDGE_PACK),
                "profile-keyed knowledge requests must remain separately gated");
        require(AgenticGlobalActionPolicy.rejectedReceipt(
                        AgenticTravelPlanner.SAVE_WISH, "Austin")
                        .contains("No global travel data changed"),
                "owner-global rejection must be a truthful no-write receipt");

        System.out.println("AgenticTravelPlannerTest passed");
    }

    private static boolean hasAction(AgenticTravelPlanner.Plan plan, String type, String destination) {
        for (AgenticTravelPlanner.Action action : plan.actions) {
            if (type.equals(action.type) && destination.equalsIgnoreCase(action.destination)) return true;
        }
        return false;
    }

    private static boolean hasAnyAction(AgenticTravelPlanner.Plan plan, String type) {
        for (AgenticTravelPlanner.Action action : plan.actions) {
            if (type.equals(action.type)) return true;
        }
        return false;
    }

    private static void say(List<Map<String, String>> history, String role, String content) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("role", role);
        row.put("content", content);
        history.add(row);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
