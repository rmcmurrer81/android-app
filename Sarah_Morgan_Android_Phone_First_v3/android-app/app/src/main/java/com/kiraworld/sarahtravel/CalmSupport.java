package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CalmSupport {
    public static final class Question {
        public final String prompt;
        public final String[] choices;
        public final int correctIndex;
        public final String explanation;
        public Question(String prompt, String[] choices, int correctIndex, String explanation) {
            this.prompt = prompt; this.choices = choices; this.correctIndex = correctIndex; this.explanation = explanation;
        }
    }

    private CalmSupport() { }

    public static String turbulenceSupport(Map<String, String> profile) {
        String name = profile.getOrDefault("name", "there");
        return "I'm here with you, " + name + ". Keep your seat belt fastened and follow the crew's instructions. Put both feet on the floor if you can, drop your shoulders, and make the exhale slightly longer than the inhale. I cannot judge the aircraft from the phone, so I will not promise what is happening—but I can stay with you and help the next minute feel smaller.";
    }

    public static String groundingSupport() {
        return "Let's make the cabin smaller for a moment: notice five things you can see, four things you can feel, three things you can hear, two things you can smell, and one thing you want to do after you land. You do not have to rush.";
    }

    public static String triviaIntroduction(Map<String, String> profile) {
        String destination = bestDestination(List.of(), List.of(), profile);
        return "Let's move your attention for a minute. I can give you multiple-choice trivia based on your age, interests, " + destination + ", or where you are from. Open Calm & Trivia and I will keep the questions coming without needing the internet.";
    }

    public static List<Question> questions(Map<String, String> profile, List<Map<String, String>> trips, List<Map<String, String>> wishes) {
        int age = parseAge(profile.get("age"));
        String destination = bestDestination(trips, wishes, profile);
        String d = destination.toLowerCase(Locale.US);
        List<Question> q = new ArrayList<>();
        if (d.contains("paris") || d.contains("france")) {
            if (age < 13) {
                q.add(new Question("Which fictional Paris hero is associated with magical earrings?", new String[]{"Ladybug", "Wonder Woman", "Elsa"}, 0, "Ladybug is the Paris-based hero in Miraculous Ladybug."));
                q.add(new Question("Which Paris landmark is a tall iron tower?", new String[]{"Big Ben", "Eiffel Tower", "Space Needle"}, 1, "The Eiffel Tower is one of Paris's best-known landmarks."));
            } else {
                q.add(new Question("Which film follows a shy Paris waitress named Amélie?", new String[]{"Amélie", "Casablanca", "Roman Holiday"}, 0, "Amélie is a whimsical film strongly associated with Paris."));
                q.add(new Question("Which river runs through Paris?", new String[]{"Seine", "Thames", "Hudson"}, 0, "The Seine runs through Paris."));
                if (age >= 18) q.add(new Question("Which mature action film uses major Paris locations in its fourth chapter?", new String[]{"John Wick: Chapter 4", "Top Gun", "Jaws"}, 0, "John Wick: Chapter 4 includes highly violent action in Paris; it is fictional atmosphere, not travel preparation."));
            }
        }
        q.add(new Question("What should you usually do during turbulence?", new String[]{"Keep the seat belt fastened and follow crew instructions", "Stand in the aisle", "Open the overhead bin"}, 0, "The safest general response is to remain seated, keep the belt fastened, and follow the crew."));
        q.add(new Question("Which item is most useful to keep easy to reach during a flight?", new String[]{"A small comfort item or headphones", "A checked suitcase", "A hotel television"}, 0, "A small familiar item, headphones, or another approved comfort tool can help with distraction."));
        return q;
    }

    private static String bestDestination(List<Map<String, String>> trips, List<Map<String, String>> wishes, Map<String, String> profile) {
        for (Map<String, String> t : trips) {
            String status = t.getOrDefault("status", "").toLowerCase(Locale.US);
            if (status.contains("current") || status.contains("planned")) return t.getOrDefault("destination", "your destination");
        }
        if (!wishes.isEmpty()) return wishes.get(0).getOrDefault("destination", "your destination");
        return profile.getOrDefault("hometown", "your destination");
    }

    private static int parseAge(String value) {
        try { return Integer.parseInt(value == null ? "18" : value); }
        catch (Exception ignored) { return 18; }
    }
}
