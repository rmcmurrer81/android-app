package com.kiraworld.sarahtravel;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java identity and emotional-intent parser used before profile creation. */
public final class IdentityIntent {
    private static final Pattern CORRECTED_NAME = Pattern.compile(
            "(?i)^(?:(?:no|actually|sorry|wait)[,! ]+)?(?:I['’]?m|I am|this is|my name is)\\s+([A-Za-z][A-Za-z'’-]{1,30})(?:\\b.*)?$");
    private static final Set<String> STATES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "tired", "hungry", "scared", "worried", "nervous", "fine", "good",
            "great", "okay", "ok", "sad", "happy", "sick", "cold", "hot",
            "bored", "lost", "confused", "ready", "here", "back", "going",
            "thinking", "planning", "trying", "working", "watching", "looking",
            "visiting", "traveling", "travelling", "stressed", "stressing",
            "stress", "anxious", "afraid", "panicking", "panicked", "overwhelmed",
            "upset", "uncomfortable", "shaking", "terrified", "uneasy")));

    private IdentityIntent() { }

    public static String correctedName(String raw) {
        Matcher matcher = CORRECTED_NAME.matcher(raw == null ? "" : raw.trim());
        if (!matcher.matches()) return "";
        String candidate = matcher.group(1);
        return looksLikeStateNotName(candidate) ? "" : candidate;
    }

    public static boolean hasCorrectionCue(String raw) {
        String lower = lower(raw);
        return lower.startsWith("no ") || lower.startsWith("no,")
                || lower.startsWith("actually ") || lower.startsWith("sorry ")
                || lower.startsWith("wait ") || lower.contains("you have the wrong name")
                || lower.contains("that is not my name") || lower.contains("that's not my name");
    }

    public static boolean looksLikeStateNotName(String value) {
        String lower = lower(value).replaceAll("[^a-z'-]", "");
        return STATES.contains(lower);
    }

    public static boolean isStressOrFear(String raw) {
        String lower = lower(raw);
        return lower.matches(".*\\b(stress|stressed|stressing|anxious|anxiety|afraid|scared|nervous|panic|panicking|panicked|overwhelmed|terrified|uneasy|freaking out|uncomfortable)\\b.*")
                || lower.contains("my heart is racing")
                || lower.contains("this is too fast")
                || lower.contains("i need help calming down")
                || lower.contains("help me calm down");
    }

    public static String transport(String raw) {
        String lower = lower(raw);
        if (lower.matches(".*\\b(plane|airplane|flight|takeoff|taking off|landing|turbulence|airport)\\b.*")) return "plane";
        if (lower.matches(".*\\b(train|rail|subway|metro|amtrak)\\b.*")) return "train";
        if (lower.matches(".*\\b(bus|coach)\\b.*")) return "bus";
        if (lower.matches(".*\\b(ferry|boat|ship|cruise)\\b.*")) return "boat";
        if (lower.matches(".*\\b(car|driving|drive|rideshare|uber|lyft|taxi)\\b.*")) return "car";
        return "general";
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US).trim();
    }
}
