package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

public final class TravelNotebookActivity extends Activity {
    private SarahDatabase db;
    private LinearLayout container;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_notebook);
        db = new SarahDatabase(this);
        container = findViewById(R.id.notebookContainer);
        findViewById(R.id.addWishButton).setOnClickListener(v -> showWishDialog());
        findViewById(R.id.addTripButton).setOnClickListener(v -> showTripDialog());
        refresh();
    }

    private void refresh() {
        container.removeAllViews();
        List<Map<String, String>> memories = db.listMemories(150);

        addHeader("Destination knowledge packs");
        List<Map<String, String>> packs = db.listKnowledgePacks(100);
        if (packs.isEmpty()) {
            addRow("No packs yet", "Mention a possible destination and Sarah will queue one automatically. Current recommendations and events require Smart setup.");
        } else {
            for (Map<String, String> pack : packs) {
                String destination = pack.getOrDefault("destination", "Destination");
                String status = pack.getOrDefault("status", "pending");
                if (!"ready".equals(status)) {
                    addRow(destination + " — research queued", "Sarah will retry when internet and a connected-model key are available.");
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

        addHeader("Active travel-deal watches");
        List<Map<String, String>> watches = db.listDealWatches(100);
        if (watches.isEmpty()) {
            addRow("No active watches", "Saying that a destination is a dream trip or asking Sarah to watch for deals creates a broad watch with sensible defaults.");
        } else {
            for (Map<String, String> watch : watches) {
                String title = watch.getOrDefault("origin", "Home area") + " → "
                        + watch.getOrDefault("destination", "Destination");
                String detail = "Status: " + watch.getOrDefault("backend_status", "queued")
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
        for (Map<String, String> row : db.listWishes(50)) addRow(row.get("destination"), row.get("notes"));

        addHeader("Trips");
        for (Map<String, String> row : db.listTrips(50)) {
            addRow(row.get("status") + ": " + row.get("destination"),
                    row.get("title") + (row.get("notes").isEmpty() ? "" : " — " + row.get("notes")));
        }

        addHeader("Travel preferences and needs");
        boolean hasTravelMemory = false;
        hasTravelMemory |= addMemoryRows(memories, "travel_preference");
        hasTravelMemory |= addMemoryRows(memories, "trip_focus");
        hasTravelMemory |= addMemoryRows(memories, "travel_worry");
        hasTravelMemory |= addMemoryRows(memories, "travel_experience");
        if (!hasTravelMemory) addRow("Nothing saved yet", "Sarah only adds approved memories when memory is enabled.");

        addHeader("Other things Sarah remembers");
        for (Map<String, String> row : memories) {
            String category = row.getOrDefault("category", "");
            if (category.equals("deal_watch_request")
                    || category.equals("travel_preference")
                    || category.equals("trip_focus")
                    || category.equals("travel_worry")
                    || category.equals("travel_experience")) continue;
            addRow(category, row.get("summary"));
        }
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

    private static double number(String value) {
        try { return Double.parseDouble(value == null ? "0" : value); }
        catch (Exception ignored) { return 0; }
    }
}
