package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.List;
import java.util.Map;

/** Structured itinerary, budget and packing lists for the active profile. */
public final class TripPlannerActivity extends Activity {
    private TravelContextSnapshot trip;

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
        trip = TravelContextSnapshot.load(this);
        LinearLayout root = TravelUi.page(this);
        root.addView(TravelUi.hero(
                this,
                "AI trip planning track",
                trip.hasDestination() ? trip.destination : "Choose a trip in Sarah",
                trip.dateLabel() + " • " + trip.personName));

        LinearLayout approach = TravelUi.card(this, TravelUi.SKY);
        approach.addView(TravelUi.cardTitle(this, "🧠", "From conversation to an editable plan"));
        approach.addView(TravelUi.body(this,
                "Sarah can suggest a plan in conversation, but an itinerary item, budget amount, or packing task becomes durable only after it is saved here. That keeps ideas separate from confirmed plans."));
        approach.addView(TravelUi.outlineButton(this, "Find things to add",
                v -> TravelUi.start(this, LocalExperienceActivity.class)));
        root.addView(approach);

        if (!trip.hasDestination()) {
            LinearLayout empty = TravelUi.card(this, TravelUi.PEACH);
            empty.addView(TravelUi.body(this,
                    "Tell Sarah you are planning or considering a destination or event, then return here."));
            root.addView(empty);
            return;
        }

        TripPlanStore store = new TripPlanStore(this);
        List<Map<String, String>> itinerary;
        List<Map<String, String>> budget;
        List<Map<String, String>> packing;
        try {
            itinerary = store.itinerary(trip.personId, trip.destination, 200);
            budget = store.budget(trip.personId, trip.destination, 100);
            packing = store.packing(trip.personId, trip.destination, 200);
        } finally {
            store.close();
        }

        root.addView(TravelUi.section(this, "Day-by-day itinerary"));
        LinearLayout itineraryActions = TravelUi.card(this, TravelUi.MINT);
        itineraryActions.addView(TravelUi.primaryButton(this, "Add itinerary item", v -> addItinerary()));
        root.addView(itineraryActions);
        if (itinerary.isEmpty()) {
            root.addView(emptyCard("No itinerary items yet", "Add one place, meal, travel segment, rest block or event at a time. Leave breathing room instead of creating a landmark race."));
        } else {
            for (Map<String, String> row : itinerary) root.addView(itineraryCard(row));
        }

        root.addView(TravelUi.section(this, "Budget"));
        LinearLayout budgetActions = TravelUi.card(this, TravelUi.PEACH);
        budgetActions.addView(TravelUi.primaryButton(this, "Add budget item", v -> addBudget()));
        root.addView(budgetActions);
        if (budget.isEmpty()) {
            root.addView(emptyCard("No budget items yet", "Track planned and actual amounts for transport, hotel, food, experiences, local rides, parking, fees and a small emergency buffer."));
        } else {
            double planned = 0;
            double actual = 0;
            for (Map<String, String> row : budget) {
                planned += number(row.get("planned"));
                actual += number(row.get("actual"));
                root.addView(budgetCard(row));
            }
            LinearLayout total = TravelUi.card(this, TravelUi.LAVENDER);
            total.addView(TravelUi.cardTitle(this, "💰", "Budget totals"));
            total.addView(TravelUi.body(this,
                    String.format("Planned: $%.2f\nActual recorded: $%.2f\nDifference: $%.2f",
                            planned, actual, planned - actual)));
            root.addView(total);
        }

