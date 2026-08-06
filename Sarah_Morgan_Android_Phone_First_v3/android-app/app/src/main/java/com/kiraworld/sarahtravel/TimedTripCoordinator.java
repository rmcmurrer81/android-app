package com.kiraworld.sarahtravel;

import android.content.Context;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Applies a timed city statement after it has been parsed. */
public final class TimedTripCoordinator {
    private TimedTripCoordinator() { }

    public static String handle(
            Context context,
            String message,
            Map<String, String> profile,
            List<Map<String, String>> memories) {
        if (context == null || GenericEventReference.looksLikeEvent(message)) return null;
        TripWindowParser.TripWindow trip = TripWindowParser.parse(message);
        if (!trip.found()) return null;

        boolean owner = "yes".equals(profile.getOrDefault("active_speaker_is_owner", "yes"));
        if (owner) {
            SarahDatabase db = new SarahDatabase(context.getApplicationContext());
            try {
                if (!alreadySaved(db.listTrips(50), trip)) {
                    db.addTrip(
                            trip.label + " trip to " + trip.destination,
                            trip.destination,
                            "planned",
                            "Planned dates: " + trip.startDate + " through " + trip.endDate);
                }
                db.queueKnowledgePack(trip.destination);
            } finally {
                db.close();
            }
        } else {
            PersonProfileStore people = new PersonProfileStore(context.getApplicationContext());
            try {
                String name = profile.getOrDefault("name", profile.getOrDefault("active_speaker", "Guest"));
                people.setTripParticipation(name, trip.destination, "going");
                people.addMemory(
                        name,
                        "planned_trip",
                        "Plans to visit " + trip.destination + " " + trip.label,
                        message);
            } finally {
                people.close();
            }
        }
        return CityVisitPlanner.answer(trip, profile, memories);
    }

    private static boolean alreadySaved(
            List<Map<String, String>> trips,
            TripWindowParser.TripWindow candidate) {
        for (Map<String, String> trip : trips) {
            if (!candidate.destination.equalsIgnoreCase(trip.getOrDefault("destination", ""))) continue;
            String notes = trip.getOrDefault("notes", "").toLowerCase(Locale.US);
            if (notes.contains(candidate.startDate.toString().toLowerCase(Locale.US))
                    && notes.contains(candidate.endDate.toString().toLowerCase(Locale.US))) return true;
        }
        return false;
    }
}
