package com.kiraworld.sarahtravel;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure policy separating a Gmail observation, an owner-approved calendar item,
 * and an explicitly scheduled local reminder.
 */
public final class EmailCalendarPolicy {
    public static final String EMAIL_PENDING = "EMAIL_CANDIDATE_PENDING_OWNER_DECISION";
    public static final String EMAIL_ACCEPTED = "EMAIL_CANDIDATE_ACCEPTED_BY_OWNER";
    public static final String EMAIL_DISMISSED = "EMAIL_CANDIDATE_DISMISSED_BY_OWNER";
    public static final String CALENDAR_NOT_SAVED = "CALENDAR_ITEM_NOT_SAVED";
    public static final String CALENDAR_SAVED = "OWNER_APPROVED_CALENDAR_ITEM";
    public static final String CALENDAR_REMOVED = "CALENDAR_ITEM_REMOVED_BY_OWNER";
    public static final String REMINDER_NOT_SCHEDULED = "REMINDER_NOT_SCHEDULED";
    public static final String REMINDER_SCHEDULED = "OWNER_SCHEDULED_LOCAL_REMINDER";
    public static final String REMINDER_DELIVERED = "LOCAL_REMINDER_DELIVERED";
    public static final String REMINDER_BLOCKED = "LOCAL_REMINDER_BLOCKED_NOTIFICATION_PERMISSION";
    public static final String TIME_SOURCE_EMAIL = "EXACT_TIME_FROM_GMAIL_SOURCE_RECEIPT";
    public static final String TIME_SOURCE_OWNER = "EXACT_TIME_ENTERED_BY_OWNER";

    private static final Pattern LABELED_RFC3339 = Pattern.compile(
            "(?i)\\b(depart(?:ure|s|ing)?|leav(?:e|es|ing)|arriv(?:al|e|es|ing)|"
                    + "start(?:s|ing)?|begin(?:s|ning)?|end(?:s|ing)?)\\b"
                    + "[^\\r\\n]{0,48}?"
                    + "(20\\d{2}-\\d{2}-\\d{2}T\\d{2}:\\d{2}"
                    + "(?::\\d{2}(?:\\.\\d{1,9})?)?(?:Z|[+-]\\d{2}:\\d{2}))");

    private EmailCalendarPolicy() { }

    public static String candidateKind(String subject, String snippet) {
        String text = (clean(subject, 300) + " " + clean(snippet, 800)).toLowerCase(Locale.US);
        if (containsAny(text, "flight", "boarding", "airline", "departure", "arrival")) {
            return "flight";
        }
        if (containsAny(text, "amtrak", "train", "rail", "platform")) return "train";
        if (containsAny(text, "bus", "coach", "greyhound")) return "bus";
        if (containsAny(text, "conference", "convention", "festival", "concert", "event", "ticket")) {
            return "event";
        }
        if (containsAny(text, "hotel", "room", "check-in", "reservation")) return "lodging";
        return "travel";
    }

    /**
     * Accept only an explicitly labelled RFC3339 timestamp with a real UTC
     * offset. Ambiguous dates, the Gmail sent-date header, and unlabeled
     * numbers never become calendar time.
     */
    public static ExactTimes exactTimesFromSourceText(String subject, String snippet) {
        String text = clean(subject, 300) + "\n" + clean(snippet, 2000);
        String departureOrStart = "";
        String arrivalOrEnd = "";
        Matcher matcher = LABELED_RFC3339.matcher(text);
        while (matcher.find()) {
            String label = matcher.group(1).toLowerCase(Locale.US);
            String normalized = normalizedInstant(matcher.group(2));
            if (normalized.isEmpty()) continue;
            if (label.startsWith("arriv") || label.startsWith("end")) {
                if (arrivalOrEnd.isEmpty()) arrivalOrEnd = normalized;
            } else if (departureOrStart.isEmpty()) {
                departureOrStart = normalized;
            }
        }
        if (!departureOrStart.isEmpty() && !arrivalOrEnd.isEmpty()
                && Instant.parse(arrivalOrEnd).isBefore(Instant.parse(departureOrStart))) {
            // Contradictory source text is evidence, not permission to invent a correction.
            return new ExactTimes("", "", false);
        }
        return new ExactTimes(departureOrStart, arrivalOrEnd,
                !departureOrStart.isEmpty() || !arrivalOrEnd.isEmpty());
    }

    public static String normalizedInstant(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return "";
        try {
            return OffsetDateTime.parse(text).toInstant().toString();
        } catch (DateTimeParseException ignored) {
            try { return Instant.parse(text).toString(); }
            catch (DateTimeParseException alsoIgnored) { return ""; }
        }
    }

    public static boolean mayApproveCalendarItem(
            String emailCandidateState,
            boolean explicitOwnerApproval) {
        return explicitOwnerApproval && EMAIL_PENDING.equals(emailCandidateState);
    }

    public static boolean mayScheduleReminder(
            String calendarState,
            boolean explicitOwnerRequest,
            String triggerInstant,
            long nowEpochMillis) {
        String normalized = normalizedInstant(triggerInstant);
        return CALENDAR_SAVED.equals(calendarState)
                && explicitOwnerRequest
                && !normalized.isEmpty()
                && Instant.parse(normalized).toEpochMilli() > nowEpochMillis;
    }

    public static long reminderTrigger(long anchorEpochMillis, long leadMillis, long nowEpochMillis) {
        if (anchorEpochMillis <= 0L || leadMillis < 0L) return -1L;
        long trigger = anchorEpochMillis - leadMillis;
        return trigger > nowEpochMillis ? trigger : -1L;
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }

    private static String clean(String value, int max) {
        String text = value == null ? "" : value
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
                .replaceAll("[ \\t]+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    public static final class ExactTimes {
        public final String startInstant;
        public final String endInstant;
        public final boolean sourceSupported;

        ExactTimes(String startInstant, String endInstant, boolean sourceSupported) {
            this.startInstant = startInstant;
            this.endInstant = endInstant;
            this.sourceSupported = sourceSupported;
        }
    }
}
