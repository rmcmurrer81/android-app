package com.kiraworld.sarahtravel;

public final class EmailCalendarPolicyTest {
    public static void main(String[] args) {
        require("flight".equals(EmailCalendarPolicy.candidateKind(
                "Your flight itinerary", "Boarding confirmation")), "flight classification");
        EmailCalendarPolicy.ExactTimes exact = EmailCalendarPolicy.exactTimesFromSourceText(
                "Flight departure 2026-08-12T15:30:00-04:00",
                "Arrival 2026-08-12T21:10:00-04:00");
        require(exact.sourceSupported, "explicit source times supported");
        require("2026-08-12T19:30:00Z".equals(exact.startInstant), "departure normalized");
        require("2026-08-13T01:10:00Z".equals(exact.endInstant), "arrival normalized");

        EmailCalendarPolicy.ExactTimes ambiguous = EmailCalendarPolicy.exactTimesFromSourceText(
                "Flight August 12 at 3:30", "Your ticket is ready");
        require(!ambiguous.sourceSupported && ambiguous.startInstant.isEmpty(),
                "ambiguous time must remain unknown");
        EmailCalendarPolicy.ExactTimes sentDate = EmailCalendarPolicy.exactTimesFromSourceText(
                "Event details", "Message sent 2026-08-12T15:30:00-04:00");
        require(!sentDate.sourceSupported, "unlabelled/message date not event time");

        require(!EmailCalendarPolicy.mayApproveCalendarItem(
                EmailCalendarPolicy.EMAIL_PENDING, false), "no automatic save");
        require(EmailCalendarPolicy.mayApproveCalendarItem(
                EmailCalendarPolicy.EMAIL_PENDING, true), "explicit save allowed");
        require(!EmailCalendarPolicy.mayScheduleReminder(
                EmailCalendarPolicy.CALENDAR_NOT_SAVED, true,
                "2030-01-01T10:00:00Z", 1L), "candidate is not reminder authority");
        require(!EmailCalendarPolicy.mayScheduleReminder(
                EmailCalendarPolicy.CALENDAR_SAVED, false,
                "2030-01-01T10:00:00Z", 1L), "reminder needs separate owner action");
        require(EmailCalendarPolicy.mayScheduleReminder(
                EmailCalendarPolicy.CALENDAR_SAVED, true,
                "2030-01-01T10:00:00Z", 1L), "explicit saved-item reminder allowed");
        require(EmailCalendarPolicy.reminderTrigger(10_000L, 1_000L, 1L) == 9_000L,
                "lead time arithmetic");
        require(EmailCalendarPolicy.reminderTrigger(10_000L, 10_000L, 1L) == -1L,
                "past trigger rejected");
        System.out.println("EMAIL_CALENDAR_POLICY_PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
