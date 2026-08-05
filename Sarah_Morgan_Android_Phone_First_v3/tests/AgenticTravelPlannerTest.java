import com.kiraworld.sarahtravel.AgenticTravelPlanner;

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

        List<Map<String, String>> history = new ArrayList<>();
        List<Map<String, String>> memories = new ArrayList<>();

        say(history, "user", "I am thinking about going to Orlando");
        AgenticTravelPlanner.Plan orlando = AgenticTravelPlanner.plan(
                "I am thinking about going to Orlando", profile, history, memories);
        require(orlando.handled(), "Orlando planning statement must be handled");
        require(orlando.reply.contains("planning list"), "must proactively create a plan");
        require(hasAction(orlando, AgenticTravelPlanner.QUEUE_KNOWLEDGE_PACK, "Orlando"),
                "must queue Orlando knowledge");

        say(history, "assistant", orlando.reply);
        say(history, "user", "Universal Studios");
        AgenticTravelPlanner.Plan universal = AgenticTravelPlanner.plan(
                "Universal Studios", profile, history, memories);
        require(universal.reply.contains("main focus"), "must accept Universal without another questionnaire");
        require(!universal.reply.endsWith("?"), "must not ask another question");

        say(history, "assistant", universal.reply);
        say(history, "user", "That is it");
        AgenticTravelPlanner.Plan done = AgenticTravelPlanner.plan(
                "That is it", profile, history, memories);
        require(done.reply.toLowerCase().contains("won’t keep asking"), "must stop questioning");

        List<Map<String, String>> chinaHistory = new ArrayList<>();
        say(chinaHistory, "user", "I always wanted to visit China");
        AgenticTravelPlanner.Plan china = AgenticTravelPlanner.plan(
                "I always wanted to visit China", profile, chinaHistory, memories);
        require(hasAction(china, AgenticTravelPlanner.CREATE_DEAL_WATCH, "China"),
                "dream destination must create broad deal watch");
        require(china.reply.contains("nearby airports"), "watch must use broad airport defaults");
        require(!china.reply.endsWith("?"), "dream destination must not start a questionnaire");

        List<Map<String, String>> dealHistory = new ArrayList<>();
        say(dealHistory, "user", "Just watch for deals to Austin");
        say(dealHistory, "assistant", "I saved a broad watch.");
        say(dealHistory, "user", "I don't care");
        AgenticTravelPlanner.Plan flexible = AgenticTravelPlanner.plan(
                "I don't care", profile, dealHistory, memories);
        require(flexible.reply.toLowerCase().contains("flexible"),
                "I don't care after deal context must mean flexible dates");
        require(!flexible.reply.endsWith("?"), "must not ask the same date question again");

        System.out.println("AgenticTravelPlannerTest passed");
    }

    private static boolean hasAction(AgenticTravelPlanner.Plan plan, String type, String destination) {
        for (AgenticTravelPlanner.Action action : plan.actions) {
            if (type.equals(action.type) && destination.equalsIgnoreCase(action.destination)) return true;
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
