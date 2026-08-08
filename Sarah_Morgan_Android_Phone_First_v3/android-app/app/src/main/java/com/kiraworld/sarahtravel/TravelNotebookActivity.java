package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

public final class TravelNotebookActivity extends Activity {
    private SarahDatabase db;
    private EventTripStore eventStore;
    private MobilityWatchStore mobilityStore;
    private PersonProfileStore people;
    private LinearLayout container;
    private Map<String, String> activeProfile;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_notebook);
        SafeAreaInsets.apply(
                this,
                findViewById(R.id.notebookRoot),
                null,
                findViewById(R.id.notebookScroll));
        db = new SarahDatabase(this);
        eventStore = new EventTripStore(this);
        mobilityStore = new MobilityWatchStore(this);
        people = new PersonProfileStore(this);
        Map<String, String> owner = db.getProfile();
        people.ensureOwner(owner);
        activeProfile = people.getActiveProfile();
        if (activeProfile.isEmpty()) activeProfile = people.ensureOwner(owner);

        container = findViewById(R.id.notebookContainer);
        View addWish = findViewById(R.id.addWishButton);
        View addTrip = findViewById(R.id.addTripButton);
        boolean ownerActive = isOwnerActive();
        addWish.setVisibility(ownerActive ? View.VISIBLE : View.GONE);
        addTrip.setVisibility(ownerActive ? View.VISIBLE : View.GONE);
        addWish.setOnClickListener(v -> showWishDialog());
        addTrip.setOnClickListener(v -> showTripDialog());
        refresh();
    }

    private void refresh() {
        container.removeAllViews();
        addProfileSection();
        if (!isOwnerActive()) {
            refreshSeparateProfile();
            return;
        }
        refreshOwnerNotebook();
    }

    private void addProfileSection() {
        addHeader("People using this phone");
        if (isOwnerActive()) {
            for (Map<String, String> profile : people.listProfiles()) {
                String detail = joinSections(
                        label("Age", "yes".equals(profile.get("age_known")) ? profile.get("age") : "not set"),
                        label("Memory", profile.getOrDefault("memory_consent", "unknown")),
                        label("Relationship", profile.getOrDefault("relationship", "")),
                        "yes".equals(profile.get("active")) ? "Currently talking with Sarah" : "");
                addRow(profile.getOrDefault("name", "Person")
                        + ("yes".equals(profile.get("is_owner")) ? " — phone owner" : ""), detail);
            }
            addRow("Switch profiles", "Use the person icon in Sarah’s main header. A new person may say “My name is …” and Sarah will ask age before using age-sensitive suggestions.");
        } else {
            addRow(activeProfile.getOrDefault("name", "Person") + " — separate profile",
                    joinSections(
                            label("Age", "yes".equals(activeProfile.get("age_known")) ? activeProfile.get("age") : "not set"),
                            label("Age group", activeProfile.getOrDefault("age_group", "family-friendly until known")),
                            label("Memory", activeProfile.getOrDefault("memory_consent", "unknown")),
                            "Owner memories, private trips, bookings, watches, and other profiles are hidden in this view."));
        }
    }

    private void refreshSeparateProfile() {
        String name = activeProfile.getOrDefault("name", "Person");

        addHeader(name + "’s approved memories");
        List<Map<String, String>> memories = people.listMemories(name, 100);
        if (memories.isEmpty()) {
            addRow("Nothing saved", "Sarah keeps the conversation separate but saves personal interests only when this profile has permission.");
        } else {
            for (Map<String, String> memory : memories) {
                addRow(memory.getOrDefault("category", "memory"), memory.getOrDefault("summary", ""));
            }
        }

        addHeader("Trips this profile is joining");
        boolean found = false;
        for (Map<String, String> trip : db.listTrips(100)) {
            String destination = trip.getOrDefault("destination", "");
            if (!"going".equals(people.getTripParticipation(name, destination))) continue;
            addRow(destination, "Recorded as joining this shared trip. Owner-private notes and bookings are hidden.");
            found = true;
        }
        for (Map<String, String> event : eventStore.listActiveEventTrips(100)) {
            String destination = event.getOrDefault("destination", "");
            if (!"going".equals(people.getTripParticipation(name, destination))) continue;
            addRow(event.getOrDefault("event_name", "Shared event") + " — " + destination,
                    joinSections(
                            dateRange(event.get("start_date"), event.get("end_date")),
                            label("Venue", event.getOrDefault("venue", "")),
                            "Only shared event facts are shown; owner-private bookings and notes remain hidden."));
            found = true;
        }
        if (!found) {
            addRow("No shared trip recorded", "Sarah will not assume this person is joining the phone owner’s trip. The person can answer when Sarah asks, or discuss their own destination separately.");
        }
    }

    private void refreshOwnerNotebook() {
        List<Map<String, String>> memories = db.listMemories(150);

        addHeader("Saved journeys");
        List<Map<String, String>> journeys = mobilityStore.listJourneyPlans(100);
        if (journeys.isEmpty()) {
            addRow("No saved journey yet", "Say something like “cross-country train from New York to California” or “take the metro to New York Comic Con.”");
        } else {
            for (Map<String, String> journey : journeys) {
                String title = journey.getOrDefault("origin", "Origin") + " → "
                        + journey.getOrDefault("destination", "Destination");
                String detail = joinSections(
                        label("Methods", journey.getOrDefault("modes", "mixed").replace(',', '/')),
                        label("Event", journey.getOrDefault("event_name", "")),
                        label("Notes", journey.getOrDefault("notes", "")),
                        label("Saved", date(journey.get("updated_at"))));
                addRow(title, detail);
            }
        }

        addHeader("Multimodal travel watches");
        List<Map<String, String>> mobilityWatches = mobilityStore.listWatches(100);
        if (mobilityWatches.isEmpty()) {
            addRow("No multimodal watch", "Ask Sarah to monitor travel options. She can compare air, Amtrak or rail, intercity bus, local transit, driving, and ferry where appropriate.");
        } else {
            for (Map<String, String> watch : mobilityWatches) {
                String title = watch.getOrDefault("origin", "Origin") + " → "
                        + watch.getOrDefault("destination", "Destination");
                String detail = joinSections(
                        label("Methods", watch.getOrDefault("modes", "mixed").replace(',', '/')),
                        label("Purpose", watch.getOrDefault("purpose", "options")),
                        label("Event", watch.getOrDefault("event_name", "")),
                        label("Status", humanMonitoringStatus(watch.getOrDefault("backend_status", "queued"))),
                        label("Last checked", date(watch.get("last_checked_at"))),
                        label("Latest result", watch.getOrDefault("last_summary", "")),
                        label("Source note", watch.getOrDefault("last_source_note", "")));
                addRow(title, detail);
            }
        }

        addHeader("Monitored event trips");
        List<Map<String, String>> events = eventStore.listActiveEventTrips(100);
        if (events.isEmpty()) {
            addRow("No monitored events", "Mention a known event or an unfamiliar convention. Sarah should verify its location and dates before saving it as an event trip.");
        } else {
            for (Map<String, String> event : events) {
                String title = event.getOrDefault("event_name", "Event") + " — "
                        + event.getOrDefault("destination", "Destination");
                String detail = joinSections(
                        label("Status", humanMonitoringStatus(event.getOrDefault("monitor_status", "queued"))),
                        label("Venue", event.getOrDefault("venue", "")),
                        dateRange(event.get("start_date"), event.get("end_date")),
                        label("Latest monitored details", event.getOrDefault("updates_summary", "")),
                        label("Nearby food", event.getOrDefault("nearby_food", "")),
                        label("Nearby places", event.getOrDefault("nearby_places", "")),
                        label("Transportation", event.getOrDefault("transport_notes", "")),
                        label("Official or research source", event.getOrDefault("source_note", "")),
                        label("Last checked", date(event.get("last_checked_at"))));
                addRow(title, detail);
            }
        }

        addHeader("Imported bookings");
        List<Map<String, String>> bookings = eventStore.listBookings(100);
        if (bookings.isEmpty()) {
            addRow("No booking imports", "Share an Expedia or other booking link, or share a booking screenshot from the phone Gallery.");
        } else {
            for (Map<String, String> booking : bookings) {
                String title = booking.getOrDefault("provider", "Booking") + " — "
                        + booking.getOrDefault("booking_type", "travel");
                String detail = joinSections(
                        label("Status", booking.getOrDefault("status", "pending_review")),
                        label("Summary", booking.getOrDefault("extracted_summary", "")),
                        label("Dates", joinedDates(booking.get("start_date"), booking.get("end_date"))),
                        label("Address", booking.getOrDefault("address", "")),
                        money(booking.get("total"), booking.get("currency")),
                        label("Confirmation code", booking.getOrDefault("confirmation_code", "")),
                        label("Source", booking.getOrDefault("source_kind", "")));
                addRow(title, detail);
            }
        }

        addHeader("Destination knowledge packs");
        List<Map<String, String>> packs = db.listKnowledgePacks(100);
        if (packs.isEmpty()) {
            addRow("No packs yet", "Mention a possible destination and Sarah can prepare one when a verified current-source connection is available.");
        } else {
            for (Map<String, String> pack : packs) {
                String destination = pack.getOrDefault("destination", "Destination");
                String status = pack.getOrDefault(
                        "status", SarahDatabase.KNOWLEDGE_PENDING_NOT_SCHEDULED);
                if (!SarahDatabase.KNOWLEDGE_READY.equalsIgnoreCase(status)) {
                    String label = SarahDatabase.KNOWLEDGE_PENDING_NOT_SCHEDULED.equalsIgnoreCase(status)
                            ? "saved request — not scheduled"
                            : SarahDatabase.KNOWLEDGE_PENDING_SCHEDULED.equalsIgnoreCase(status)
                                ? "scheduled"
                                : SarahDatabase.KNOWLEDGE_RUNNING.equalsIgnoreCase(status)
                                    ? "running"
                                    : SarahDatabase.KNOWLEDGE_FAILED.equalsIgnoreCase(status)
                                        ? "last attempt failed"
                                        : status.toLowerCase();
                    addRow(destination + " — " + label,
                            "A request is not called running until Android accepted a real job. Failures remain recorded and no result is claimed.");
                    continue;
                }
                String detail = joinSections(
                        pack.getOrDefault("overview", ""),
                        label("Recommended starting points", pack.getOrDefault("recommendations", "")),
                        label("Transport", pack.getOrDefault("transport", "")),
                        label("Accessibility and sensory notes", pack.getOrDefault("accessibility", "")),
                        label("Seasonal context", pack.getOrDefault("seasonal", "")),
                        label("Current events to verify", pack.getOrDefault("events", "")),
                        label("Research note", pack.getOrDefault("source_note", "")));
                addRow(destination + " — refreshed " + date(pack.get("refreshed_at")), detail);
            }
        }

        addHeader("Legacy airfare watches");
        List<Map<String, String>> watches = db.listDealWatches(100);
        if (watches.isEmpty()) {
            addRow("No flight-only watch", "New broad requests use the multimodal watch above instead of assuming air travel.");
        } else {
            for (Map<String, String> watch : watches) {
                String title = watch.getOrDefault("origin", "Home area") + " → "
                        + watch.getOrDefault("destination", "Destination");
                String detail = "Status: " + humanMonitoringStatus(watch.getOrDefault("backend_status", "queued"))
                        + "\nRound trip, " + watch.getOrDefault("travelers", "1") + " traveler"
                        + "\nFlexible dates: " + yesNo(watch.get("flexible_dates"))
                        + "; nearby airports: " + yesNo(watch.get("nearby_airports"))
                        + "\nTrip lengths: " + watch.getOrDefault("min_trip_days", "3")
                        + "–" + watch.getOrDefault("max_trip_days", "14") + " nights"
                        + "\nBags: " + watch.getOrDefault("bag_mode", "carry_on")
                        + "\nLast checked: " + date(watch.get("last_checked_at"));
                double previous = number(watch.get("last_notified_price"));
                if (previous > 0) detail += "\nLast notified fare: "
                        + watch.getOrDefault("currency", "USD") + " " + Math.round(previous);
                addRow(title, detail);
            }
        }

        addHeader("Places I want to visit");
        List<Map<String, String>> wishes = db.listWishes(50);
        if (wishes.isEmpty()) addRow("Nothing saved", "Sarah can save dream destinations for the phone owner.");
        for (Map<String, String> row : wishes) addRow(row.get("destination"), row.get("notes"));

        addHeader("Trips");
        List<Map<String, String>> trips = db.listTrips(50);
        if (trips.isEmpty()) addRow("No trip records", "A phrase such as “I am going to New York next week” can create a planned trip automatically.");
        for (Map<String, String> row : trips) {
            addRow(row.get("status") + ": " + row.get("destination"),
                    row.get("title") + (row.get("notes").isEmpty() ? "" : " — " + row.get("notes")));
        }

        addHeader("Travel preferences and needs");
        boolean hasTravelMemory = false;
        hasTravelMemory |= addMemoryRows(memories, "travel_preference");
        hasTravelMemory |= addMemoryRows(memories, "trip_focus");
        hasTravelMemory |= addMemoryRows(memories, "travel_worry");
        hasTravelMemory |= addMemoryRows(memories, "travel_experience");
        hasTravelMemory |= addMemoryRows(memories, "event_trip");
        hasTravelMemory |= addMemoryRows(memories, "journey_plan");
        if (!hasTravelMemory) addRow("Nothing saved yet", "Sarah only adds approved memories when memory is enabled.");

        addHeader("Other things Sarah remembers");
        boolean other = false;
        for (Map<String, String> row : memories) {
            String category = row.getOrDefault("category", "");
            if (category.equals("deal_watch_request")
                    || category.equals("travel_preference")
                    || category.equals("trip_focus")
                    || category.equals("travel_worry")
                    || category.equals("travel_experience")
                    || category.equals("event_trip")
                    || category.equals("journey_plan")) continue;
            addRow(category, row.get("summary"));
            other = true;
        }
        if (!other) addRow("Nothing else saved", "Movies, shows, books, games, and other interests can be stored in the active profile when memory is allowed.");
    }

    private boolean isOwnerActive() {
        return "yes".equals(activeProfile.getOrDefault("is_owner", "no"));
    }

    private boolean addMemoryRows(List<Map<String, String>> memories, String wantedCategory) {
        boolean added = false;
        for (Map<String, String> row : memories) {
            if (!wantedCategory.equals(row.getOrDefault("category", ""))) continue;
            addRow(labelForCategory(wantedCategory), row.get("summary"));
            added = true;
        }
        return added;
    }

    private String labelForCategory(String category) {
        if (category.equals("travel_preference")) return "Preference";
        if (category.equals("trip_focus")) return "Trip focus";
        if (category.equals("travel_worry")) return "Travel worry";
        if (category.equals("travel_experience")) return "Travel experience";
        if (category.equals("event_trip")) return "Event trip";
        if (category.equals("journey_plan")) return "Journey plan";
        return category;
    }

    private void addHeader(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(20f);
        view.setTextColor(getColor(R.color.sarah_navy));
        view.setPadding(0, 20, 0, 8);
        container.addView(view);
    }

    private void addRow(String title, String detail) {
        TextView view = new TextView(this);
        view.setText("• " + title + (detail == null || detail.isEmpty() ? "" : "\n  " + detail));
        view.setTextSize(16f);
        view.setPadding(4, 8, 4, 8);
        container.addView(view);
    }

    private void showWishDialog() {
        if (!isOwnerActive()) return;
        LinearLayout box = dialogBox();
        EditText destination = field("Destination");
        EditText notes = field("Why you want to go or what interests you");
        box.addView(destination);
        box.addView(notes);
        new AlertDialog.Builder(this)
                .setTitle("Wish-list place")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    if (destination.getText().toString().trim().isEmpty()) {
                        Toast.makeText(this, "Enter a destination.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String place = destination.getText().toString().trim();
                    db.addWish(place, notes.getText().toString());
                    db.queueKnowledgePack(place);
                    DealWatchScheduler.runSoon(this);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showTripDialog() {
        if (!isOwnerActive()) return;
        LinearLayout box = dialogBox();
        EditText title = field("Trip name");
        EditText destination = field("Destination");
        EditText status = field("past, planned, or current");
        EditText notes = field("Favorite moments, worries, dates, or notes");
        box.addView(title);
        box.addView(destination);
        box.addView(status);
        box.addView(notes);
        new AlertDialog.Builder(this)
                .setTitle("Trip record")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    if (title.getText().toString().trim().isEmpty()
                            || destination.getText().toString().trim().isEmpty()) {
                        Toast.makeText(this, "Enter a name and destination.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String place = destination.getText().toString().trim();
                    db.addTrip(
                            title.getText().toString(),
                            place,
                            status.getText().toString().trim().isEmpty() ? "planned" : status.getText().toString(),
                            notes.getText().toString());
                    db.queueKnowledgePack(place);
                    DealWatchScheduler.runSoon(this);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(padding, padding / 2, padding, 0);
        return box;
    }

    private EditText field(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private static String label(String name, String value) {
        return value == null || value.trim().isEmpty() ? "" : name + ": " + value.trim();
    }

    private static String joinSections(String... values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append(value.trim());
        }
        return out.toString();
    }

    private static String yesNo(String value) {
        return "1".equals(value) || "yes".equalsIgnoreCase(value) ? "yes" : "no";
    }

    private static String date(String value) {
        try {
            long time = Long.parseLong(value == null ? "0" : value);
            if (time <= 0) return "not yet";
            return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(time));
        } catch (Exception ignored) {
            return "not yet";
        }
    }

    private static String dateRange(String start, String end) {
        String joined = joinedDates(start, end);
        return joined.isEmpty() ? "" : "Dates: " + joined;
    }

    private static String joinedDates(String start, String end) {
        String a = start == null ? "" : start.trim();
        String b = end == null ? "" : end.trim();
        if (a.isEmpty()) return b;
        if (b.isEmpty() || a.equals(b)) return a;
        return a + " to " + b;
    }

    private static String money(String total, String currency) {
        double amount = number(total);
        return amount <= 0 ? "" : "Total: " + (currency == null ? "USD" : currency) + " " + Math.round(amount);
    }

    private static double number(String value) {
        try { return Double.parseDouble(value == null ? "0" : value); }
        catch (Exception ignored) { return 0; }
    }

    private static String humanMonitoringStatus(String value) {
        String status = value == null ? "" : value.trim();
        if (status.equals("backend_not_configured") || status.equals("setup_required")) {
            return BackgroundResearchPolicy.unavailableStatus();
        }
        if (status.equals("temporary_error")) return "Temporary connection error · will retry only while enabled";
        if (status.equals("queued")) return "Saved request · waiting for its live connection";
        return status.replace('_', ' ');
    }

    @Override
    protected void onDestroy() {
        if (people != null) people.close();
        if (mobilityStore != null) mobilityStore.close();
        if (eventStore != null) eventStore.close();
        if (db != null) db.close();
        super.onDestroy();
    }
}
