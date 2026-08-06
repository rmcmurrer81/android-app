package com.kiraworld.sarahtravel;

import android.content.Context;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small read-only snapshot used by Sarah's travel commerce and experience
 * tools. It never creates a booking or turns a suggestion into a confirmed
 * trip. The current active profile remains the privacy boundary.
 */
public final class TravelContextSnapshot {
    public final String personId;
    public final String personName;
    public final String origin;
    public final String destination;
    public final String eventName;
    public final String startDate;
    public final String endDate;
    public final int travelers;
    public final String source;

    private TravelContextSnapshot(
            String personId,
            String personName,
            String origin,
            String destination,
            String eventName,
            String startDate,
            String endDate,
            int travelers,
            String source) {
        this.personId = clean(personId);
        this.personName = clean(personName);
        this.origin = clean(origin);
        this.destination = clean(destination);
        this.eventName = clean(eventName);
        this.startDate = clean(startDate);
        this.endDate = clean(endDate);
        this.travelers = Math.max(1, travelers);
        this.source = clean(source);
    }

    public static TravelContextSnapshot load(Context context) {
        Context app = context.getApplicationContext();
        PersonProfileStore people = new PersonProfileStore(app);
        Map<String, String> person;
        try {
            person = people.getActiveProfile();
        } finally {
            people.close();
        }
        String personId = person.getOrDefault("person_id", "1");
        String personName = person.getOrDefault("name", "Traveler");
        String origin = person.getOrDefault("hometown", "");
        boolean owner = "yes".equals(person.getOrDefault("is_owner", "no"));

        SarahDatabase db = new SarahDatabase(app);
        if (origin.isEmpty() && owner) origin = db.getProfile().getOrDefault("hometown", "");

        String destination = "";
        String eventName = "";
        String start = "";
        String end = "";
        String source = "";

        EventTripStore events = new EventTripStore(app);
        PersonProfileStore participation = new PersonProfileStore(app);
        try {
            for (Map<String, String> event : events.listActiveEventTrips(50)) {
                String candidate = clean(event.get("destination"));
                if (candidate.isEmpty()) continue;
                if (!owner && !"going".equals(
                        participation.getTripParticipation(personName, candidate))) continue;
                String candidateStart = clean(event.get("start_date"));
                if (dateHasPassed(candidateStart, clean(event.get("end_date")))) continue;
                destination = candidate;
                eventName = clean(event.get("event_name"));
                start = candidateStart;
                end = clean(event.get("end_date"));
                source = "event";
                break;
            }
        } finally {
            events.close();
        }

        try {
            if (destination.isEmpty()) {
                for (Map<String, String> trip : db.listTrips(50)) {
                    String status = clean(trip.get("status")).toLowerCase(Locale.US);
                    String candidate = clean(trip.get("destination"));
                    if (candidate.isEmpty()) continue;
                    if (!owner && !"going".equals(
                            participation.getTripParticipation(personName, candidate))) continue;
                    if (status.contains("planned") || status.contains("current")
                            || status.contains("upcoming") || status.contains("confirmed")) {
                        destination = candidate;
                        source = "trip";
                        TripWindowParser.Window window = TripWindowParser.parse(
                                clean(trip.get("notes")), LocalDate.now());
                        if (window.found) {
                            start = window.start.toString();
                            end = window.end.toString();
                        }
                        break;
                    }
                }
            }
            if (destination.isEmpty() && owner) {
                List<Map<String, String>> wishes = db.listWishes(20);
                if (!wishes.isEmpty()) {
                    destination = clean(wishes.get(0).get("destination"));
                    source = destination.isEmpty() ? "" : "wish";
                }
            }
            if (destination.isEmpty()) {
                List<Map<String, String>> history = db.recentMessagesForSpeaker(personName, 30);
                List<String> places = DestinationParser.extractFromHistory(history, 8);
                if (!places.isEmpty()) {
                    destination = places.get(places.size() - 1);
                    source = "conversation";
                }
            }
        } finally {
            db.close();
            participation.close();
        }

        return new TravelContextSnapshot(
                personId,
                personName,
                origin,
                destination,
                eventName,
                start,
                end,
                1,
                source);
    }

    public TravelContextSnapshot withSearch(
            String destination,
            String startDate,
            String endDate,
            int travelers) {
        return new TravelContextSnapshot(
                personId,
                personName,
                origin,
                clean(destination).isEmpty() ? this.destination : destination,
                eventName,
                startDate,
                endDate,
                travelers,
                "manual_search");
    }

    public boolean hasDestination() {
        return !destination.isEmpty();
    }

    public boolean hasDates() {
        return !startDate.isEmpty() && !endDate.isEmpty();
    }

    public String title() {
        if (!eventName.isEmpty()) return eventName + " • " + destination;
        if (!destination.isEmpty()) return destination;
        return "No active trip yet";
    }

    public String dateLabel() {
        if (startDate.isEmpty()) return "Dates not set";
        if (endDate.isEmpty() || startDate.equals(endDate)) return startDate;
        return startDate + " to " + endDate;
    }

    private static boolean dateHasPassed(String start, String end) {
        try {
            String value = clean(end).isEmpty() ? clean(start) : clean(end);
            return !value.isEmpty() && LocalDate.parse(value).isBefore(LocalDate.now());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
