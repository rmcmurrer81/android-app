package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative local memory extraction. */
public final class MemoryExtractor {
    public static final class Candidate {
        public final String category;
        public final String summary;

        public Candidate(String category, String summary) {
            this.category = category;
            this.summary = summary;
        }
    }

    private static final Pattern PAST_TRIP = Pattern.compile(
            "\\b(?:I went to|I have been to|I've been to|we went to)\\s+([^.!?]{2,80})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FAVORITE = Pattern.compile(
            "\\bmy favorite ([a-zA-Z ]{2,30}) is ([^.!?]{2,80})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_DISLIKE = Pattern.compile(
            "\\bI (?:hate|dislike|can't stand|cannot stand|try to avoid|prefer to avoid)\\s+([^.!?]{2,80})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WORRY = Pattern.compile(
            "\\bI(?:'m| am) (?:worried|nervous|anxious|scared) about\\s+([^.!?]{2,100})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM = Pattern.compile(
            "\\bI(?:'m| am) from\\s+([^.!?]{2,80})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LIKES = Pattern.compile(
            "\\bI (?:really )?(?:like|love|enjoy|am a fan of|'m a fan of)\\s+([^.!?]{2,80})",
            Pattern.CASE_INSENSITIVE);

    private MemoryExtractor() { }

    public static List<Candidate> extract(String text) {
        String safe = text == null ? "" : text.trim();
        String lower = safe.toLowerCase(Locale.US);
        List<Candidate> out = new ArrayList<>();

        addDestinationWishes(out, safe, lower);
        add(out, PAST_TRIP, safe, "past_trip", "Has traveled to ");
        addFavorite(out, safe);
        add(out, EXPLICIT_DISLIKE, safe, "preference", "Dislikes or avoids ");
        add(out, WORRY, safe, "travel_worry", "Travel worry: ");
        add(out, FROM, safe, "profile", "Is from ");
        addLikes(out, safe);

        if (containsAny(lower,
                "dates do not matter", "dates don't matter", "don't care about dates",
                "do not care about dates", "don't care of dates", "do not care of dates",
                "any dates work", "any days work", "flexible dates", "whenever is cheapest")) {
            out.add(new Candidate("travel_preference", "Travel dates are flexible"));
        }

        if (containsAny(lower,
                "travel light", "pack light", "carry-on only", "carry on only",
                "no checked bag", "no checked bags", "do not check bags", "don't check bags")) {
            out.add(new Candidate("travel_preference", "Usually travels light and prefers little or no checked luggage"));
        }

        if (containsAny(lower, "one traveler", "traveling alone", "travelling alone", "solo trip", "going alone", "just me")) {
            out.add(new Candidate("travel_preference", "Usually plans for one traveler"));
        }

        if (containsAny(lower, "round trip", "round-trip")) {
            out.add(new Candidate("travel_preference", "Usually searches round-trip fares unless stated otherwise"));
        }

        if (wantsDealAlerts(lower)) {
            List<String> destinations = DestinationParser.extractDestinations(safe);
            String summary = destinations.isEmpty()
                    ? "Wants travel deal alerts"
                    : "Wants travel deal alerts for " + DestinationParser.join(destinations);
            out.add(new Candidate("deal_watch_request", summary));
        }

        if (lower.contains("never flown") || lower.contains("never been on a plane") || lower.contains("first flight")) {
            out.add(new Candidate("travel_experience", "Flying is new or this may be a first flight"));
        }
        return deduplicate(out);
    }

    private static void addDestinationWishes(List<Candidate> out, String text, String lower) {
        if (!containsAny(lower,
                "want to visit", "want to go to", "wanted to visit", "wanted to go to",
                "would love to travel to", "would like to visit", "would like to go to",
                "dream trip", "deciding between")) return;
        List<String> destinations = DestinationParser.extractDestinations(text);
        for (String destination : destinations) {
            out.add(new Candidate("wish_list", "Wants to visit " + destination));
        }
    }

    private static void add(List<Candidate> out, Pattern pattern, String text, String category, String prefix) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String value = clean(matcher.group(1));
            if (!value.isEmpty()) out.add(new Candidate(category, prefix + value));
        }
    }

    private static void addFavorite(List<Candidate> out, String text) {
        Matcher matcher = FAVORITE.matcher(text);
        if (matcher.find()) {
            String subject = clean(matcher.group(1));
            String value = clean(matcher.group(2));
            if (!subject.isEmpty() && !value.isEmpty()) {
                out.add(new Candidate("preference", "Favorite " + subject + ": " + value));
            }
        }
    }

    private static void addLikes(List<Candidate> out, String text) {
        Matcher matcher = LIKES.matcher(text);
        if (!matcher.find()) return;
        String value = clean(matcher.group(1));
        String lower = value.toLowerCase(Locale.US);
        if (value.isEmpty() || lower.startsWith("seeing it") || lower.startsWith("watching stuff")
                || lower.equals("it") || lower.startsWith("that")) return;
        out.add(new Candidate("interest", "Enjoys " + value));
    }

    private static boolean wantsDealAlerts(String lower) {
        boolean alert = containsAny(lower, "notify me", "alert me", "deal alert", "price alert", "watch prices", "track prices");
        boolean deal = containsAny(lower, "deal", "fare", "airfare", "flight price", "cheap flight", "price drop");
        return alert && deal;
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }

    private static List<Candidate> deduplicate(List<Candidate> input) {
        List<Candidate> result = new ArrayList<>();
        for (Candidate candidate : input) {
            boolean exists = false;
            for (Candidate saved : result) {
                if (saved.category.equals(candidate.category) && saved.summary.equalsIgnoreCase(candidate.summary)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) result.add(candidate);
        }
        return result;
    }

    private static String clean(String value) {
        if (value == null) return "";
        String cleaned = value.trim().replaceAll("\\s+", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(?:and then|but then|because)\\b.*$", "").trim();
        return cleaned;
    }
}