        root.addView(TravelUi.section(this, "Packing and preparation"));
        LinearLayout packingActions = TravelUi.card(this, TravelUi.SKY);
        packingActions.addView(TravelUi.primaryButton(this, "Add packing or preparation item", v -> addPacking()));
        root.addView(packingActions);
        if (packing.isEmpty()) {
            root.addView(emptyCard("No packing items yet", "Include travel documents, medication, chargers, accessibility equipment, weather layers, event tickets, backup payment, snacks and comfort items where relevant."));
        } else {
            for (Map<String, String> row : packing) root.addView(packingCard(row));
        }
    }

    private LinearLayout itineraryCard(Map<String, String> row) {
        LinearLayout card = TravelUi.card(this, TravelUi.MINT);
        String when = value(row.get("date"), "date open")
                + (row.getOrDefault("time", "").isEmpty() ? "" : " • " + row.get("time"));
        card.addView(TravelUi.cardTitle(this, "📍", row.getOrDefault("title", "Plan item")));
        card.addView(TravelUi.body(this,
                when
                        + text("Location", row.get("location"))
                        + text("Category", row.get("category"))
                        + (number(row.get("cost")) > 0 ? String.format("\nEstimated cost: $%.2f", number(row.get("cost"))) : "")
                        + text("Notes", row.get("notes"))));
        card.addView(TravelUi.outlineButton(this, "Remove item",
                v -> remove("itinerary_items", row)));
        return card;
    }

    private LinearLayout budgetCard(Map<String, String> row) {
        LinearLayout card = TravelUi.card(this, TravelUi.PEACH);
        card.addView(TravelUi.cardTitle(this, "🧾", row.getOrDefault("category", "Budget")));
        card.addView(TravelUi.body(this,
                String.format("Planned: $%.2f\nActual: $%.2f",
                        number(row.get("planned")), number(row.get("actual")))
                        + text("Notes", row.get("notes"))));
        card.addView(TravelUi.outlineButton(this, "Remove budget item",
                v -> remove("budget_items", row)));
        return card;
    }

    private LinearLayout packingCard(Map<String, String> row) {
        LinearLayout card = TravelUi.card(this, TravelUi.CREAM);
        CheckBox packed = new CheckBox(this);
        packed.setText(row.getOrDefault("label", "Packing item")
                + " • " + row.getOrDefault("category", "general"));
        packed.setChecked("1".equals(row.get("packed")));
        packed.setOnCheckedChangeListener((button, checked) -> {
            TripPlanStore store = new TripPlanStore(this);
            try {
                store.togglePacked(longNumber(row.get("id")), checked);
            } finally {
                store.close();
            }
        });
        card.addView(packed);
        card.addView(TravelUi.outlineButton(this, "Remove item",
                v -> remove("packing_items", row)));
        return card;
    }

    private void addItinerary() {
        LinearLayout box = form();
        EditText date = field("Date or day label", trip.startDate, InputType.TYPE_CLASS_TEXT);
        EditText time = field("Time (optional)", "", InputType.TYPE_CLASS_TEXT);
        EditText title = field("What is planned?", "", InputType.TYPE_CLASS_TEXT);
        EditText location = field("Location or address", "", InputType.TYPE_CLASS_TEXT);
        EditText category = field("Category: activity, meal, travel, rest, event...", "activity", InputType.TYPE_CLASS_TEXT);
        EditText cost = field("Estimated cost", "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText notes = field("Notes, tickets, accessibility, backup plan...", "", InputType.TYPE_CLASS_TEXT);
        box.addView(date); box.addView(time); box.addView(title); box.addView(location);
        box.addView(category); box.addView(cost); box.addView(notes);
        new AlertDialog.Builder(this)
                .setTitle("Add itinerary item")
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = title.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Enter an itinerary title.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    TripPlanStore store = new TripPlanStore(this);
                    try {
                        store.addItinerary(
                                trip.personId, trip.destination,
                                date.getText().toString(), time.getText().toString(), name,
                                location.getText().toString(), category.getText().toString(),
                                number(cost.getText().toString()), notes.getText().toString());
                    } finally { store.close(); }
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addBudget() {
        LinearLayout box = form();
        EditText category = field("Category", "", InputType.TYPE_CLASS_TEXT);
        EditText planned = field("Planned amount", "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText actual = field("Actual amount so far", "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText notes = field("Notes", "", InputType.TYPE_CLASS_TEXT);
        box.addView(category); box.addView(planned); box.addView(actual); box.addView(notes);
        new AlertDialog.Builder(this)
                .setTitle("Add budget item")
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = category.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Enter a category.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    TripPlanStore store = new TripPlanStore(this);
                    try {
                        store.addBudget(trip.personId, trip.destination, name,
                                number(planned.getText().toString()),
                                number(actual.getText().toString()),
                                notes.getText().toString());
                    } finally { store.close(); }
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addPacking() {
        LinearLayout box = form();
        EditText category = field("Category", "general", InputType.TYPE_CLASS_TEXT);
        EditText label = field("Item or preparation task", "", InputType.TYPE_CLASS_TEXT);
        box.addView(category); box.addView(label);
        new AlertDialog.Builder(this)
                .setTitle("Add packing item")
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = label.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Enter an item.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    TripPlanStore store = new TripPlanStore(this);
                    try {
                        store.addPacking(trip.personId, trip.destination,
                                category.getText().toString(), name);
                    } finally { store.close(); }
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void remove(String table, Map<String, String> row) {
        new AlertDialog.Builder(this)
                .setTitle("Remove this item?")
                .setPositiveButton("Remove", (dialog, which) -> {
                    TripPlanStore store = new TripPlanStore(this);
                    try { store.remove(table, longNumber(row.get("id"))); }
                    finally { store.close(); }
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private LinearLayout emptyCard(String title, String body) {
        LinearLayout card = TravelUi.card(this, TravelUi.CREAM);
        card.addView(TravelUi.cardTitle(this, "•", title));
        card.addView(TravelUi.body(this, body));
        return card;
    }

    private LinearLayout form() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = TravelUi.dp(this, 18);
        box.setPadding(padding, padding / 2, padding, 0);
        return box;
    }

    private EditText field(String hint, String value, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value == null ? "" : value);
        field.setInputType(inputType | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private static String text(String label, String value) {
        return value == null || value.trim().isEmpty() ? "" : "\n" + label + ": " + value.trim();
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static double number(String value) {
        try { return Double.parseDouble(value == null ? "0" : value.trim()); }
        catch (Exception ignored) { return 0; }
    }

    private static long longNumber(String value) {
        try { return Long.parseLong(value == null ? "0" : value.trim()); }
        catch (Exception ignored) { return 0; }
    }
}
