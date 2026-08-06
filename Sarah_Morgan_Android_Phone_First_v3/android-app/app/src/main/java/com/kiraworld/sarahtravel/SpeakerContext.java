package com.kiraworld.sarahtravel;

import android.content.Context;

import java.time.LocalDate;
import java.time.Year;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracks the person currently speaking on a shared phone.
 *
 * Names, ages, consent, interests, and trip participation are stored in
 * PersonProfileStore so a parent, child, partner, or friend does not inherit
 * the phone owner's identity or memories.
 */
public final class SpeakerContext implements AutoCloseable {
    public static final class Result {
        public final boolean handled;
        public final String reply;
        public final boolean speakerChanged;
        /** True when the user's current message was spoken by the newly active person. */
        public final boolean messageBelongsToActiveSpeaker;

        Result(boolean handled, String reply) {
            this(handled, reply, false, false);
        }

        Result(
                boolean handled,
                String reply,
                boolean speakerChanged,
                boolean messageBelongsToActiveSpeaker) {
            this.handled = handled;
            this.reply = reply == null ? "" : reply;
            this.speakerChanged = speakerChanged;
            this.messageBelongsToActiveSpeaker = messageBelongsToActiveSpeaker;
        }
    }

    private enum Pending {
        NONE,
        AGE,
        MEMORY_CONSENT,
        TRIP_PARTICIPATION
    }

    private static final Pattern CHILD_RELATION_NAME = Pattern.compile(
            "(?i)\\b(?:daughter|son|child|kid|granddaughter|grandson|niece|nephew)\\s+(?:named\\s+)?([A-Za-z][A-Za-z'’-]{1,30})\\b");
    private static final Pattern DIRECT_INTRO = Pattern.compile(
            "(?i)^(?:(?:hi|hello|hey)(?:\\s+sarah)?[,! ]*)?(?:I['’]?m|I am|my name is|this is)\\s+([A-Za-z][A-Za-z'’-]{1,30})(?:[.! ]*)$");
    private static final Pattern AGE_WITH_I_AM = Pattern.compile(
            "(?i)\\b(?:I['’]?m|I am)\\s+(\\d{1,3})\\b");
    private static final Pattern AGE_WITH_SUFFIX = Pattern.compile(
            "(?i)\\b(\\d{1,3})\\s*(?:years? old|yrs? old|yo)\\b");
    private static final Pattern BIRTH_YEAR = Pattern.compile(
            "(?i)\\b(?:born in|birth year is|I was born in)\\s+(19\\d{2}|20\\d{2})\\b");
    private static final Pattern EXPLICIT_CHILD_AGE = Pattern.compile(
            "(?i)\\b(\\d{1,2})[- ]?year[- ]?old\\b");

    private final Context context;
    private final Map<String, String> ownerProfile;
    private final PersonProfileStore people;
    private final String ownerName;
    private Map<String, String> activeProfile;
    private Pending pending = Pending.NONE;
    private String pendingTripDestination = "";

    public SpeakerContext(Map<String, String> ownerProfile) {
        this.context = SarahApplication.appContext();
        this.ownerProfile = new LinkedHashMap<>(ownerProfile);
        this.people = context == null ? null : new PersonProfileStore(context);
        String fallbackOwner = ownerProfile.getOrDefault("name", "the phone owner");
        if (people != null) {
            Map<String, String> owner = people.ensureOwner(ownerProfile);
            ownerName = owner.getOrDefault("name", fallbackOwner);
            activeProfile = people.getActiveProfile();
            if (activeProfile.isEmpty()) activeProfile = owner;
        } else {
            ownerName = fallbackOwner;
            activeProfile = ownerFallback(ownerProfile);
        }
    }

    public Result handle(String message) {
        String raw = message == null ? "" : message.trim();
        if (raw.isEmpty()) return new Result(false, "");
        String lower = raw.toLowerCase(Locale.US);

        if (isReturnToOwner(lower)) {
            boolean changed = !isOwner();
            switchTo(ownerName);
            pending = Pending.NONE;
            pendingTripDestination = "";
            return new Result(
                    true,
                    "Welcome back, " + ownerName + ". I’m using your profile again.",
                    changed,
                    true);
        }

        Result pendingResult = handlePending(raw, lower);
        if (pendingResult.handled) return pendingResult;

        Result handoff = detectHandoff(raw, lower);
        if (handoff.handled) return handoff;

        Result intro = detectSelfIntroduction(raw);
        if (intro.handled) return intro;

        rememberApprovedDetails(raw);
        return new Result(false, "");
    }

