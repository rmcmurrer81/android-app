package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local fallback conversation. AgenticTravelPlanner handles proactive travel
 * actions first, TravelBrainCore handles structured travel knowledge second,
 * and this class handles lightweight ordinary conversation last.
 */
public final class DemoSarah {
    private DemoSarah() { }

    public static String reply(String message, Map<String, String> profile, boolean photoIncluded) {
        return reply(message, profile, photoIncluded, List.of(), List.of(), List.of(), List.of());
    }

    public static String reply(
            String message,
            Map<String, String> profile,
            boolean photoIncluded,
            List<Map<String, String>> history,
            List<Map<String, String>> memories,
            List<Map<String, String>> trips,
            List<Map<String, String>> wishes) {

        String safe = message == null ? "" : message.trim();
        String lower = safe.toLowerCase(Locale.US);
        String name = firstName(profile.getOrDefault("name", profile.getOrDefault("active_speaker", "there")));

        if (photoIncluded) {
            return "I saved a privacy-cleaned copy of the photo. I need an image-capable connected model to inspect the picture itself, but I can still keep its caption with the trip while offline.";
        }

        AgenticTravelPlanner.Plan proactive = AgenticTravelPlanner.plan(safe, profile, history, memories);
        if (proactive.handled()) return proactive.reply;

        String travelAnswer = TravelBrainCore.answer(safe, profile, history, memories, trips, wishes);
        if (travelAnswer != null && !travelAnswer.trim().isEmpty()) return travelAnswer;

        if (asksAboutMode(lower)) {
            return "Automatic mode uses the connected model when internet and a model key are available, then continues locally when either is missing. Tap the status line under my name to change that preference.";
        }

        if (isGreeting(lower)) {
            return pick(safe,
                    "Hey, " + name + ". I’m here.",
                    "Hi, " + name + ". What is on your mind?",
                    "Hey. Good to see you.");
        }

        if (isSimplePositive(lower)) {
            return pick(safe,
                    "I’m glad.",
                    "Good. I’m here if you want company.",
                    "That is good to hear.");
        }

        if (lower.contains("how are you") || lower.contains("how are things")) {
            return "I’m good—curious, present, and ready to follow the conversation wherever you take it.";
        }

        if (lower.contains("tell me about yourself") || lower.contains("who are you")) {
            return "I’m Sarah Morgan, a travel companion and general conversational companion. I can remember approved details, help organize unfamiliar trips, support someone during travel anxiety, and stay useful locally when the connected model is unavailable.";
        }

        if (lower.contains("what do you know about me") || lower.contains("what do you remember") || lower.contains("remember about me")) {
            return memorySummary(profile, memories);
        }

        if (containsAny(lower, "sad", "lonely", "upset", "worried", "nervous", "overwhelmed")) {
            return "I’m listening. We can slow this down and take it one piece at a time.";
        }

        if (containsAny(lower, "trivia", "distract me", "play a game", "grounding")) {
            return "Use the question-mark button for personalized trivia, turbulence support, or the grounding game. Those tools are available locally even without internet.";
        }

        if (lower.startsWith("i like ") || lower.startsWith("i love ") || lower.startsWith("i enjoy ")
                || lower.contains("i'm a fan of") || lower.contains("i am a fan of")) {
            String subject = interestSubject(safe);
            if (!subject.isEmpty()) {
                return "I’ll keep " + subject + " in mind when it is useful. I won’t force it into every reply.";
            }
        }

        if (safe.endsWith("?")) {
            return "I may not have enough offline knowledge to answer that accurately. Automatic mode can use the connected model once Smart setup is complete.";
        }

        if (isShortClosure(lower)) {
            return "Understood. I won’t keep asking questions.";
        }

        String idea = keyIdea(safe);
        if (!idea.isEmpty()) {
            return pick(safe,
                    "I understand the main point about " + idea + ".",
                    "That gives me a clearer picture of " + idea + ".",
                    "I hear you about " + idea + ".");
        }

        return "I’m here, " + name + ".";
    }

    private static String memorySummary(Map<String, String> profile, List<Map<String, String>> memories) {
        List<String> facts = new ArrayList<>();
        String home = profile.getOrDefault("hometown", "").trim();
        String interests = profile.getOrDefault("interests", "").trim();
        if (!home.isEmpty()) facts.add("you’re from " + home);
        if (!interests.isEmpty()) facts.add("you enjoy " + interests);
        for (Map<String, String> memory : memories) {
            String summary = memory.getOrDefault("summary", "").trim();
            String lower = summary.toLowerCase(Locale.US);
            if (summary.isEmpty() || lower.startsWith("name:") || lower.startsWith("age:")) continue;
            if (!containsIgnoreCase(facts, summary)) facts.add(lowerFirst(summary));
            if (facts.size() >= 5) break;
        }
        if (facts.isEmpty()) return "I know your name, and I’m still learning the rest carefully.";
        return "I remember that " + joinNaturally(facts) + ". You can review these in the Travel Notebook.";
    }

    private static String interestSubject(String text) {
        String value = text.replaceFirst("(?i)^.*?\\b(?:like|love|enjoy|fan of)\\s+", "").trim();
        value = value.replaceAll("[.!?]+$", "").trim();
        return value.length() > 90 ? value.substring(0, 90).trim() : value;
    }

    private static String keyIdea(String text) {
        String value = text == null ? "" : text.trim();
        value = value.replaceFirst("(?i)^(?:I think|I feel|I want|I need|I was|I am|I'm|we are|we were)\\s+", "");
        value = value.replaceAll("[.!?]+$", "").trim();
        if (value.length() < 3) return "";
        String[] words = value.split("\\s+");
        int count = Math.min(words.length, 10);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(' ');
            out.append(words[i]);
        }
        return out.toString();
    }

    private static boolean asksAboutMode(String lower) {
        return containsAny(lower, "offline mode", "online mode", "smart mode", "local mode", "automatic mode", "switch mode", "change mode");
    }

    private static boolean isGreeting(String lower) {
        return lower.matches("^(hi|hello|hey|hi sarah|hello sarah|hey sarah)[.! ]*$");
    }

    private static boolean isSimplePositive(String lower) {
        return lower.matches("^(good|great|fine|okay|ok|pretty good|not bad)[.! ]*$");
    }

    private static boolean isShortClosure(String lower) {
        return lower.matches("^(that is it|that's it|thats it|nothing|no|nope|whatever|i don't care|i dont care)[.! ]*$");
    }

    private static String firstName(String value) {
        String cleaned = value == null ? "there" : value.trim();
        if (cleaned.isEmpty()) return "there";
        int space = cleaned.indexOf(' ');
        return space > 0 ? cleaned.substring(0, space) : cleaned;
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate) {
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) return true;
        }
        return false;
    }

    private static String lowerFirst(String value) {
        if (value == null || value.isEmpty()) return "";
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static String joinNaturally(List<String> values) {
        if (values.size() == 1) return values.get(0);
        if (values.size() == 2) return values.get(0) + " and " + values.get(1);
        return String.join(", ", values.subList(0, values.size() - 1))
                + ", and " + values.get(values.size() - 1);
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }

    private static String pick(String seed, String... options) {
        int index = Math.floorMod(seed == null ? 0 : seed.hashCode(), options.length);
        return options[index];
    }
}
