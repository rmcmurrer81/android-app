package com.kiraworld.sarahtravel;

import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpeakerContext {
    public static final class Result {
        public final boolean handled;
        public final String reply;

        Result(boolean handled, String reply) {
            this.handled = handled;
            this.reply = reply;
        }
    }

    private static final Pattern CHILD_RELATION_NAME = Pattern.compile(
            "(?i)\\b(?:daughter|son|child|kid|granddaughter|grandson|niece|nephew)\\s+(?:named\\s+)?([A-Za-z][A-Za-z'’-]{1,30})\\b");
    private static final Pattern DIRECT_INTRO = Pattern.compile(
            "(?i)^(?:(?:hi|hello|hey)(?:\\s+sarah)?[,! ]*)?(?:I['’]?m|I am|my name is)\\s+([A-Za-z][A-Za-z'’-]{1,30})(?:[.! ]*)$");
    private static final Pattern AGE_WITH_I_AM = Pattern.compile(
            "(?i)\\b(?:I['’]?m|I am)\\s+(\\d{1,3})\\b");
    private static final Pattern AGE_WITH_SUFFIX = Pattern.compile(
            "(?i)\\b(\\d{1,3})\\s*(?:years? old|yrs? old|yo)\\b");
    private static final Pattern BIRTH_YEAR = Pattern.compile(
            "(?i)\\b(?:born in|birth year is|I was born in)\\s+(19\\d{2}|20\\d{2})\\b");
    private static final Pattern EXPLICIT_CHILD_AGE = Pattern.compile(
            "(?i)\\b(\\d{1,2})[- ]?year[- ]?old\\b");

    private final String ownerName;
    private final int ownerAge;
    private String activeName;
    private int activeAge;
    private boolean activeAgeKnown;
    private boolean guest;
    private boolean conservativeUnknownAge;

    public SpeakerContext(Map<String, String> ownerProfile) {
        ownerName = ownerProfile.getOrDefault("name", "the phone owner");
        ownerAge = parseInt(ownerProfile.get("age"), 18);
        resetToOwner();
    }

    public Result handle(String message) {
        String raw = message == null ? "" : message.trim();
        if (raw.isEmpty()) return new Result(false, "");
        String lower = raw.toLowerCase(Locale.US);

        if (isReturnToOwner(lower)) {
            resetToOwner();
            return new Result(true, "Welcome back, " + ownerName + ". I’m talking with you again.");
        }

        Result handoff = detectHandoff(raw, lower);
        if (handoff.handled) return handoff;

        Result intro = detectSelfIntroduction(raw);
        if (intro.handled) return intro;

        if (guest && !activeAgeKnown) {
            int age = parseAgeAnswer(raw);
            if (age >= 1 && age <= 120) {
                activeAge = age;
                activeAgeKnown = true;
                conservativeUnknownAge = false;
                return new Result(true,
                        "Thanks, " + activeName + ". I’ll keep games, books, movies, and travel ideas right for your age. What would you like to talk about?");
            }
            return new Result(true,
                    "Before we keep going, how old are you, " + activeName + "? You can tell me your age or the year you were born. Until I know, I’ll keep everything family-friendly.");
        }

        return new Result(false, "");
    }

    private Result detectHandoff(String raw, String lower) {
        boolean cue = lower.contains("handing")
                || lower.contains("handling you to")
                || lower.contains("passing the phone")
                || lower.contains("giving the phone")
                || lower.contains("give the phone")
                || lower.contains("here's my")
                || lower.contains("here is my")
                || lower.contains("talk to my")
                || lower.contains("say hi to my")
                || lower.contains("meet my");
        if (!cue) return new Result(false, "");

        Matcher relation = CHILD_RELATION_NAME.matcher(raw);
        if (!relation.find()) return new Result(false, "");
        String name = cleanName(relation.group(1));
        int age = parseExplicitChildAge(raw);
        setGuest(name, age, true);
        if (activeAgeKnown) {
            return new Result(true,
                    "Hi, " + activeName + ". Nice to meet you. I know you’re " + activeAge
                            + ", so I’ll keep our games, books, movies, and travel ideas right for your age. What would you like to talk about?");
        }
        return new Result(true,
                "Hi, " + activeName + ". Nice to meet you. Before I suggest movies, games, or places, how old are you? Until I know, I’ll keep everything family-friendly.");
    }

    private Result detectSelfIntroduction(String raw) {
        if (raw.length() > 100) return new Result(false, "");
        Matcher matcher = DIRECT_INTRO.matcher(raw);
        if (!matcher.matches()) return new Result(false, "");
        String originalName = matcher.group(1);
        if (originalName == null || originalName.isEmpty() || !Character.isUpperCase(originalName.charAt(0))) {
            return new Result(false, "");
        }
        String name = cleanName(originalName);
        if (name.equalsIgnoreCase(ownerName)) {
            resetToOwner();
            return new Result(true, "Hi, " + ownerName + ". I know it’s you again.");
        }
        setGuest(name, 0, false);
        return new Result(true,
                "Hi, " + activeName + ". Nice to meet you. How old are you? I ask so I can choose safe, age-appropriate games, books, movies, and travel ideas.");
    }

    private boolean isReturnToOwner(String lower) {
        String owner = ownerName.toLowerCase(Locale.US);
        return lower.equals("i'm back")
                || lower.equals("i am back")
                || lower.contains("handing the phone back")
                || lower.contains("passing the phone back")
                || lower.contains("phone back to " + owner)
                || lower.contains("this is " + owner + " again")
                || lower.contains("it's " + owner + " again")
                || lower.contains("it is " + owner + " again");
    }

    private void setGuest(String name, int age, boolean knownChildRelation) {
        activeName = name;
        guest = !name.equalsIgnoreCase(ownerName);
        activeAge = age;
        activeAgeKnown = age >= 1 && age <= 120;
        conservativeUnknownAge = guest && !activeAgeKnown;
        if (knownChildRelation && !activeAgeKnown) conservativeUnknownAge = true;
    }

    public void resetToOwner() {
        activeName = ownerName;
        activeAge = ownerAge;
        activeAgeKnown = true;
        guest = false;
        conservativeUnknownAge = false;
    }

    public Map<String, String> profileFor(Map<String, String> ownerProfile) {
        Map<String, String> result = new LinkedHashMap<>(ownerProfile);
        result.put("owner_name", ownerName);
        result.put("active_speaker", activeName);
        result.put("name", activeName);
        result.put("active_speaker_is_guest", guest ? "yes" : "no");
        result.put("active_speaker_age_known", activeAgeKnown ? "yes" : "no");
        result.put("age", activeAgeKnown ? String.valueOf(activeAge) : "unknown");
        result.put("age_group", ageGroup());
        if (guest) result.put("memory_consent", "no");
        return result;
    }

    public String activeName() { return activeName; }
    public boolean isGuest() { return guest; }
    public boolean ageKnown() { return activeAgeKnown; }

    public String ageGroup() {
        if (!activeAgeKnown || conservativeUnknownAge) return "unknown_use_child_safe_mode";
        if (activeAge < 13) return "child";
        if (activeAge < 18) return "teen";
        return "adult";
    }

    private static int parseAgeAnswer(String raw) {
        String trimmed = raw.trim();
        Matcher birth = BIRTH_YEAR.matcher(trimmed);
        if (birth.find()) {
            int year = parseInt(birth.group(1), 0);
            int age = Year.now().getValue() - year;
            return age >= 1 && age <= 120 ? age : 0;
        }
        if (trimmed.matches("\\d{4}")) {
            int year = parseInt(trimmed, 0);
            int age = Year.now().getValue() - year;
            return age >= 1 && age <= 120 ? age : 0;
        }
        if (trimmed.matches("\\d{1,3}")) return parseInt(trimmed, 0);
        Matcher spokenAge = AGE_WITH_I_AM.matcher(trimmed);
        if (spokenAge.find()) return parseInt(spokenAge.group(1), 0);
        Matcher suffixedAge = AGE_WITH_SUFFIX.matcher(trimmed);
        if (suffixedAge.find()) return parseInt(suffixedAge.group(1), 0);
        return 0;
    }

    private static int parseExplicitChildAge(String raw) {
        Matcher matcher = EXPLICIT_CHILD_AGE.matcher(raw);
        return matcher.find() ? parseInt(matcher.group(1), 0) : 0;
    }

    private static String cleanName(String value) {
        String name = value == null ? "guest" : value.trim();
        if (name.isEmpty()) return "guest";
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value == null ? "" : value.trim()); }
        catch (Exception ignored) { return fallback; }
    }
}
