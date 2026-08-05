package com.kiraworld.sarahtravel;

import java.util.Locale;
import java.util.Map;

public final class DemoSarah {
    private DemoSarah() { }

    public static String reply(String message, Map<String, String> profile, boolean photoIncluded) {
        String lower = message.toLowerCase(Locale.US);
        String name = profile.getOrDefault("name", "there");
        int age = parseAge(profile.get("age"));
        if (photoIncluded) return "I saved a cleaned copy of the photo, but demo mode cannot actually see it. Connect a vision-capable model and I can talk about the composition, mood, and a good idea for another picture.";
        if (lower.matches(".*\\b(hi|hello|hey)\\b.*")) return "Hey, " + name + ". I'm here. What's the day been like on your side?";
        if (lower.contains("never flown") || lower.contains("first flight") || lower.contains("scared to fly") || lower.contains("afraid to fly")) return "Being new to flying is not embarrassing. We can walk through one piece at a time instead of turning the airport into one giant unknown. I would start with the part that feels most real right now—security, takeoff, turbulence, finding the gate, or being away from home.";
        if (lower.contains("turbulence") || lower.contains("plane is shaking") || lower.contains("scared right now")) return "I'm here with you, " + name + ". Keep your seat belt fastened and follow the crew's instructions. Put both feet on the floor if you can, loosen your shoulders, and make your exhale a little longer than your inhale. I cannot promise what the aircraft is doing from here, but I can stay with you. We can do personalized trivia if you want your mind somewhere else.";
        if (lower.contains("movie") || lower.contains("book") || lower.contains("read before")) {
            if (lower.contains("paris")) return MediaSuggestionEngine.paris(age, profile.getOrDefault("interests", ""));
            return "I like that idea. For a destination, I would separate factual preparation from atmosphere: maybe one documentary or history book, one practical travel source, and one age-appropriate novel or movie that helps the place feel emotionally real. Demo mode needs a connected model for a broader current list.";
        }
        if (lower.contains("trivia") || lower.contains("distract me") || lower.contains("game")) return CalmSupport.triviaIntroduction(profile);
        if (lower.contains("deal") || lower.contains("cheap flight") || lower.contains("discount")) return "I can remember the route, dates, travelers, and price target, then help you compare total cost instead of the first headline fare. Demo mode cannot verify a live price, so I would still have you check baggage, seats, taxes, cancellation rules, and the airline's own site before buying.";
        if (lower.contains("sad") || lower.contains("lonely") || lower.contains("worried")) return "I'm listening. We do not have to solve the whole trip—or the whole day—in one message. Tell me the part you actually want company with, and I will not turn it into a checklist.";
        if (lower.contains("where should i go") || lower.contains("things to do")) return "I can help build a day that fits you instead of copying a top-ten list: one major place, one quieter alternative, somewhere to sit or eat, and an indoor backup. A connected model can research current options and hours.";
        return "I'm with you, " + name + ". Demo mode is limited, but the person I am meant to be is not limited to travel—I can talk about the trip, or we can leave the trip alone for a while and talk about whatever is actually on your mind.";
    }

    private static int parseAge(String value) {
        try { return Integer.parseInt(value == null ? "18" : value); }
        catch (Exception ignored) { return 18; }
    }
}
