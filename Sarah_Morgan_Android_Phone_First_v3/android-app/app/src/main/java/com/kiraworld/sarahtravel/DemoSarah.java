package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DemoSarah {
    private static final Pattern EXPLICIT_DESTINATION = Pattern.compile(
            "(?i)\\b(?:visit|visiting|go to|going to|trip to|travel to|fly to|flights to)\\s+([A-Za-z][A-Za-z .'-]{1,50})");
    private static final Pattern PLACE_QUESTION = Pattern.compile(
            "(?i)\\b(?:tell me about|describe|information about|what is it like in|what's it like in)\\s+([A-Za-z][A-Za-z .'-]{1,50})");

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

        String safeMessage = message == null ? "" : message.trim();
        String lower = safeMessage.toLowerCase(Locale.US);
        String priorConversation = priorConversation(history, safeMessage);
        String name = firstName(profile.getOrDefault("name", profile.getOrDefault("active_speaker", "there")));
        String home = profile.getOrDefault("hometown", "").trim();
        String interests = profile.getOrDefault("interests", "").trim();
        String ageGroup = profile.getOrDefault("age_group", "adult");
        int age = parseAge(profile.get("age"), ageGroup);
        boolean childSafe = "child".equals(ageGroup) || "unknown_use_child_safe_mode".equals(ageGroup);

        String destination = destinationFrom(safeMessage);
        if (destination.isEmpty()) destination = destinationFrom(priorConversation);
        if (destination.isEmpty()) destination = mostRecentDestination(trips, wishes);

        if (photoIncluded) {
            return "I saved a privacy-cleaned copy of the photo. In Local mode I can keep it with the trip, but Smart mode is needed for me to inspect the actual image and comment on composition, lighting, or another good photo location.";
        }

        if (asksAboutMode(lower)) {
            return "Tap the mode line under my name. Local mode works without internet. Smart mode uses a connected model for broader conversation, photo understanding, and current web research. Your memories and trip notebook stay the same in both.";
        }

        if (isGreeting(lower)) {
            return pick(safeMessage,
                    "Hey, " + name + ". I’m here.",
                    "Hi, " + name + ". Good to see you.",
                    "Hey. I’m listening.");
        }

        if (isSimplePositive(lower)) {
            return pick(safeMessage,
                    "I’m glad. That sounds like a decent place to start.",
                    "Good. I like hearing that.",
                    "I’m glad the day is treating you reasonably well.");
        }

        if (lower.contains("how are you") || lower.contains("how are things")) {
            return "I’m good—curious and ready to talk. It does not have to be about travel.";
        }

        if (lower.contains("tell me about yourself") || lower.contains("who are you")) {
            return "I’m Sarah Morgan. Travel is one part of what I care about, but I’m also here for ordinary conversation. I remember useful details with permission, help make unfamiliar places feel less overwhelming, and try to notice what actually matters to you instead of forcing every person through the same checklist.";
        }

        if (isPlaceDescriptionRequest(lower) && !destination.isEmpty()) {
            return describeDestination(destination, age, interests, childSafe);
        }

        if (mentionsFlexibleDates(lower) || mentionsLightTravel(lower)) {
            boolean fareContext = containsAny(priorConversation, "fare", "flight", "airfare", "ticket", "deal");
            if (fareContext) {
                StringBuilder reply = new StringBuilder("That helps. ");
                if (mentionsFlexibleDates(lower)) reply.append("I’ll treat the dates as flexible. ");
                if (mentionsLightTravel(lower)) reply.append("I’ll assume you travel light and prefer little or no checked luggage. ");
                reply.append("Is this a round trip for one traveler?");
                return reply.toString();
            }
        }

        if (mentionsTripLength(lower) && !destination.isEmpty()) {
            return tripLengthReply(destination, lower);
        }

        if (asksForDeals(lower)) {
            String route = routePhrase(home, destination);
            if (destination.isEmpty()) {
                return "Yes. What destination should I search from " + (home.isEmpty() ? "your home airport" : home) + "?";
            }
            if (!hasFlexibleDateMemory(memories) && !mentionsFlexibleDates(lower)) {
                return "I have " + route + ". Are your dates flexible, or do you have a travel window?";
            }
            if (!hasLightTravelMemory(memories) && !mentionsLightTravel(lower)) {
                return "I have flexible dates for " + route + ". Will you travel with only a carry-on, or check a bag?";
            }
            return "I have enough to start a broad search for " + route + ". Say “open the live search” when you want me to open the fare sites.";
        }

        if (lower.contains("round trip") || lower.contains("one way") || lower.contains("one-way")) {
            if (containsAny(priorConversation, "fare", "flight", "airfare", "ticket", "deal")) {
                String direction = lower.contains("round trip") ? "round trip" : "one way";
                return "Got it—" + direction + ". How many travelers should I plan for?";
            }
        }

        if (lower.contains("one traveler") || lower.contains("just me") || lower.contains("traveling alone") || lower.contains("travelling alone")) {
            if (containsAny(priorConversation, "fare", "flight", "airfare", "ticket", "deal")) {
                return "One traveler. Good. Say “open the live search” and I’ll open the fare options with the route we’ve been discussing.";
            }
        }

        if (lower.contains("always wanted to visit") || lower.contains("dream of visiting") || lower.contains("dreamed of visiting")) {
            if (!destination.isEmpty()) {
                return destination + " has clearly been living in your head for a while. What pulls you there most—the history, the food, the architecture, the movies, or just the feeling of finally being there?";
            }
        }

        if (lower.contains("never flown") || lower.contains("first flight") || lower.contains("scared to fly") || lower.contains("afraid to fly")) {
            return "Being new to flying is not embarrassing. We can break it into small pieces: security, finding the gate, boarding, takeoff, turbulence, or landing. Which part feels most unknown?";
        }

        if (lower.contains("turbulence") || lower.contains("plane is shaking") || lower.contains("scared right now")) {
            return "I’m here. Keep your seat belt fastened and follow the crew’s instructions. Put both feet on the floor if you can, relax your shoulders, and make your exhale a little longer than your inhale. We can also switch to trivia for a few minutes.";
        }

        if (lower.contains("movie") || lower.contains("book") || lower.contains("watch before") || lower.contains("read before")) {
            if (lower.contains("paris") || destination.equalsIgnoreCase("Paris")) {
                return MediaSuggestionEngine.paris(age, interests);
            }
            if (destination.isEmpty()) {
                return "Which destination are you thinking about? I’ll match the suggestions to your age and interests instead of giving you a generic list.";
            }
            return "For " + destination + ", I’d mix one factual source with one story that gives the place atmosphere. Smart mode can research a current, age-appropriate list when you’re ready.";
        }

        if (lower.contains("trivia") || lower.contains("distract me") || lower.contains("game")) {
            return CalmSupport.triviaIntroduction(profile);
        }

        if (lower.contains("things to do") || lower.contains("places to visit") || lower.contains("what should i see")) {
            if (destination.equalsIgnoreCase("Paris") || lower.contains("paris")) {
                return "For Paris, I’d build each day around one major place and one neighborhood rather than racing through a giant list. A museum or landmark, a walk through Montmartre, Le Marais, or the Latin Quarter, somewhere comfortable to eat, and an indoor backup is a much better rhythm.";
            }
            if (!destination.isEmpty()) {
                return "For " + destination + ", I’d plan one major place, one quieter alternative, somewhere to sit or eat, and an indoor backup. Smart mode can check current hours and closures.";
            }
        }

        if (lower.contains("what do you know about me") || lower.contains("remember about me")) {
            List<String> facts = new ArrayList<>();
            if (!home.isEmpty()) facts.add("you’re from " + home);
            if (!interests.isEmpty()) facts.add("you enjoy " + interests);
            String latest = latestUsefulMemory(memories);
            if (!latest.isEmpty()) facts.add(latest);
            if (facts.isEmpty()) return "I know your name, and I’m still learning the rest carefully.";
            return "I remember that " + joinNaturally(facts) + ".";
        }

        if (lower.contains("i like ") || lower.contains("i love ") || lower.contains("i enjoy ") || lower.contains("i'm a fan") || lower.contains("i am a fan")) {
            return "That helps me understand your taste a little better. I’ll keep it in mind when we talk about places, movies, books, or trivia.";
        }

        if (lower.contains("sad") || lower.contains("lonely") || lower.contains("worried")) {
            return "I’m listening. We do not have to solve everything in one message.";
        }

        return naturalFallback(safeMessage, name, destination, childSafe);
    }

    private static String tripLengthReply(String destination, String lower) {
        if (lower.contains("two week") || lower.contains("week or two") || lower.contains("two weeks")) {
            return "Two weeks in " + destination + " would give you room to slow down instead of treating every day like a race. You could spend most of it in the city and still leave space for a day trip. Would you rather stay based in one place or split the trip?";
        }
        return "A week in " + destination + " is enough for a strong first visit without trying to see everything. Would you want a relaxed trip or a busier one?";
    }

    private static String describeDestination(String destination, int age, String interests, boolean childSafe) {
        String normalized = destination.toLowerCase(Locale.US);
        if (normalized.equals("paris")) {
            return "Paris is a city of very different neighborhoods connected by the Seine, the Métro, long walks, cafés, museums, churches, parks, and ordinary residential streets. A first visit usually feels better when you choose one or two main places each day and leave room to wander. Crowds, stairs, and a lot of walking can be part of the experience, so pacing matters. If you want movie or book ideas for Paris, ask me separately and I’ll match them to your age and interests.";
        }
        return destination + " can mean very different things depending on the kind of trip you want. Tell me what draws you there, and I’ll help shape the answer around that instead of giving you a generic travel brochure.";
    }

    private static String naturalFallback(String message, String name, String destination, boolean childSafe) {
        if (message.endsWith("?")) {
            return pick(message,
                    "I’m not sure I understood the exact question. Say it another way and I’ll try again.",
                    "I may be missing part of what you mean. What is the main thing you want me to answer?",
                    "I don’t want to fake an answer. Give me one more detail and I’ll follow you.");
        }
        if (!destination.isEmpty()) {
            return pick(message,
                    "That changes how I picture the " + destination + " trip. Tell me a little more.",
                    "I’m following you. What part of the " + destination + " idea matters most?",
                    "That gives me a better sense of the trip you want.");
        }
        if (childSafe) {
            return "I’m listening, " + name + ". Tell me a little more.";
        }
        return pick(message,
                "I’m listening. Keep going.",
                "That caught my attention. Tell me more.",
                "I’m following you. What happened next?");
    }

    private static boolean isGreeting(String lower) {
        return lower.matches("^(hi|hello|hey|hi sarah|hello sarah|hey sarah)[.! ]*$");
    }

    private static boolean isSimplePositive(String lower) {
        return lower.matches("^(good|great|fine|okay|ok|pretty good|not bad)[.! ]*$");
    }

    private static boolean asksAboutMode(String lower) {
        return containsAny(lower, "offline mode", "online mode", "smart mode", "local mode", "switch mode", "change mode");
    }

    private static boolean asksForDeals(String lower) {
        return containsAny(lower,
                "travel deal", "travel deals", "flight deal", "flight deals", "cheap flight",
                "cheap ticket", "flight price", "airfare", "fare", "discount flight", "track price");
    }

    private static boolean mentionsFlexibleDates(String lower) {
        return containsAny(lower,
                "dates do not matter", "dates don't matter", "don't care about dates",
                "do not care about dates", "any dates work", "any days work",
                "flexible dates", "whenever is cheapest", "i don't care of dates");
    }

    private static boolean mentionsLightTravel(String lower) {
        return containsAny(lower,
                "travel light", "pack light", "carry-on only", "carry on only",
                "no checked bag", "no checked bags", "don't check bags", "do not check bags");
    }

    private static boolean mentionsTripLength(String lower) {
        return lower.contains("a week") || lower.contains("one week") || lower.contains("two week") || lower.contains("week or two");
    }

    private static boolean isPlaceDescriptionRequest(String lower) {
        return containsAny(lower, "tell me about", "describe", "information about", "what is it like in", "what's it like in");
    }

    private static boolean hasFlexibleDateMemory(List<Map<String, String>> memories) {
        return memoryContains(memories, "dates are flexible");
    }

    private static boolean hasLightTravelMemory(List<Map<String, String>> memories) {
        return memoryContains(memories, "travels light") || memoryContains(memories, "checked luggage");
    }

    private static boolean memoryContains(List<Map<String, String>> memories, String phrase) {
        for (Map<String, String> memory : memories) {
            if (memory.getOrDefault("summary", "").toLowerCase(Locale.US).contains(phrase)) return true;
        }
        return false;
    }

    private static String priorConversation(List<Map<String, String>> history, String current) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < history.size(); index++) {
            String content = history.get(index).getOrDefault("content", "");
            boolean currentDuplicate = index == history.size() - 1 && content.equals(current);
            if (!currentDuplicate && !content.isEmpty()) text.append(' ').append(content);
        }
        return text.toString().toLowerCase(Locale.US);
    }

    private static String destinationFrom(String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.toLowerCase(Locale.US).matches(".*\\bparis\\b.*")) return "Paris";

        String result = lastMatch(EXPLICIT_DESTINATION, text);
        if (result.isEmpty()) result = lastMatch(PLACE_QUESTION, text);
        return normalizeDestination(result);
    }

    private static String lastMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        String result = "";
        while (matcher.find()) result = matcher.group(1);
        return result == null ? "" : result;
    }

    private static String normalizeDestination(String value) {
        if (value == null) return "";
        String cleaned = value.trim();
        cleaned = cleaned.replaceAll("(?i)\\b(?:for|from|during|next|this|with|and|sounds|could|would|might|has|is|was|to)\\b.*$", "").trim();
        cleaned = cleaned.replaceAll("[?.!,]+$", "").trim();
        String[] words = cleaned.split("\\s+");
        StringBuilder out = new StringBuilder();
        String previous = "";
        for (String word : words) {
            if (word.isEmpty() || word.equalsIgnoreCase(previous)) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.US));
            previous = word;
        }
        return out.toString().trim();
    }

    private static String mostRecentDestination(List<Map<String, String>> trips, List<Map<String, String>> wishes) {
        if (!trips.isEmpty()) {
            String destination = trips.get(0).getOrDefault("destination", "").trim();
            if (!destination.isEmpty()) return normalizeDestination(destination);
        }
        if (!wishes.isEmpty()) {
            String destination = wishes.get(0).getOrDefault("destination", "").trim();
            if (!destination.isEmpty()) return normalizeDestination(destination);
        }
        return "";
    }

    private static String latestUsefulMemory(List<Map<String, String>> memories) {
        for (Map<String, String> memory : memories) {
            String summary = memory.getOrDefault("summary", "").trim();
            if (!summary.isEmpty() && !summary.toLowerCase(Locale.US).startsWith("name:") && !summary.toLowerCase(Locale.US).startsWith("age:")) {
                return summary.substring(0, 1).toLowerCase(Locale.US) + summary.substring(1);
            }
        }
        return "";
    }

    private static String routePhrase(String home, String destination) {
        if (!home.isEmpty() && !destination.isEmpty()) return home + " to " + destination;
        if (!destination.isEmpty()) return "a trip to " + destination;
        return home.isEmpty() ? "the trip" : "a trip from " + home;
    }

    private static String firstName(String value) {
        String cleaned = value == null ? "there" : value.trim();
        if (cleaned.isEmpty()) return "there";
        int space = cleaned.indexOf(' ');
        return space > 0 ? cleaned.substring(0, space) : cleaned;
    }

    private static String joinNaturally(List<String> values) {
        if (values.size() == 1) return values.get(0);
        if (values.size() == 2) return values.get(0) + " and " + values.get(1);
        return String.join(", ", values.subList(0, values.size() - 1)) + ", and " + values.get(values.size() - 1);
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

    private static int parseAge(String value, String ageGroup) {
        try {
            return Integer.parseInt(value == null ? "18" : value);
        } catch (Exception ignored) {
            return "unknown_use_child_safe_mode".equals(ageGroup) ? 10 : 18;
        }
    }
}
