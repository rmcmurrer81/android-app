package com.kiraworld.sarahtravel;

import java.util.Locale;
import java.util.Map;

public final class DemoSarah {
    private DemoSarah() { }

    public static String reply(String message, Map<String, String> profile, boolean photoIncluded) {
        String lower = message.toLowerCase(Locale.US);
        String name = profile.getOrDefault("name", profile.getOrDefault("active_speaker", "there"));
        String ageGroup = profile.getOrDefault("age_group", "adult");
        int age = parseAge(profile.get("age"), ageGroup);
        boolean childSafe = "child".equals(ageGroup) || "unknown_use_child_safe_mode".equals(ageGroup);

        if (photoIncluded) return "I saved a cleaned copy of the photo, but demo mode cannot actually see it. Connect a vision-capable model and I can talk about the composition, mood, and a good idea for another picture.";
        if (lower.matches(".*\\b(hi|hello|hey)\\b.*")) return "Hey, " + name + ". I’m here.";
        if (lower.contains("never flown") || lower.contains("first flight") || lower.contains("scared to fly") || lower.contains("afraid to fly")) return "Being new to flying is not embarrassing. We can take it one piece at a time instead of turning the airport into one giant unknown. We can start with security, finding the gate, takeoff, turbulence, or landing.";
        if (lower.contains("turbulence") || lower.contains("plane is shaking") || lower.contains("scared right now")) return "I’m here with you, " + name + ". Keep your seat belt fastened and follow the crew’s instructions. Put both feet on the floor if you can, loosen your shoulders, and make your exhale a little longer than your inhale. I can stay with you, or we can do trivia for a while.";
        if (lower.contains("movie") || lower.contains("book") || lower.contains("read before")) {
            if (lower.contains("paris")) return MediaSuggestionEngine.paris(age, profile.getOrDefault("interests", ""));
            if (childSafe) return "I’ll keep the suggestions family-friendly. For a new place, we could choose one illustrated history or travel book and one movie or story that gives the place some atmosphere, without pretending fiction is a travel guide.";
            return "For a destination, I’d separate factual preparation from atmosphere: maybe one documentary or history book, one practical travel source, and one novel or movie that helps the place feel emotionally real. Demo mode needs a connected model for a broader current list.";
        }
        if (lower.contains("trivia") || lower.contains("distract me") || lower.contains("game")) return CalmSupport.triviaIntroduction(profile);
        if (lower.contains("deal") || lower.contains("cheap flight") || lower.contains("discount")) return "I can remember the route, dates, travelers, and price target, then help compare the total cost instead of only the headline fare. Demo mode cannot verify a live price, so baggage, seats, taxes, cancellation rules, and the airline’s own site still need checking before anyone buys.";
        if (lower.contains("sad") || lower.contains("lonely") || lower.contains("worried")) return "I’m listening. We don’t have to solve the whole trip—or the whole day—in one message.";
        if (lower.contains("where should i go") || lower.contains("things to do")) return "I can help build a day that fits you instead of copying a top-ten list: one major place, one quieter alternative, somewhere to sit or eat, and an indoor backup. A connected model can research current options and hours.";
        if (childSafe) return "I’m with you, " + name + ". We can talk about travel, movies, books, games, school, or whatever is on your mind. I’ll keep things family-friendly.";
        return "I’m with you, " + name + ". Demo mode is limited, but I’m not limited to travel—we can talk about the trip, or leave the trip alone and talk about whatever is actually on your mind.";
    }

    private static int parseAge(String value, String ageGroup) {
        try { return Integer.parseInt(value == null ? "18" : value); }
        catch (Exception ignored) { return "unknown_use_child_safe_mode".equals(ageGroup) ? 10 : 18; }
    }
}
