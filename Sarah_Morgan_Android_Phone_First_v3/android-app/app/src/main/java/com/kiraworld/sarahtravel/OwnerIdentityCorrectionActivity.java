package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** One-time repair for v8 installations that stored a relationship label as the owner's name. */
public final class OwnerIdentityCorrectionActivity extends Activity {
    private SarahDatabase database;
    private EditText nameInput;
    private TextView error;
    private Map<String, String> durableCandidate = new LinkedHashMap<>();
    private List<String> placeholderPersonIds = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        database = new SarahDatabase(this);
        SarahLocationStore locations = new SarahLocationStore(this);
        if (!database.isPlaceholderOwner()) {
            if (finishPendingLocationMove(locations, database.getProfile())) openSarah();
            else showMigrationRetry();
            return;
        }
        PersonProfileStore existingProfiles = new PersonProfileStore(this);
        try {
            durableCandidate = existingProfiles.uniqueConfirmedOwnerCandidate();
            placeholderPersonIds = existingProfiles.placeholderProfileIds();
        } finally {
            existingProfiles.close();
        }
        LinkedHashSet<String> resumableIds = new LinkedHashSet<>(locations.pendingOwnerMoveIds());
        resumableIds.addAll(placeholderPersonIds);
        placeholderPersonIds = new ArrayList<>(resumableIds);
        // Persist this marker before ensureOwner can delete the placeholder SQLite rows.
        locations.rememberPendingOwnerMove(placeholderPersonIds);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Let Sarah restore your name");
        title.setTextSize(25f);
        root.addView(title);

        TextView explanation = new TextView(this);
        explanation.setText("An earlier build saved “Phone owner” as a display name. Enter the name Sarah should use. Your existing conversations, memories, trips, wishes, watches, home area, and settings stay in place.");
        explanation.setTextSize(16f);
        explanation.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(explanation);

        nameInput = new EditText(this);
        nameInput.setHint("Your name");
        nameInput.setSingleLine(true);
        nameInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        if (!durableCandidate.isEmpty()) nameInput.setText(durableCandidate.getOrDefault("name", ""));
        root.addView(nameInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        error = new TextView(this);
        error.setTextSize(14f);
        root.addView(error);

        Button save = new Button(this);
        save.setText("Confirm and restore my profile");
        save.setOnClickListener(v -> saveName());
        root.addView(save);
        nameInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveName();
                return true;
            }
            return false;
        });

        setContentView(root);
        SafeAreaInsets.apply(this, root, null, null);
        nameInput.requestFocus();
    }

    private void saveName() {
        String name = nameInput.getText().toString().trim();
        if (!ProfileMigrationPolicy.isConfirmedDisplayName(name)) {
            error.setText("Please enter a person’s name, not “Phone owner” or “Traveler.”");
            return;
        }
        Map<String, String> correction = new LinkedHashMap<>();
        if (!durableCandidate.isEmpty()
                && name.equalsIgnoreCase(durableCandidate.getOrDefault("name", ""))) {
            correction.putAll(durableCandidate);
        } else {
            correction.put("name", name);
            correction.put("age_known", "no");
        }
        Map<String, String> restoredProfile = new LinkedHashMap<>(database.getProfile());
        restoredProfile.putAll(correction);
        PersonProfileStore people = new PersonProfileStore(this);
        Map<String, String> restored;
        try {
            restored = people.ensureOwner(restoredProfile);
        } finally {
            people.close();
        }
        String restoredPersonId = restored.getOrDefault("person_id", "");
        if (!restoredPersonId.matches("[0-9]+")) {
            error.setText("Sarah could not locate the restored profile. Reopen Sarah to resume; no profile was deleted.");
            return;
        }
        SarahLocationStore locations = new SarahLocationStore(this);
        for (String placeholderPersonId : placeholderPersonIds) {
            boolean moved = OwnerProfileDataMigrator.move(
                    this, placeholderPersonId, restoredPersonId, name);
            if (!moved || !locations.markOwnerMoveComplete(placeholderPersonId)) {
                error.setText("Sarah could not finish moving every saved item. Reopen Sarah to retry; the old records and retry marker remain intact.");
                return;
            }
        }
        if (!OwnerProfileDataMigrator.claimLegacyOwnerData(this, restoredPersonId)) {
            error.setText("Sarah could not bind the preserved event and booking records to your confirmed profile. Reopen Sarah to retry; the preserved records remain hidden and unchanged.");
            return;
        }
        MindEventStore mind = new MindEventStore(this);
        try { mind.relabelPlaceholderSpeakers(name); }
        finally { mind.close(); }
        // Commit the primary name last. If the process stops above, the
        // placeholder remains and this explicit repair safely resumes.
        if (!database.reconcilePlaceholderOwner(correction)) {
            error.setText("Sarah could not finish that correction. Reopen Sarah to resume; no profile was deleted.");
            return;
        }
        if (locations.pendingOwnerMoveIds().isEmpty()) locations.clearPendingOwnerMove();
        database.repairPlaceholderOwnerLabels();
        openSarah();
    }

    private boolean finishPendingLocationMove(
            SarahLocationStore locations,
            Map<String, String> primaryProfile) {
        List<String> pendingIds = locations.pendingOwnerMoveIds();
        PersonProfileStore people = new PersonProfileStore(this);
        Map<String, String> confirmed;
        try {
            confirmed = people.findByName(primaryProfile.getOrDefault("name", ""));
        } finally {
            people.close();
        }
        String confirmedId = confirmed.getOrDefault("person_id", "");
        if (!confirmedId.matches("[0-9]+")) return false;
        String confirmedName = confirmed.getOrDefault("name", primaryProfile.getOrDefault("name", ""));
        for (String pendingId : pendingIds) {
            boolean moved = OwnerProfileDataMigrator.move(
                    this, pendingId, confirmedId, confirmedName);
            if (!moved || !locations.markOwnerMoveComplete(pendingId)) return false;
        }
        if (!OwnerProfileDataMigrator.claimLegacyOwnerData(this, confirmedId)) return false;
        if (locations.pendingOwnerMoveIds().isEmpty()) locations.clearPendingOwnerMove();
        return true;
    }

    private void showMigrationRetry() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        TextView message = new TextView(this);
        message.setText("Sarah preserved the earlier profile records but could not finish moving all of them. Close and reopen Sarah to retry; no pending record was cleared.");
        message.setTextSize(17f);
        root.addView(message);
        setContentView(root);
        SafeAreaInsets.apply(this, root, null, null);
    }

    private void openSarah() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    @Override protected void onDestroy() {
        if (database != null) database.close();
        super.onDestroy();
    }
}
