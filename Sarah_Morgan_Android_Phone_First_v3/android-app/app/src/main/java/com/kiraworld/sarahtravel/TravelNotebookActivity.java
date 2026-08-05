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

        addHeader("Travel deal watches");
        List<Map<String, String>> watches = db.listDealWatches(100);
        if (watches.isEmpty()) {
            addRow("No watch yet", "Mention a dream destination or ask Sarah to watch for deals.");
        } else {
            for (Map<String, String> watch : watches) {
                String title = watch.get("origin") + " → " + watch.get("destination");
                String detail = "Status: " + watch.get("backend_status")
                        + " • " + watch.get("trip_type")
                        + " • " + watch.get("travelers") + " traveler(s)"
                        + " • " + watch.get("bag_mode")
                        + " • " + watch.get("min_trip_days") + "–" + watch.get("max_trip_days") + " nights"
                        + " • flexible dates: " + yesNo(watch.get("flexible_dates"))
                        + " • nearby airports: " + yesNo(watch.get("nearby_airports"));
                addRow(title, detail);
            }
        }

        addHeader("Destination knowledge packs");
        List<Map<String, String>> packs = db.listKnowledgePacks(100);
        if (packs.isEmpty()) {
            addRow("No pack yet", "Sarah queues a pack automatically when a destination becomes part of a plan.");
        } else {
            for (Map<String, String> pack : packs) {
                String detail = "Status: " + pack.get("status");
                if (!pack.getOrDefault("overview", "").isEmpty()) detail += "\n" + pack.get("overview");
                if (!pack.getOrDefault("events", "").isEmpty()) detail += "\nEvents: " + pack.get("events");
                if (!pack.getOrDefault("source_note", "").isEmpty()) detail += "\nSource note: " + pack.get("source_note");
                addRow(pack.get("destination"), detail);
            }
        }

        addHeader("Legacy deal requests");
        boolean hasDealRequest = addMemoryRows(memories, "deal_watch_request");
        if (!hasDealRequest) addRow("None", "Older request-only records will appear here if present.");

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
        hasTravelMemory |= addMemoryRows(memories, "travel_worry");
        hasTravelMemory |= addMemoryRows(memories, "travel_experience");
        hasTravelMemory |= addMemoryRows(memories, "trip_focus");
        if (!hasTravelMemory) addRow("Nothing saved yet", "Sarah only adds approved memories when memory is enabled.");

        addHeader("Other things Sarah remembers");
        for (Map<String, String> row : memories) {
            String category = row.getOrDefault("category", "");
            if (category.equals("deal_watch_request")
                    || category.equals("travel_preference")
                    || category.equals("travel_worry")
                    || category.equals("travel_experience")
                    || category.equals("trip_focus")) continue;
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
        if (category.equals("deal_watch_request")) return "Older request record";
        if (category.equals("travel_preference")) return "Preference";
        if (category.equals("travel_worry")) return "Travel worry";
        if (category.equals("travel_experience")) return "Travel experience";
        if (category.equals("trip_focus")) return "Trip focus";
        return category;
    }

    private static String yesNo(String value) {
        return "1".equals(value) ? "yes" : "no";
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
}
