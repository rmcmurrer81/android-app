package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;

import java.util.List;
import java.util.Map;

/** Review official event details, nearby places, routes and monitoring evidence. */
public final class EventTripCenterActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        LinearLayout root = TravelUi.page(this);
        root.addView(TravelUi.hero(
                this,
                "Event trip center",
                "Conventions, conferences and festivals",
                "Sarah keeps the event separate from the city, preserves official-source evidence, and carries the event through short follow-up questions."));

        EventTripStore store = new EventTripStore(this);
        List<Map<String, String>> events;
        try {
            events = store.listActiveEventTrips(100);
        } finally {
            store.close();
        }

        if (events.isEmpty()) {
            LinearLayout empty = TravelUi.card(this, TravelUi.SKY);
            empty.addView(TravelUi.cardTitle(this, "🎟️", "No event trip saved yet"));
            empty.addView(TravelUi.body(this,
                    "Tell Sarah you are thinking about attending a real public event. She should recognize it as an event, search for a likely official page, verify the city and dates, and avoid saving the event name as though it were a destination."));
            root.addView(empty);
            return;
        }

        for (Map<String, String> event : events) {
            String name = event.getOrDefault("event_name", "Event");
            String destination = event.getOrDefault("destination", "");
            String venue = event.getOrDefault("venue", "");
            String start = event.getOrDefault("start_date", "");
            String end = event.getOrDefault("end_date", "");
            String official = event.getOrDefault("official_url", "");
            String detail = "Status: " + event.getOrDefault("monitor_status", "queued")
                    + (venue.isEmpty() ? "" : "\nVenue: " + venue)
                    + (start.isEmpty() ? "\nDates: not verified yet" : "\nDates: " + start + (end.isEmpty() || start.equals(end) ? "" : " to " + end))
                    + text("Latest details", event.getOrDefault("updates_summary", ""))
                    + text("Transportation", event.getOrDefault("transport_notes", ""))
                    + text("Nearby food", event.getOrDefault("nearby_food", ""))
                    + text("Nearby places", event.getOrDefault("nearby_places", ""))
                    + text("Source note", event.getOrDefault("source_note", ""));

            LinearLayout card = TravelUi.card(this, TravelUi.LAVENDER);
            card.addView(TravelUi.cardTitle(this, "🎫", name));
            card.addView(TravelUi.body(this, destination + detail));
            if (!official.isEmpty()) {
                card.addView(TravelUi.primaryButton(this, "Open official event page",
                        v -> TravelUi.open(this, official)));
            }
            card.addView(TravelUi.outlineButton(this, "Map and route",
                    v -> TravelUi.open(this, ExternalTravelLinks.mapsSearch(name + " " + destination))));
            card.addView(TravelUi.outlineButton(this, "Food near the event",
                    v -> TravelUi.open(this, ExternalTravelLinks.mapsSearch("restaurants near " + name + " " + destination))));
            card.addView(TravelUi.outlineButton(this, "Things to do nearby",
                    v -> TravelUi.open(this, ExternalTravelLinks.googleSearch("things to do near " + name + " " + destination))));
            root.addView(card);
        }
    }

    private static String text(String label, String value) {
        return value == null || value.trim().isEmpty() ? "" : "\n" + label + ": " + value.trim();
    }
}
