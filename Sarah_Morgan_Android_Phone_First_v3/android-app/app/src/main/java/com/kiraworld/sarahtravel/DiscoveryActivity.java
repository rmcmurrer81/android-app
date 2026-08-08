package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Source-bound discoveries plus a truthful, preflighted manual refresh action. */
public final class DiscoveryActivity extends Activity {
    private Map<String, String> activeProfile;
    private List<Map<String, String>> trips;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(TravelUi.dp(this, 18), TravelUi.dp(this, 18),
                TravelUi.dp(this, 18), TravelUi.dp(this, 30));
        scroll.addView(root);

        TextView heading = new TextView(this);
        heading.setText("Sarah discoveries");
        heading.setTextSize(28);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(heading);

        TextView note = new TextView(this);
        note.setText("Possible matches keep their exact source and research time. A result is not a booking or proof of current availability.");
        note.setPadding(0, TravelUi.dp(this, 10), 0, TravelUi.dp(this, 14));
        root.addView(note);

        SarahDatabase db = new SarahDatabase(this);
        PersonProfileStore people = new PersonProfileStore(this);
        String name;
        String personId;
        try {
            Map<String, String> owner = db.getProfile();
            people.ensureOwner(owner);
            activeProfile = new java.util.HashMap<>(people.getActiveProfile());
            boolean activeOwner = "yes".equals(activeProfile.getOrDefault("is_owner", "no"));
            activeProfile.put("active_speaker_is_owner", activeOwner ? "yes" : "no");
            if (activeOwner && !activeProfile.containsKey("memory_consent")) {
                activeProfile.put("memory_consent", owner.getOrDefault("memory_consent", "no"));
            }
            name = activeProfile.getOrDefault("name", owner.getOrDefault("name", "Traveler"));
            personId = activeProfile.getOrDefault("person_id", "");
            trips = new ArrayList<>(db.listTrips(50));
        } finally {
            people.close();
            db.close();
        }

        ProactiveDiscoveryStore store = new ProactiveDiscoveryStore(this);
        List<Map<String, String>> rows;
        try {
            store.claimLegacyProfile(personId, name);
            rows = store.list(personId, 50);
        } finally {
            store.close();
        }

        String latestReceipt = ProactiveResearchReceiptStore.latest(this, personId);
        if (!latestReceipt.isEmpty()) {
            TextView receipt = new TextView(this);
            receipt.setText(humanReceipt(latestReceipt));
            receipt.setPadding(0, 0, 0, TravelUi.dp(this, 10));
            root.addView(receipt);
        }

        if (rows.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No source-backed discoveries are saved for this profile yet.");
            root.addView(empty);
        } else {
            for (Map<String, String> row : rows) addDiscovery(root, row);
        }

        Button refresh = new Button(this);
        refresh.setAllCaps(false);
        String availability = ProactiveDiscoveryCoordinator.availabilityStatus(this, activeProfile, trips);
        refresh.setText("ready".equals(availability) ? "Research now" : availability);
        refresh.setEnabled("ready".equals(availability));
        refresh.setOnClickListener(v -> {
            String current = ProactiveDiscoveryCoordinator.availabilityStatus(this, activeProfile, trips);
            if (!"ready".equals(current)) {
                refresh.setText(current);
                refresh.setEnabled(false);
                return;
            }
            refresh.setText("Researching now · only verified source results will be saved");
            refresh.setEnabled(false);
            Map<String, String> capturedProfile = new java.util.HashMap<>(activeProfile);
            List<Map<String, String>> capturedTrips = new ArrayList<>(trips);
            new Thread(() -> {
                try {
                    int added = ProactiveDiscoveryCoordinator.refresh(
                            this, capturedProfile, capturedTrips,
                            "owner_requested_immediate");
                    runOnUiThread(() -> {
                        refresh.setText("Research completed · " + added
                                + " new source-backed match" + (added == 1 ? "" : "es"));
                        refresh.setEnabled(true);
                    });
                } catch (Exception failure) {
                    runOnUiThread(() -> {
                        refresh.setText("Research did not complete · no result was claimed");
                        refresh.setEnabled(true);
                    });
                }
            }, "Sarah-Owner-Research").start();
        });
        root.addView(refresh);

        setContentView(scroll);
        SafeAreaInsets.apply(this, scroll, null, scroll);
    }

    private static String humanReceipt(String serialized) {
        try {
            JSONObject receipt = new JSONObject(serialized);
            String status = receipt.optString("status", "UNKNOWN");
            int queries = receipt.optInt("query_count", 0);
            int sources = receipt.optInt("source_result_count", 0);
            int saved = receipt.optInt("saved_count", 0);
            long completedAt = receipt.optLong("completed_at", 0L);
            String time = completedAt > 0
                    ? DateFormat.getDateTimeInstance().format(new Date(completedAt))
                    : "in progress";
            return "Last research: " + status.toLowerCase(Locale.US)
                    + " · " + queries + " bounded quer" + (queries == 1 ? "y" : "ies")
                    + " · " + sources + " source result" + (sources == 1 ? "" : "s")
                    + " · " + saved + " saved · " + time;
        } catch (Exception ignored) {
            return "Last research receipt could not be read; no result is being claimed.";
        }
    }

    private void addDiscovery(LinearLayout root, Map<String, String> row) {
        TextView title = new TextView(this);
        title.setText(row.getOrDefault("title", "Possible match"));
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, TravelUi.dp(this, 18), 0, TravelUi.dp(this, 4));
        root.addView(title);

        long sourceTime = 0L;
        try { sourceTime = Long.parseLong(row.getOrDefault("source_time", "0")); }
        catch (Exception ignored) { }
        String researched = sourceTime > 0
                ? DateFormat.getDateTimeInstance().format(new Date(sourceTime))
                : "time not recorded";
        TextView body = new TextView(this);
        body.setText(row.getOrDefault("summary", "") + "\n\nSource: "
                + row.getOrDefault("source", "not recorded") + "\nResearched: " + researched);
        root.addView(body);

        Button open = new Button(this);
        open.setText("Open source and verify");
        open.setAllCaps(false);
        String url = row.getOrDefault("url", "");
        open.setEnabled(url.startsWith("https://"));
        open.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (Exception ignored) { }
        });
        root.addView(open);
    }
}
