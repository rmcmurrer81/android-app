package com.kiraworld.sarahtravel;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DemoSarah {
    private static final Pattern DESTINATION = Pattern.compile(
            "(?i)\\b(?:visit|visiting|go to|going to|trip to|travel to|fly to|flights to)\\s+([A-Za-z][A-Za-z .'-]{2,50})");

    private DemoSarah() { }

    public static String reply(String message, Map<String, String> profile, boolean photoIncluded) {
        String lower = message.toLowerCase(Locale.US);
        String name = profile.getOrDefault("name", profile.getOrDefault("active_speaker", "there"));
        String home = profile.getOrDefault("hometown", "");
        String interests = profile.getOrDefault("interests", "");
        String ageGroup = profile.getOrDefault("age_group", "adult");
        int age = parseAge(profile.get("age"), ageGroup);
        boolean childSafe = "child".equals(ageGroup) || "unknown_use_child_safe_mode".equals(ageGroup);
        String destination = destinationFrom(message);

        if (photoIncluded) {
            return "I saved a cleaned copy of the photo, but offline mode cannot actually see the image. With a vision-capable model connected, I can discuss the composition, lighting, mood, and another respectful place or angle for a picture.";
        }

        if (lower.matches(".*\\b(hi|hello|hey)\\b.*")) {
            return "Hey, " + name + ". I’m here.";
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

        if (TravelSearchHelper.shouldOffer(message)) {
            String routeText;
            if (!destination.isEmpty() && !home.isEmpty()) routeText = " from " + home + " to " + destination;
            else if (!destination.isEmpty()) routeText = " to " + destination;
            else routeText = "";
            return "I can open live searches on Google Flights, Google Flight Deals, KAYAK, and Skyscanner for fares" + routeText + ". Before a real comparison, I need approximate dates, round-trip or one-way, number of travelers, bags, and whether nearby airports are okay. I won’t invent a price just to sound useful.";
        }

        if (lower.contains("movie") || lower.contains("book") || lower.contains("read before")) {
            if (lower.contains("paris")) return MediaSuggestionEngine.paris(age, interests);
            if (childSafe) {
                return "I’ll keep the suggestions family-friendly. For a new place, we could choose one illustrated history or travel book and one movie or story that gives the place some atmosphere, without pretending fiction is a travel guide.";
            }
            return "For a destination, I’d separate factual preparation from atmosphere: one documentary or history book, one practical travel source, and one novel or movie that helps the place feel emotionally real. Live search can broaden and verify the list.";
        }

        if (lower.contains("trivia") || lower.contains("distract me") || lower.contains("game")) {
            return CalmSupport.triviaIntroduction(profile);
        }

        if ((lower.contains("things to do") || lower.contains("places to visit")) && lower.contains("paris")) {
            return "For a first Paris day, I’d avoid cramming everything together: choose one major anchor such as the Louvre or Eiffel Tower area, one neighborhood walk such as Montmartre or the Latin Quarter, somewhere comfortable to sit or eat, and an indoor backup. Current opening hours and tickets need a live check before you go.";
        }

        if (lower.contains("where should i go") || lower.contains("things to do") || lower.contains("places to visit")) {
            return "I can help build a day that fits you instead of copying a top-ten list: one major place, one quieter alternative, somewhere to sit or eat, and an indoor backup. Live search can check current hours, closures, tickets, and events.";
        }

        if (lower.contains("sad") || lower.contains("lonely") || lower.contains("worried")) {
            return "I’m listening. We don’t have to solve the whole trip—or the whole day—in one message.";
        }

        if (lower.contains("what do you know about me") || lower.contains("remember about me")) {
            String known = "I know you as " + name;
            if (!home.isEmpty()) known += " from " + home;
            if (!interests.isEmpty()) known += ", and you told me you’re interested in " + interests;
            return known + ". I’ll keep learning carefully instead of filling gaps with guesses.";
        }

        if (childSafe) {
            return "I’m with you, " + name + ". We can talk about travel, movies, books, games, school, or whatever is on your mind. I’ll keep things family-friendly.";
        }

        return "I’m with you, " + name + ". Offline mode gives me a smaller brain than the connected version, but I can still remember useful details, talk through travel worries, play games, and open live travel searches when current information matters.";
    }

    private static String destinationFrom(String message) {
        Matcher matcher = DESTINATION.matcher(message == null ? "" : message);
        if (!matcher.find()) return "";
        String value = matcher.group(1).trim();
        value = value.replaceAll("(?i)\\b(?:for|from|during|next|this|with|and)\\b.*$", "").trim();
        value = value.replaceAll("[?.!,]+$", "").trim();
        return value;
    }

    private static int parseAge(String value, String ageGroup) {
        try { return Integer.parseInt(value == null ? "18" : value); }
        catch (Exception ignored) { return "unknown_use_child_safe_mode".equals(ageGroup) ? 10 : 18; }
    }
}