    private Result handlePending(String raw, String lower) {
        if (pending == Pending.AGE) {
            int age = parseAgeAnswer(raw);
            if (age < 1 || age > 120) {
                return new Result(true,
                        "Before we continue, how old are you, " + activeName()
                                + "? You can tell me your age or the year you were born. Until I know, I’ll keep everything family-friendly.");
            }
            if (people != null) {
                people.setAge(activeName(), age);
                activeProfile = people.findByName(activeName());
            }
            if (age >= 18 && "unknown".equals(activeProfile.getOrDefault("memory_consent", "unknown"))) {
                pending = Pending.MEMORY_CONSENT;
                return new Result(true,
                        "Thanks, " + activeName() + ". I’ll keep your profile separate from "
                                + ownerName + "’s. Would you like me to remember interests and preferences you share? You can say yes or no.");
            }
            pending = Pending.NONE;
            return continueAfterProfileSetup(
                    "Thanks, " + activeName() + ". I’ll keep suggestions right for your age");
        }

        if (pending == Pending.MEMORY_CONSENT) {
            Boolean answer = yesNo(lower);
            if (answer == null) {
                pending = Pending.NONE;
                return new Result(false, "");
            }
            if (people != null) {
                people.setMemoryConsent(activeName(), answer);
                activeProfile = people.findByName(activeName());
            }
            pending = Pending.NONE;
            String lead = answer
                    ? "Okay. I can remember useful interests and preferences in your own profile"
                    : "Okay. I’ll keep your conversation separate without saving personal preferences";
            return continueAfterProfileSetup(lead);
        }

        if (pending == Pending.TRIP_PARTICIPATION) {
            Boolean answer = yesNo(lower);
            if (answer == null) {
                pending = Pending.NONE;
                pendingTripDestination = "";
                return new Result(false, "");
            }
            String trip = pendingTripDestination;
            if (people != null) people.setTripParticipation(activeName(), trip, answer ? "going" : "not_going");
            pending = Pending.NONE;
            pendingTripDestination = "";
            if (answer) {
                return new Result(true,
                        "Got it. I’ll include you in the " + trip
                                + " planning and keep your age, interests, pace, and needs separate from " + ownerName + "’s.");
            }
            return new Result(true,
                    "Understood. I won’t assume you are part of the " + trip
                            + " trip. We can talk about something completely different.");
        }
        return new Result(false, "");
    }

    private Result detectHandoff(String raw, String lower) {
        boolean cue = lower.contains("handing")
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
        String before = activeName();
        String name = cleanName(relation.group(1));
        int age = parseExplicitChildAge(raw);
        activatePerson(name, "family_child");
        if (age >= 1 && age <= 120 && people != null) {
            people.setAge(name, age);
            activeProfile = people.findByName(name);
        }
        Result result;
        if (!ageKnown()) {
            pending = Pending.AGE;
            result = new Result(true,
                    "Hi, " + activeName() + ". Nice to meet you. How old are you? I ask so movies, games, books, events, and travel ideas are age-appropriate.");
        } else {
            result = continueAfterProfileSetup(
                    "Hi, " + activeName() + ". Nice to see you again");
        }
        return new Result(result.handled, result.reply, !before.equalsIgnoreCase(activeName()), false);
    }

    private Result detectSelfIntroduction(String raw) {
        if (raw.length() > 100) return new Result(false, "");
        Matcher matcher = DIRECT_INTRO.matcher(raw);
        if (!matcher.matches()) return new Result(false, "");
        String originalName = matcher.group(1);
        if (originalName == null || originalName.isEmpty() || !Character.isUpperCase(originalName.charAt(0))) {
            return new Result(false, "");
        }
        String before = activeName();
        String name = cleanName(originalName);
        if (name.equalsIgnoreCase(ownerName)) {
            switchTo(ownerName);
            pending = Pending.NONE;
            return new Result(
                    true,
                    "Hi, " + ownerName + ". I know it’s you again.",
                    !before.equalsIgnoreCase(ownerName),
                    true);
        }

        boolean existed = people != null && !people.findByName(name).isEmpty();
        activatePerson(name, "phone_guest");
        Result result;
        if (!ageKnown()) {
            pending = Pending.AGE;
            result = new Result(true,
                    "Hi, " + activeName() + ". I don’t have a completed profile for you yet. How old are you? I’ll keep everything family-friendly until I know.");
        } else {
            String memory = people == null ? "" : people.memorySummary(activeName(), 3);
            String lead = existed
                    ? "Hi, " + activeName() + ". I found your separate profile"
                    : "Hi, " + activeName() + ". I created a separate profile for you";
            if (!memory.isEmpty()) lead += ". I remember: " + memory;
            result = continueAfterProfileSetup(lead);
        }
        return new Result(result.handled, result.reply, !before.equalsIgnoreCase(activeName()), true);
    }

    private Result continueAfterProfileSetup(String lead) {
        String trip = currentPlannedTrip();
        if (!trip.isEmpty() && !isOwner()) {
            String status = people == null ? "unknown" : people.getTripParticipation(activeName(), trip);
            if ("unknown".equals(status)) {
                pendingTripDestination = trip;
                pending = Pending.TRIP_PARTICIPATION;
                return new Result(true,
                        lead + ". Are you also going to " + trip + " with " + ownerName + "?");
            }
        }
        pending = Pending.NONE;
        return new Result(true, lead + ". What would you like to talk about?");
    }

