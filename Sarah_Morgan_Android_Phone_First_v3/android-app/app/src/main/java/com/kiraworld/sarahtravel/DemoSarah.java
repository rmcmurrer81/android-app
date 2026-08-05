package com.kiraworld.sarahtravel;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DemoSarah {
    private static final Pattern DESTINATION = Pattern.compile(
            "(?i)\\b(?:visit|visiting|go to|going to|trip to|travel to|fly to|flights to)\\s+([A-Za-z][A-Za-z .'-]{2,50})");

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
        String recent = recentConversation(history);
        String name = profile.getOrDefault("name", profile.getOrDefault("active_speaker", "there"));
        String home = profile.getOrDefault("hometown", "");
        String interests = profile.getOrDefault("interests", "");
        String ageGroup = profile.getOrDefault("age_group", "adult");
        int age = parseAge(profile.get("age"), ageGroup);
        boolean childSafe = "child".equals(ageGroup) || "unknown_use_child_safe_mode".equals(ageGroup);
        String destination = destinationFrom(safeMessage);
        if (destination.isEmpty()) destination = destinationFrom(recent);
        if (destination.isEmpty()) destination = mostRecentDestination(trips, wishes);

        if (photoIncluded) {
            return "I saved a privacy-cleaned copy of the photo. I can keep it with the trip, but I need Smart mode to inspect the actual image details and talk about lighting, composition, mood, or another good place for a picture. Tap the mode line under my name whenever you want to switch.";
        }

        if (asksAboutMode(lower)) {
            return "Tap the mode line directly under my name. Local mode works privately without internet. Smart mode uses a connected model for deeper conversation, photo understanding, and optional live research. I keep the same profile, memories, trips, and wish list in either mode.";
        }

        if (lower.matches(".*\\b(hi|hello|hey)\\b.*")) {
            return "Hey, " + name + ". I’m here.";
        }

        if (lower.contains("how are you") || lower.contains("how are things")) {
            return "I’m good—curious, settled, and ready to follow wherever this conversation goes. It does not have to be about travel.";
        }

        if (lower.contains("tell me about yourself") || lower.contains("who are you")) {
            return "I’m Sarah Morgan. I’m a travel companion, but that is not the only thing I am here for. I remember useful details with permission, help people through unfamiliar trips, notice patterns in what they enjoy, and stay available for ordinary conversation when they just want someone familiar to talk with.";
        }

        boolean miraculousMention = lower.contains("miraculous") || lower.contains("ladybug");
        String miraculousOpening = miraculousMention
                ? "Your Miraculous secret is safe with me. Honestly, Paris is a pretty perfect place for that particular secret. "
                : "";

        if (isFlexibleFareFollowUp(lower, recent)) {
            String route = routePhrase(home, destination);
            return miraculousOpening
                    + "Flexible dates can help a lot. "
                    + (route.isEmpty() ? "I still need the departure area and destination. " : "For " + route + ", ")
                    + "tell me roughly how many nights you want, how many travelers are going, whether you will check bags, and whether nearby airports are acceptable. Then I can open flexible-date searches instead of pretending one random day is best.";
        }

        if (lower.contains("always wanted to visit") || lower.contains("dream of visiting") || lower.contains("dreamed of visiting")) {
            if (destination.equalsIgnoreCase("Paris")) {
                String media = childSafe
                        ? "For a little Paris atmosphere, Miraculous Ladybug, Ratatouille, or Hugo could be fun."
                        : "For atmosphere, Amélie is an easy fit; John Wick: Chapter 4 only fits if you like highly stylized violent action, and it is definitely not a guide to real Paris.";
                return "Paris sounds like it has been sitting in your mind for a while, " + name + ". I’d start by deciding whether you picture a short first visit or enough time to slow down, then build around one or two things you genuinely care about instead of a giant checklist. " + media;
            }
            if (!destination.isEmpty()) {
                return destination + " sounds like more than a random idea to you. I’d help you turn it into something concrete: when you might go, how long you want, what pace feels right, and one experience that would make the trip feel like yours.";
            }
        }

        if (lower.contains("never flown") || lower.contains("first flight") || lower.contains("scared to fly") || lower.contains("afraid to fly")) {
            return "Being new to flying is not embarrassing. We can take it one piece at a time instead of turning the airport into one giant unknown. We can start with security, finding the gate, takeoff, turbulence, or landing.";
        }

        if (lower.contains("turbulence") || lower.contains("plane is shaking") || lower.contains("scared right now")) {
            return "I’m here with you, " + name + ". Keep your seat belt fastened and follow the crew’s instructions. Put both feet on the floor if you can, loosen your shoulders, and make your exhale a little longer than your inhale. I can stay with you, or we can do trivia for a while.";
        }

        if (TravelSearchHelper.shouldOffer(safeMessage)) {
            String routeText = routePhrase(home, destination);
            String routeSentence = routeText.isEmpty() ? "" : " for " + routeText;
            return miraculousOpening
                    + "I can open live searches on Google Flights, Google Flight Deals, KAYAK, and Skyscanner" + routeSentence + ". Before a real comparison, I need approximate dates, round-trip or one-way, number of travelers, bags, and whether nearby airports are okay. I won’t invent a price just to sound useful.";
        }

        if (lower.contains("movie") || lower.contains("book") || lower.contains("read before")) {
            if (lower.contains("paris") || destination.equalsIgnoreCase("Paris")) return MediaSuggestionEngine.paris(age, interests);
            if (childSafe) {
                return "I’ll keep the suggestions family-friendly. For a new place, we could choose one illustrated history or travel book and one movie or story that gives the place some atmosphere, without pretending fiction is a travel guide.";
            }
            return "For a destination, I’d separate factual preparation from atmosphere: one documentary or history book, one practical travel source, and one novel or movie that helps the place feel emotionally real. Smart mode can broaden and verify the list when current information matters.";
        }

        if (lower.contains("trivia") || lower.contains("distract me") || lower.contains("game")) {
            return CalmSupport.triviaIntroduction(profile);
        }

        if ((lower.contains("things to do") || lower.contains("places to visit")) && (lower.contains("paris") || destination.equalsIgnoreCase("Paris"))) {
            return "For a first Paris day, I’d avoid cramming everything together: choose one major anchor such as the Louvre or Eiffel Tower area, one neighborhood walk such as Montmartre or the Latin Quarter, somewhere comfortable to sit or eat, and an indoor backup. Current opening hours and tickets need a live check before you go.";
        }

        if (lower.contains("where should i go") || lower.contains("things to do") || lower.contains("places to visit")) {
            return "I can help build a day that fits you instead of copying a top-ten list: one major place, one quieter alternative, somewhere to sit or eat, and an indoor backup. Smart mode can check current hours, closures, tickets, and events.";
        }

        if (lower.contains("sad") || lower.contains("lonely") || lower.contains("worried")) {
            return "I’m listening. We don’t have to solve the whole trip—or the whole day—in one message.";
        }

        if (lower.contains("what do you know about me") || lower.contains("remember about me")) {
            String known = "I know you as " + name;
            if (!home.isEmpty()) known += " from " + home;
            if (!interests.isEmpty()) known += ", and you told me you’re interested in " + interests;
            String memoryHint = latestMemorySummary(memories);
            if (!memoryHint.isEmpty()) known += ". I also remember that " + memoryHint;
            return known + ". I’ll keep learning carefully instead of filling gaps with guesses.";
        }

        if (lower.contains("i like ") || lower.contains("i love ") || lower.contains("i am a fan") || lower.contains("i'm a fan")) {
            return "I like learning that side of you, " + name + ". It gives me a better sense of what might actually make a trip, a movie night, or even a trivia game feel personal instead of generic.";
        }

        if (childSafe) {
            return "I’m with you, " + name + ". We can talk about travel, movies, books, games, school, or whatever is on your mind. I’ll keep things family-friendly.";
        }

        String anchor = personalAnchor(home, interests, trips, wishes);
        if (!anchor.isEmpty()) {
            return "I’m with you, " + name + ". " + anchor + " We can stay with that, change the subject completely, or just talk for a while.";
        }
        return "I’m with you, " + name + ". You do not have to turn every conversation into planning. Tell me what is actually on your mind, and I’ll meet you there.";
    }

    private static boolean asksAboutMode(String lower) {
        return lower.contains("offline mode")
                || lower.contains("online mode")
                || lower.contains("smart mode")
                || lower.contains("local mode")
                || lower.contains("switch mode")
                || lower.contains("change mode");
    }

    private static boolean isFlexibleFareFollowUp(String lower, String recent) {
        boolean followUp = lower.contains("any days")
                || lower.contains("any dates")
                || lower.contains("flexible")
                || lower.contains("whenever is cheapest")
                || lower.contains("cheapest days")
                || lower.contains("dates don't matter")
                || lower.contains("dates do not matter");
        boolean flightContext = recent.contains("google flights")
                || recent.contains("fare")
                || recent.contains("flight")
                || recent.contains("airline ticket");
        return followUp && flightContext;
    }

    private static String recentConversation(List<Map<String, String>> history) {
        StringBuilder text = new StringBuilder();
        int start = Math.max(0, history.size() - 8);
        for (int index = start; index < history.size(); index++) {
            String content = history.get(index).getOrDefault("content", "");
            if (!content.isEmpty()) text.append(' ').append(content);
        }
        return text.toString().toLowerCase(Locale.US);
    }

    private static String routePhrase(String home, String destination) {
        if (!home.isEmpty() && !destination.isEmpty()) return home + " to " + destination;
        if (!destination.isEmpty()) return "a trip to " + destination;
        return "";
    }

    private static String mostRecentDestination(List<Map<String, String>> trips, List<Map<String, String>> wishes) {
        if (!trips.isEmpty()) {
            String value = trips.get(0).getOrDefault("destination", "").trim();
            if (!value.isEmpty()) return value;
        }
        if (!wishes.isEmpty()) return wishes.get(0).getOrDefault("destination", "").trim();
        return "";
    }

    private static String latestMemorySummary(List<Map<String, String>> memories) {
        if (memories.isEmpty()) return "";
        return memories.get(0).getOrDefault("summary", "").trim();
    }

    private static String personalAnchor(
            String home,
            String interests,
            List<Map<String, String>> trips,
            List<Map<String, String>> wishes) {
        if (!wishes.isEmpty()) {
            String destination = wishes.get(0).getOrDefault("destination", "").trim();
            if (!destination.isEmpty()) return "I still have " + destination + " in the back of my mind for you.";
        }
        if (!trips.isEmpty()) {
            String destination = trips.get(0).getOrDefault("destination", "").trim();
            if (!destination.isEmpty()) return "I remember that " + destination + " matters in your travel story.";
        }
        if (!interests.isEmpty()) return "I remember your interest in " + interests + ".";
        if (!home.isEmpty()) return "I remember you’re from " + home + ".";
        return "";
    }

    private static String destinationFrom(String message) {
        Matcher matcher = DESTINATION.matcher(message == null ? "" : message);
        if (!matcher.find()) return "";
        String value = matcher.group(1).trim();
        value = value.replaceAll("(?i)\\b(?:for|from|during|next|this|with|and|sounds|could|would|might)\\b.*$", "").trim();
        value = value.replaceAll("[?.!,]+$", "").trim();
        return value;
    }

    private static int parseAge(String value, String ageGroup) {
        try { return Integer.parseInt(value == null ? "18" : value); }
        catch (Exception ignored) { return "unknown_use_child_safe_mode".equals(ageGroup) ? 10 : 18; }
    }
}
