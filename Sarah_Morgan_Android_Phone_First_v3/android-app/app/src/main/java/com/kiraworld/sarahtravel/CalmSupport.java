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
            this.prompt = prompt;
            this.choices = choices;
            this.correctIndex = correctIndex;
            this.explanation = explanation;
        }
    }

    private CalmSupport() { }

    public static String turbulenceSupport(Map<String, String> profile) {
        String name = firstName(profile.getOrDefault("name", "there"));
        int age = parseAge(profile.get("age"));
        if (age > 0 && age < 13) {
            return "I’m right here with you, " + name + ". Keep your seat belt fastened and listen to the grown-up with you and the flight crew. Let your shoulders get soft. Pretend to smell a flower slowly, then blow out a birthday candle slowly. The bumps can feel scary. I cannot check the airplane from the phone, but I can stay with you, count breaths, sing a short song, or play a game.";
        }
        return "I’m here with you, " + name + ". Keep your seat belt fastened and follow the crew’s instructions. Put both feet on the floor if you can, release your shoulders, and let the exhale be a little longer than the inhale. I cannot assess the aircraft from the phone, so I will not make promises about what is happening—but I can stay with you and help the next minute feel smaller.";
    }

    public static String takeoffSupport(Map<String, String> profile) {
        String name = firstName(profile.getOrDefault("name", "there"));
        int age = parseAge(profile.get("age"));
        if (age > 0 && age < 13) {
            return "Takeoff can be loud and the seat may feel like it is gently pushing you back. Keep your belt fastened, listen to the flight crew and the adult with you, and keep your device in the mode the airline allows. I’m staying with you, " + name + ". We can do flower-and-candle breathing, count colors in the cabin, or sing together.";
        }
        return "Takeoff can include changes in engine sound, angle and pressure. Keep your seat belt fastened, follow the flight crew, and use your device only as the airline permits. I cannot assess the aircraft from the phone. I can guide slow breathing, talk with you, or keep your attention on a game until the climb feels more manageable, " + name + ".";
    }

    public static String landingSupport(Map<String, String> profile) {
        String name = firstName(profile.getOrDefault("name", "there"));
        int age = parseAge(profile.get("age"));
        if (age > 0 && age < 13) {
            return "We’re doing the landing part now, " + name + ". Keep your belt fastened and listen to the crew and the adult with you. You may notice sounds and gentle changes as the airplane gets ready to land. Let’s make the time smaller: one slow flower breath, one slow candle breath, then find three blue things.";
        }
        return "During landing, keep your seat belt fastened and follow the crew. Changes in sound, movement and pressure can happen as the airplane prepares to land, but I cannot assess the aircraft from the phone. I can stay with you through a few slow breaths or give you a focused game until you are back at the gate, " + name + ".";
    }

    public static String quietCompany(Map<String, String> profile) {
        String name = firstName(profile.getOrDefault("name", "there"));
        return "I’m here, " + name + ". You do not have to explain anything. We can take this one minute at a time. Notice the support of the seat, loosen your jaw, and let your next breath happen without forcing it. When you are ready, choose talking, breathing, trivia, a noticing game, or a sing-along.";
    }

    public static String concernResponse(String concern, Map<String, String> profile) {
        String lower = concern == null ? "" : concern.toLowerCase(Locale.US);
        String name = firstName(profile.getOrDefault("name", "there"));
        if (lower.contains("sound") || lower.contains("noise")) {
            return "The changing sounds are bothering you. I cannot identify or judge a sound from here, but we can keep your attention on something steady: the feel of your feet, a slow exhale, or a trivia question. If a sound seems unusual or you need reassurance, ask the flight crew, " + name + ".";
        }
        if (lower.contains("movement") || lower.contains("bump")) {
            return "The movement is what is bothering you. Keep the belt fastened and follow the crew. Press your feet gently into the floor, relax them, and repeat. I can count with you or start a game so every movement does not get all of your attention.";
        }
        if (lower.contains("control")) {
            return "Not being in control can make every sensation feel bigger. Let’s choose what you can control right now: your seat belt, your shoulders, where you look, and whether we breathe, talk, or play. You only need to choose the next small thing, " + name + ".";
        }
        if (lower.contains("company") || lower.contains("talk")) {
            return "I’ll stay with you, " + name + ". Tell me about a favorite movie, a place you want to see, something funny that happened, or nothing important at all. The conversation does not have to be about flying.";
        }
        return quietCompany(profile);
    }

    public static String groundingSupport() {
        return "Let’s make the cabin smaller for a moment: notice five things you can see, four things you can feel, three things you can hear, two things you can smell, and one thing you want to do after you land. You do not have to rush.";
    }

    public static String childBreathingIntroduction() {
        return "Let’s do flower-and-candle breathing. Smell the pretend flower gently for three counts. Then blow out the pretend candle slowly for four counts. If counting feels uncomfortable, breathe normally and just listen to my voice.";
    }

    public static String adultBreathingIntroduction() {
        return "Let’s use a gentle rhythm rather than a very deep breath. Breathe in comfortably for four counts and breathe out for six. Do not force it, and return to normal breathing if you feel lightheaded or uncomfortable.";
    }

    public static String triviaIntroduction(Map<String, String> profile) {
        String destination = bestDestination(List.of(), List.of(), profile);
        return "Let’s move your attention for a minute. I can give you multiple-choice trivia based on your age, interests, " + destination + ", or where you are from. The flight game works without internet.";
    }

    public static List<Question> questions(
            Map<String, String> profile,
            List<Map<String, String>> trips,
            List<Map<String, String>> wishes) {
        int age = parseAge(profile.get("age"));
        String destination = bestDestination(trips, wishes, profile);
        String d = destination.toLowerCase(Locale.US);
        String interests = profile.getOrDefault("interests", "").toLowerCase(Locale.US);
        List<Question> q = new ArrayList<>();

        if (age > 0 && age < 8) {
            q.add(new Question("Which animal says meow?", new String[]{"Cat", "Duck", "Cow"}, 0, "A cat says meow."));
            q.add(new Question("What color do blue and yellow make together?", new String[]{"Green", "Purple", "Orange"}, 0, "Blue and yellow make green."));
            q.add(new Question("How many wheels does a bicycle usually have?", new String[]{"Two", "Four", "Six"}, 0, "A bicycle usually has two wheels."));
        } else if (age > 0 && age < 13) {
            q.add(new Question("Which planet is known for its rings?", new String[]{"Saturn", "Mercury", "Earth"}, 0, "Saturn is famous for its ring system."));
            q.add(new Question("Which animal is the largest living animal?", new String[]{"Blue whale", "Elephant", "Giraffe"}, 0, "The blue whale is the largest living animal."));
            q.add(new Question("Which ocean is between North America and Europe?", new String[]{"Atlantic", "Indian", "Arctic"}, 0, "The Atlantic Ocean lies between them."));
        } else {
            q.add(new Question("Which city has the airport code LHR?", new String[]{"London", "Rome", "Tokyo"}, 0, "LHR is London Heathrow Airport."));
            q.add(new Question("Which rail service operates many intercity passenger trains in the United States?", new String[]{"Amtrak", "NASA", "FEMA"}, 0, "Amtrak operates intercity passenger rail service."));
            q.add(new Question("Which compass direction is opposite east?", new String[]{"West", "North", "South"}, 0, "West is opposite east."));
        }

        if (d.contains("paris") || d.contains("france")) {
            if (age > 0 && age < 13) {
                q.add(new Question("Which Paris landmark is a tall iron tower?", new String[]{"Eiffel Tower", "Big Ben", "Space Needle"}, 0, "The Eiffel Tower is one of Paris’s best-known landmarks."));
                q.add(new Question("Which river runs through Paris?", new String[]{"Seine", "Hudson", "Nile"}, 0, "The Seine runs through Paris."));
            } else {
                q.add(new Question("Which river runs through Paris?", new String[]{"Seine", "Thames", "Hudson"}, 0, "The Seine runs through Paris."));
                q.add(new Question("The Louvre was originally built in the medieval period as what?", new String[]{"A fortress", "A train station", "A theater"}, 0, "The Louvre began as a fortress."));
            }
        }
        if (d.contains("new york")) {
            q.add(new Question("Which large park is in the middle of Manhattan?", new String[]{"Central Park", "Yellowstone", "Hyde Park"}, 0, "Central Park is in Manhattan."));
            q.add(new Question("Which free ferry is known for harbor and skyline views?", new String[]{"Staten Island Ferry", "Channel Tunnel", "Orient Express"}, 0, "The Staten Island Ferry is a well-known free ride."));
        }
        if (d.contains("london")) {
            q.add(new Question("Which river runs through London?", new String[]{"Thames", "Seine", "Danube"}, 0, "The Thames runs through London."));
        }
        if (interests.contains("marvel")) {
            q.add(new Question("Which Marvel hero is known for carrying a vibranium shield?", new String[]{"Captain America", "Thor", "Hulk"}, 0, "Captain America is known for the shield."));
        }
        if (interests.contains("miraculous")) {
            q.add(new Question("Which hero is associated with magical ladybug earrings?", new String[]{"Ladybug", "Elsa", "Wonder Woman"}, 0, "Ladybug uses the earrings in Miraculous."));
        }
        if (interests.contains("history")) {
            q.add(new Question("Which ancient civilization built the Colosseum?", new String[]{"Romans", "Vikings", "Aztecs"}, 0, "The Romans built the Colosseum."));
        }

        q.add(new Question("What should you generally do during turbulence?", new String[]{"Keep the seat belt fastened and follow crew instructions", "Stand in the aisle", "Open the overhead bin"}, 0, "Remain seated, keep the belt fastened, and follow the crew."));
        q.add(new Question("Which item is useful to keep easy to reach during a flight?", new String[]{"A small comfort item or headphones", "A checked suitcase", "A hotel television"}, 0, "A familiar comfort item or approved headphones can help with distraction."));
        return q;
    }

    public static List<String> noticingPrompts(int age) {
        if (age > 0 && age < 8) {
            return List.of(
                    "Find something blue.",
                    "Find something shaped like a circle.",
                    "Find something soft.",
                    "Find something with a number on it.",
                    "Find something smaller than your hand.",
                    "Find something that makes you smile.");
        }
        return List.of(
                "Find three different shades of one color.",
                "Notice one steady sound underneath the other sounds.",
                "Find five letters from the alphabet around you.",
                "Notice where your feet and back touch something supportive.",
                "Find one object with an interesting texture.",
                "Name one thing you would like to do after landing.");
    }

    private static String bestDestination(
            List<Map<String, String>> trips,
            List<Map<String, String>> wishes,
            Map<String, String> profile) {
        for (Map<String, String> t : trips) {
            String status = t.getOrDefault("status", "").toLowerCase(Locale.US);
            if (status.contains("current") || status.contains("planned")) {
                return t.getOrDefault("destination", "your destination");
            }
        }
        if (!wishes.isEmpty()) return wishes.get(0).getOrDefault("destination", "your destination");
        return profile.getOrDefault("hometown", "your destination");
    }

    public static int parseAge(String value) {
        try { return Integer.parseInt(value == null ? "-1" : value); }
        catch (Exception ignored) { return -1; }
    }

    private static String firstName(String value) {
        String safe = value == null ? "there" : value.trim();
        if (safe.isEmpty()) return "there";
        int space = safe.indexOf(' ');
        return space > 0 ? safe.substring(0, space) : safe;
    }
}
