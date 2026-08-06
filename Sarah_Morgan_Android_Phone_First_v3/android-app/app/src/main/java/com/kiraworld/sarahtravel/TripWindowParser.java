package com.kiraworld.sarahtravel;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;

/** Recognizes ordinary relative travel timing without forcing a date form. */
public final class TripWindowParser {
    public static final class TripWindow {
        public final String destination;
        public final LocalDate startDate;
        public final LocalDate endDate;
        public final String label;

        TripWindow(String destination, LocalDate startDate, LocalDate endDate, String label) {
            this.destination = destination == null ? "" : destination.trim();
            this.startDate = startDate;
            this.endDate = endDate;
            this.label = label == null ? "" : label.trim();
        }

        public boolean found() {
            return !destination.isEmpty() && startDate != null && endDate != null;
        }

        public int days() {
            if (!found()) return 0;
            return (int) java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
        }

        public String encodedDates() {
            return found() ? startDate + "|" + endDate + "|" + label : "";
        }
    }

    private TripWindowParser() { }

    public static TripWindow parse(String message) {
        return parse(message, LocalDate.now());
    }

    public static TripWindow parse(String message, LocalDate today) {
        String safe = message == null ? "" : message.trim();
        String lower = safe.toLowerCase(Locale.US);
        if (!containsAny(lower,
                "going to", "visiting", "traveling to", "travelling to",
                "heading to", "spending time in", "trip to")) return empty();

        List<String> destinations = DestinationParser.extractDestinations(safe);
        if (destinations.isEmpty()) return empty();
        String destination = destinations.get(destinations.size() - 1);
        LocalDate start;
        LocalDate end;
        String label;

        if (lower.contains("next week")) {
            start = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            end = start.plusDays(6);
            label = "next week";
        } else if (lower.contains("this weekend")) {
            start = today.getDayOfWeek() == DayOfWeek.SATURDAY
                    ? today : today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY));
            end = start.plusDays(1);
            label = "this weekend";
        } else if (lower.contains("next weekend")) {
            LocalDate thisSaturday = today.getDayOfWeek() == DayOfWeek.SATURDAY
                    ? today : today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY));
            start = thisSaturday.plusWeeks(1);
            end = start.plusDays(1);
            label = "next weekend";
        } else if (lower.contains("next month")) {
            start = today.plusMonths(1).withDayOfMonth(1);
            end = start.with(TemporalAdjusters.lastDayOfMonth());
            label = "next month";
        } else if (lower.contains("tomorrow")) {
            start = today.plusDays(1);
            end = start;
            label = "tomorrow";
        } else {
            return empty();
        }
        return new TripWindow(destination, start, end, label);
    }

    private static TripWindow empty() {
        return new TripWindow("", null, null, "");
    }

    private static boolean containsAny(String value, String... phrases) {
        for (String phrase : phrases) if (value.contains(phrase)) return true;
        return false;
    }
}
