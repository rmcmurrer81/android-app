package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.LinearLayout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local hackathon demonstration of how guest requests could become hotel tasks.
 * It is not connected to a property-management system and never pretends a
 * draft was received by a real hotel.
 */
public final class HospitalityOpsActivity extends Activity {
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
        StayRequestStore store = new StayRequestStore(this);
        List<Map<String, String>> requests;
        try {
            requests = store.listAll(200);
        } finally {
            store.close();
        }

        LinearLayout root = TravelUi.page(this);
        root.addView(TravelUi.hero(
                this,
                "Hackathon hotel operations",
                "Guest request command board",
                "Front desk • housekeeping • maintenance • guest experience • transparent revenue opportunities"));

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, String> request : requests) {
            String status = request.getOrDefault("status", "draft");
            counts.put(status, counts.getOrDefault(status, 0) + 1);
        }
        LinearLayout summary = TravelUi.card(this, TravelUi.SKY);
        summary.addView(TravelUi.cardTitle(this, "📊", "Operations snapshot"));
        summary.addView(TravelUi.body(this,
                requests.isEmpty()
                        ? "No guest requests are stored yet. Create one through the Hotel Stay Assistant to populate this demo."
                        : requestSummary(counts, requests.size())));
        root.addView(summary);

        LinearLayout departments = TravelUi.card(this, TravelUi.MINT);
        departments.addView(TravelUi.cardTitle(this, "🧭", "Automatic task routing"));
        departments.addView(TravelUi.body(this,
                "Arrival, checkout and reservation questions route to front desk; towels and cleaning to housekeeping; broken fixtures to maintenance; accessibility requests remain high-priority and require human confirmation. Model text alone never marks work complete."));
        root.addView(departments);

        LinearLayout revenue = TravelUi.card(this, TravelUi.LAVENDER);
        revenue.addView(TravelUi.cardTitle(this, "💡", "Ethical revenue opportunities"));
        revenue.addView(TravelUi.body(this,
                "A property integration could suggest a paid upgrade, breakfast, parking, spa time, early check-in or late checkout only when relevant. Sarah should show the complete price, never hide a required fee, never exploit anxiety or disability, and never say an offer is available without live inventory."));
        root.addView(revenue);

        root.addView(TravelUi.section(this, "Task board"));
        if (requests.isEmpty()) {
            LinearLayout empty = TravelUi.card(this, TravelUi.CREAM);
            empty.addView(TravelUi.primaryButton(this, "Open hotel stay assistant",
                    v -> TravelUi.start(this, StayAssistantActivity.class)));
            root.addView(empty);
        } else {
            for (Map<String, String> request : requests) root.addView(task(request));
        }

        LinearLayout integration = TravelUi.card(this, TravelUi.PEACH);
        integration.addView(TravelUi.cardTitle(this, "🔌", "Property-system integration boundary"));
        integration.addView(TravelUi.body(this,
                "A real deployment needs authenticated PMS, CRM, housekeeping, maintenance, payment and messaging integrations; role-based access; audit history; retries; escalation; privacy controls; and a human takeover path. This phone screen is a safe local demonstration, not a hotel system of record."));
        root.addView(integration);
    }

    private LinearLayout task(Map<String, String> row) {
        String category = row.getOrDefault("category", "general");
        String department = department(category);
        String status = row.getOrDefault("status", "draft");
        LinearLayout card = TravelUi.card(this, color(status));
        card.addView(TravelUi.cardTitle(this, icon(category), row.getOrDefault("title", "Request")));
        card.addView(TravelUi.body(this,
                "Department: " + department
                        + "\nGuest/profile: " + row.getOrDefault("person_name", "Traveler")
                        + "\nHotel: " + value(row.get("hotel_name"), "not selected")
                        + "\nPriority: " + row.getOrDefault("priority", "normal")
                        + "\nStatus: " + status
                        + "\n\n" + row.getOrDefault("detail", "")));
        card.addView(TravelUi.primaryButton(this, "Update task status",
                v -> chooseStatus(row)));
        return card;
    }

    private void chooseStatus(Map<String, String> row) {
        String[] choices = {
                "draft",
                "sent_by_traveler",
                "acknowledged",
                "in_progress",
                "waiting_on_guest",
                "confirmed_by_hotel",
                "completed",
                "unable_to_fulfill"
        };
        new AlertDialog.Builder(this)
                .setTitle("Update demonstration status")
                .setMessage("Only a human or verified property integration should mark hotel work as acknowledged, confirmed or completed.")
                .setItems(choices, (dialog, which) -> {
                    StayRequestStore store = new StayRequestStore(this);
                    try {
                        store.updateStatus(number(row.get("id")), choices[which]);
                    } finally {
                        store.close();
                    }
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String requestSummary(Map<String, Integer> counts, int total) {
        StringBuilder out = new StringBuilder("Total tasks: ").append(total);
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            out.append("\n").append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return out.toString();
    }

    private static String department(String category) {
        if ("housekeeping".equals(category)) return "Housekeeping";
        if ("maintenance".equals(category)) return "Engineering / maintenance";
        if ("accessibility".equals(category)) return "Front desk + accessibility lead";
        if ("arrival".equals(category) || "checkout".equals(category)) return "Front desk";
        if ("room_preference".equals(category)) return "Rooms / front desk";
        return "Guest experience";
    }

    private static String icon(String category) {
        if ("housekeeping".equals(category)) return "🧹";
        if ("maintenance".equals(category)) return "🔧";
        if ("accessibility".equals(category)) return "♿";
        if ("arrival".equals(category)) return "🛎️";
        if ("checkout".equals(category)) return "🕐";
        return "📨";
    }

    private static int color(String status) {
        if ("completed".equals(status) || "confirmed_by_hotel".equals(status)) return TravelUi.MINT;
        if ("in_progress".equals(status) || "acknowledged".equals(status)) return TravelUi.SKY;
        if ("unable_to_fulfill".equals(status)) return TravelUi.PEACH;
        return TravelUi.CREAM;
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static long number(String value) {
        try { return Long.parseLong(value == null ? "0" : value); }
        catch (Exception ignored) { return 0; }
    }
}
