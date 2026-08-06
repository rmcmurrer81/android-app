package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.List;
import java.util.Map;

/** Guest-experience tools before arrival, during the stay, and at checkout. */
public final class StayAssistantActivity extends Activity {
    private TravelContextSnapshot trip;
    private String hotelName;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        load();
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        render();
    }

    private void load() {
        trip = TravelContextSnapshot.load(this);
        hotelName = getSharedPreferences("sarah_stay_assistant", MODE_PRIVATE)
                .getString("p" + trip.personId + "_hotel", "");
    }

    private void render() {
        LinearLayout root = TravelUi.page(this);
        root.addView(TravelUi.hero(
                this,
                "Hotel stay assistant",
                hotelName.isEmpty() ? "Hotel not selected" : hotelName,
                trip.hasDestination() ? trip.destination + " • " + trip.personName : trip.personName));

        LinearLayout hotel = TravelUi.card(this, TravelUi.SKY);
        hotel.addView(TravelUi.cardTitle(this, "🏨", "Connect this stay"));
        hotel.addView(TravelUi.body(this,
                "Set the hotel name, share a booking link or screenshot with Sarah, and use the hotel's official contact information. Sarah does not sign into private booking accounts or assume a draft request was delivered."));
        hotel.addView(TravelUi.primaryButton(this, "Set hotel name", v -> setHotel()));
        hotel.addView(TravelUi.outlineButton(this, "Find the hotel's official website or contact",
                v -> TravelUi.open(this, ExternalTravelLinks.googleSearch(
                        (hotelName.isEmpty() ? "hotel" : hotelName) + " " + trip.destination + " official website contact"))));
        root.addView(hotel);

        root.addView(TravelUi.section(this, "Quick guest requests"));
        root.addView(requestCard("🌙", "Late arrival",
                "arrival", "Late arrival notice",
                "I expect to arrive later than normal check-in. Please keep the reservation active and tell me the correct late-arrival procedure.",
                "normal", TravelUi.LAVENDER));
        root.addView(requestCard("🤫", "Quiet room",
                "room_preference", "Quiet-room request",
                "If available, I would appreciate a quieter room away from elevators, ice machines, major street noise, or event spaces. I understand this is a request, not a guarantee.",
                "normal", TravelUi.MINT));
        root.addView(requestCard("♿", "Accessible room or route",
                "accessibility", "Accessibility request",
                "Please confirm the accessible room features and a step-free route from arrival to the room. I will add the exact features I need before sending.",
                "high", TravelUi.SKY));
        root.addView(requestCard("🛏️", "Allergy or bedding request",
                "room_preference", "Bedding or allergy request",
                "Please note the following bedding or allergy-related request. I understand the hotel must confirm what it can provide.",
                "high", TravelUi.PEACH));
        root.addView(requestCard("🧹", "Housekeeping",
                "housekeeping", "Housekeeping request",
                "Please help with the following housekeeping request for my room.",
                "normal", TravelUi.MINT));
        root.addView(requestCard("🔧", "Maintenance problem",
                "maintenance", "Maintenance issue",
                "There is a maintenance issue in the room. I will describe the problem and whether it affects safety or the ability to use the room.",
                "high", TravelUi.PEACH));
        root.addView(requestCard("🕐", "Late checkout",
                "checkout", "Late-checkout request",
                "Is a later checkout available, and is there a fee? Please confirm the time and total charge before changing the reservation.",
                "normal", TravelUi.LAVENDER));
        root.addView(requestCard("✍️", "Custom request",
                "general", "Hotel request",
                "I have a request about my stay.",
                "normal", TravelUi.SKY));

        root.addView(TravelUi.section(this, "Saved request ledger"));
        StayRequestStore store = new StayRequestStore(this);
        List<Map<String, String>> rows;
        try {
            rows = store.listForPerson(trip.personId, 100);
        } finally {
            store.close();
        }
        if (rows.isEmpty()) {
            LinearLayout empty = TravelUi.card(this, TravelUi.CREAM);
            empty.addView(TravelUi.body(this,
                    "No requests saved. A new request starts as a draft and remains a draft until the traveler sends it through a real contact channel."));
            root.addView(empty);
        } else {
            for (Map<String, String> row : rows) root.addView(savedRequest(row));
        }
    }

    private LinearLayout requestCard(
            String icon,
            String label,
            String category,
            String title,
            String template,
            String priority,
            int color) {
        LinearLayout card = TravelUi.card(this, color);
        card.addView(TravelUi.cardTitle(this, icon, label));
        card.addView(TravelUi.body(this, template));
        card.addView(TravelUi.outlineButton(this, "Create request draft",
                v -> editAndSave(category, title, template, priority)));
        return card;
    }

    private void editAndSave(String category, String title, String template, String priority) {
        EditText detail = new EditText(this);
        detail.setText(template);
        detail.setMinLines(5);
        detail.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        int padding = TravelUi.dp(this, 18);
        detail.setPadding(padding, padding, padding, padding);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("Add the details the hotel needs. Avoid putting payment-card details or account passwords in the message.")
                .setView(detail)
                .setPositiveButton("Save draft", (dialog, which) -> {
                    StayRequestStore store = new StayRequestStore(this);
                    try {
                        store.add(
                                trip.personId,
                                trip.personName,
                                trip.destination,
                                hotelName,
                                category,
                                title,
                                detail.getText().toString(),
                                priority);
                    } finally {
                        store.close();
                    }
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private LinearLayout savedRequest(Map<String, String> row) {
        LinearLayout card = TravelUi.card(this, TravelUi.CREAM);
        String title = row.getOrDefault("title", "Request");
        String status = row.getOrDefault("status", "draft");
        card.addView(TravelUi.cardTitle(this, "📨", title + " • " + status));
        card.addView(TravelUi.body(this,
                row.getOrDefault("detail", "")
                        + "\nPriority: " + row.getOrDefault("priority", "normal")
                        + (row.getOrDefault("hotel_name", "").isEmpty()
                                ? "" : "\nHotel: " + row.get("hotel_name"))));
        card.addView(TravelUi.primaryButton(this, "Share or send this draft",
                v -> share(row)));
        if ("draft".equals(status)) {
            card.addView(TravelUi.outlineButton(this, "Mark as sent by traveler",
                    v -> updateStatus(row, "sent_by_traveler")));
        } else if (!"confirmed_by_hotel".equals(status)) {
            card.addView(TravelUi.outlineButton(this, "Mark as confirmed by hotel",
                    v -> updateStatus(row, "confirmed_by_hotel")));
        }
        card.addView(TravelUi.outlineButton(this, "Remove local draft",
                v -> remove(row)));
        return card;
    }

    private void share(Map<String, String> row) {
        String text = row.getOrDefault("title", "Hotel request") + "\n\n"
                + row.getOrDefault("detail", "")
                + (hotelName.isEmpty() ? "" : "\n\nHotel: " + hotelName)
                + (trip.destination.isEmpty() ? "" : "\nDestination: " + trip.destination);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, row.getOrDefault("title", "Hotel request"));
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, "Send hotel request"));
    }

    private void setHotel() {
        EditText field = new EditText(this);
        field.setHint("Hotel name");
        field.setText(hotelName);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        int padding = TravelUi.dp(this, 18);
        field.setPadding(padding, padding, padding, padding);
        new AlertDialog.Builder(this)
                .setTitle("Hotel for this profile")
                .setView(field)
                .setPositiveButton("Save", (dialog, which) -> {
                    hotelName = field.getText().toString().trim();
                    getSharedPreferences("sarah_stay_assistant", MODE_PRIVATE)
                            .edit()
                            .putString("p" + trip.personId + "_hotel", hotelName)
                            .apply();
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateStatus(Map<String, String> row, String status) {
        StayRequestStore store = new StayRequestStore(this);
        try {
            store.updateStatus(number(row.get("id")), status);
        } finally {
            store.close();
        }
        render();
    }

    private void remove(Map<String, String> row) {
        new AlertDialog.Builder(this)
                .setTitle("Remove this local request?")
                .setMessage("This does not cancel or change anything at the hotel.")
                .setPositiveButton("Remove", (dialog, which) -> {
                    StayRequestStore store = new StayRequestStore(this);
                    try {
                        store.delete(number(row.get("id")));
                    } finally {
                        store.close();
                    }
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static long number(String value) {
        try { return Long.parseLong(value == null ? "0" : value); }
        catch (Exception ignored) { return 0; }
    }
}