    private void activatePerson(String name, String relationship) {
        if (people == null) {
            activeProfile = new LinkedHashMap<>();
            activeProfile.put("name", cleanName(name));
            activeProfile.put("age", "unknown");
            activeProfile.put("age_known", "no");
            activeProfile.put("memory_consent", "no");
            activeProfile.put("is_owner", "no");
            return;
        }
        people.createOrGet(name, relationship);
        people.setActiveByName(name);
        activeProfile = people.findByName(name);
    }

    public void switchTo(String name) {
        if (people == null) return;
        Map<String, String> found = people.findByName(name);
        if (found.isEmpty()) return;
        people.setActiveByName(found.get("name"));
        activeProfile = people.findByName(found.get("name"));
        pending = Pending.NONE;
        pendingTripDestination = "";
    }

    public List<Map<String, String>> savedProfiles() {
        return people == null ? List.of(activeProfile) : people.listProfiles();
    }

    public Map<String, String> profileFor(Map<String, String> ignoredOwnerProfile) {
        Map<String, String> result = new LinkedHashMap<>();
        if (isOwner()) result.putAll(ownerProfile);
        result.putAll(activeProfile);
        result.put("owner_name", ownerName);
        result.put("active_speaker", activeName());
        result.put("active_speaker_is_guest", isGuest() ? "yes" : "no");
        result.put("active_speaker_is_owner", isOwner() ? "yes" : "no");
        result.put("active_speaker_age_known", ageKnown() ? "yes" : "no");
        result.put("age_group", ageGroup());
        if (people != null) {
            String memories = people.memorySummary(activeName(), 8);
            if (!memories.isEmpty()) result.put("speaker_memories", memories);
            String trip = currentPlannedTrip();
            if (!trip.isEmpty()) {
                String participation = people.getTripParticipation(activeName(), trip);
                result.put("current_shared_trip", trip);
                result.put("current_shared_trip_participation", participation);
            }
        }
        if (!isOwner() && !"yes".equals(activeProfile.getOrDefault("memory_consent", "no"))) {
            result.put("memory_consent", "no");
        }
        return result;
    }

    public String activeName() {
        return activeProfile.getOrDefault("name", ownerName);
    }

    public boolean isGuest() {
        return !isOwner();
    }

    public boolean isOwner() {
        return "yes".equals(activeProfile.getOrDefault("is_owner", "no"))
                || activeName().equalsIgnoreCase(ownerName);
    }

    public boolean ageKnown() {
        return "yes".equals(activeProfile.getOrDefault("age_known", "no"));
    }

    public String ageGroup() {
        if (!ageKnown()) return "unknown_use_child_safe_mode";
        int age = parseInt(activeProfile.get("age"), 0);
        if (age < 13) return "child";
        if (age < 18) return "teen";
        return "adult";
    }

    private void rememberApprovedDetails(String raw) {
        if (people == null || !"yes".equals(activeProfile.getOrDefault("memory_consent", "no"))) return;
        for (MemoryExtractor.Candidate candidate : MemoryExtractor.extract(raw)) {
            if (candidate.category.equals("profile") && !isOwner()) continue;
            people.addMemory(activeName(), candidate.category, candidate.summary, raw);
        }
    }

    private String currentPlannedTrip() {
        if (context == null) return "";
        SarahDatabase db = new SarahDatabase(context);
        try {
            for (Map<String, String> trip : db.listTrips(20)) {
                String status = trip.getOrDefault("status", "").toLowerCase(Locale.US);
                String destination = trip.getOrDefault("destination", "").trim();
                if (!destination.isEmpty() && (status.contains("planned")
                        || status.contains("upcoming") || status.contains("confirmed"))) {
                    return destination;
                }
            }
        } finally {
            db.close();
        }

        EventTripStore events = new EventTripStore(context);
        try {
            for (Map<String, String> event : events.listActiveEventTrips(20)) {
                String destination = event.getOrDefault("destination", "").trim();
                String start = event.getOrDefault("start_date", "").trim();
                if (destination.isEmpty()) continue;
                if (start.isEmpty()) return destination;
                try {
                    if (!LocalDate.parse(start).isBefore(LocalDate.now())) return destination;
                } catch (Exception ignored) {
                    return destination;
                }
            }
        } finally {
            events.close();
        }
        return "";
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

    private static Boolean yesNo(String lower) {
        String clean = lower.trim().replaceAll("[.!?]+$", "");
        if (clean.matches("^(yes|yeah|yep|sure|okay|ok|please do|that is fine|that's fine|i am|i'm going|i will)$")) return true;
        if (clean.matches("^(no|nope|do not|don't|i am not|i'm not|not me|please don't|please do not)$")) return false;
        return null;
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

    private static Map<String, String> ownerFallback(Map<String, String> owner) {
        Map<String, String> result = new LinkedHashMap<>(owner);
        result.put("is_owner", "yes");
        result.put("age_known", "yes");
        return result;
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

    @Override
    public void close() {
        if (people != null) people.close();
    }
}
