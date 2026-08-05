package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MemoryExtractor {
    public static final class Candidate {
        public final String category;
        public final String summary;
        public Candidate(String category, String summary) {
            this.category = category;
            this.summary = summary;
        }
    }

    private static final Pattern WANTS_TO_VISIT = Pattern.compile("\\b(?:I want to (?:go to|visit)|I'd like to (?:go to|visit)|I would like to (?:go to|visit)|dream trip is)\\s+([^.!?]{2,80})", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAST_TRIP = Pattern.compile("\\b(?:I went to|I have been to|I've been to|we went to)\\s+([^.!?]{2,80})", Pattern.CASE_INSENSITIVE);
    private static final Pattern FAVORITE = Pattern.compile("\\bmy favorite ([a-zA-Z ]{2,30}) is ([^.!?]{2,80})", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISLIKE = Pattern.compile("\\bI (?:do not|don't|hate|dislike)\\s+([^.!?]{2,80})", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORRY = Pattern.compile("\\bI(?:'m| am) (?:worried|nervous|anxious|scared) about\\s+([^.!?]{2,100})", Pattern.CASE_INSENSITIVE);
    private static final Pattern FROM = Pattern.compile("\\bI(?:'m| am) from\\s+([^.!?]{2,80})", Pattern.CASE_INSENSITIVE);

    private MemoryExtractor() { }

    public static List<Candidate> extract(String text) {
        List<Candidate> out = new ArrayList<>();
        add(out, WANTS_TO_VISIT, text, "wish_list", "Wants to visit ");
        add(out, PAST_TRIP, text, "past_trip", "Has traveled to ");
        addFavorite(out, text);
        add(out, DISLIKE, text, "preference", "Dislikes or avoids ");
        add(out, WORRY, text, "travel_worry", "Travel worry: ");
        add(out, FROM, text, "profile", "Is from ");
        String lower = text.toLowerCase(Locale.US);
        if (lower.contains("never flown") || lower.contains("never been on a plane") || lower.contains("first flight")) {
            out.add(new Candidate("travel_experience", "Flying is new or this may be a first flight"));
        }
        return out;
    }

    private static void add(List<Candidate> out, Pattern pattern, String text, String category, String prefix) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            String value = clean(m.group(1));
            if (!value.isEmpty()) out.add(new Candidate(category, prefix + value));
        }
    }

    private static void addFavorite(List<Candidate> out, String text) {
        Matcher m = FAVORITE.matcher(text);
        if (m.find()) {
            String subject = clean(m.group(1));
            String value = clean(m.group(2));
            if (!subject.isEmpty() && !value.isEmpty()) out.add(new Candidate("preference", "Favorite " + subject + ": " + value));
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
