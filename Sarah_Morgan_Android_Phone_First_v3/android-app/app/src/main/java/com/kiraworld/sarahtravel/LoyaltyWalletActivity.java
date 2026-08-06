package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.List;
import java.util.Map;

/** Program IDs and status context without passwords or automatic account access. */
public final class LoyaltyWalletActivity extends Activity {
    private Map<String, String> person;

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
        PersonProfileStore people = new PersonProfileStore(this);
        try {
            person = people.getActiveProfile();
        } finally {
            people.close();
        }
        String personId = person.getOrDefault("person_id", "1");
        String name = person.getOrDefault("name", "Traveler");

        LinearLayout root = TravelUi.page(this);
        root.addView(TravelUi.hero(
                this,
                "Private local wallet",
                name + "'s loyalty programs",
                "Program names, membership identifiers and tiers are encrypted on this phone. Passwords do not belong here."));

        LinearLayout safety = TravelUi.card(this, TravelUi.PEACH);
        safety.addView(TravelUi.cardTitle(this, "🔐", "What Sarah stores"));
        safety.addView(TravelUi.body(this,
                "Store a program name, optional member identifier, tier, website and notes. Do not store passwords, recovery codes, full payment-card numbers, or answers to security questions."));
        safety.addView(TravelUi.primaryButton(this, "Add a loyalty program", v -> addProgram()));
        root.addView(safety);

        root.addView(TravelUi.section(this, "Saved programs"));
        List<LoyaltyVaultStore.Entry> entries = LoyaltyVaultStore.list(this, personId);
        if (entries.isEmpty()) {
            LinearLayout empty = TravelUi.card(this, TravelUi.SKY);
            empty.addView(TravelUi.cardTitle(this, "🎁", "Nothing saved yet"));
            empty.addView(TravelUi.body(this,
                    "Examples include hotel programs, airline miles, Amtrak Guest Rewards, car-rental programs, Rove Miles, or a travel credit-card program."));
            root.addView(empty);
            return;
        }

        for (LoyaltyVaultStore.Entry entry : entries) {
            LinearLayout card = TravelUi.card(this, TravelUi.LAVENDER);
            card.addView(TravelUi.cardTitle(this, "🎫", entry.program));
            String detail = (entry.kind.isEmpty() ? "" : "Type: " + entry.kind)
                    + (entry.tier.isEmpty() ? "" : "\nTier: " + entry.tier)
                    + (entry.memberId.isEmpty() ? "" : "\nMember ID: " + mask(entry.memberId))
                    + (entry.notes.isEmpty() ? "" : "\nNotes: " + entry.notes);
            card.addView(TravelUi.body(this, detail.isEmpty() ? "Saved loyalty program" : detail));
            if (!entry.website.isEmpty()) {
                card.addView(TravelUi.outlineButton(this, "Open program website",
                        v -> TravelUi.open(this, entry.website)));
            }
            card.addView(TravelUi.outlineButton(this, "Remove from this profile",
                    v -> confirmRemove(personId, entry)));
            root.addView(card);
        }
    }

    private void addProgram() {
        String personId = person.getOrDefault("person_id", "1");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = TravelUi.dp(this, 18);
        box.setPadding(padding, padding / 2, padding, 0);

        EditText program = field("Program name, for example Hilton Honors", InputType.TYPE_CLASS_TEXT);
        EditText kind = field("Type: hotel, airline, rail, car, rewards...", InputType.TYPE_CLASS_TEXT);
        EditText memberId = field("Member ID (optional — never a password)", InputType.TYPE_CLASS_TEXT);
        EditText tier = field("Tier or status (optional)", InputType.TYPE_CLASS_TEXT);
        EditText website = field("Official website URL (optional)", InputType.TYPE_TEXT_VARIATION_URI);
        EditText notes = field("Notes: benefits, expiry, direct-booking rule...", InputType.TYPE_CLASS_TEXT);
        box.addView(program);
        box.addView(kind);
        box.addView(memberId);
        box.addView(tier);
        box.addView(website);
        box.addView(notes);

        new AlertDialog.Builder(this)
                .setTitle("Add loyalty program")
                .setMessage("Sarah stores this for the active profile only.")
                .setView(box)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = program.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "Enter the program name.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    String url = website.getText().toString().trim();
                    if (!url.isEmpty() && !url.startsWith("https://")) url = "https://" + url;
                    LoyaltyVaultStore.add(
                            this,
                            personId,
                            name,
                            kind.getText().toString(),
                            memberId.getText().toString(),
                            tier.getText().toString(),
                            url,
                            notes.getText().toString());
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmRemove(String personId, LoyaltyVaultStore.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + entry.program + "?")
                .setMessage("This removes the encrypted record from the active profile. It does not change the real loyalty account.")
                .setPositiveButton("Remove", (dialog, which) -> {
                    LoyaltyVaultStore.remove(this, personId, entry.id);
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText field(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setInputType(inputType | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private static String mask(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() <= 4) return clean;
        return "••••" + clean.substring(clean.length() - 4);
    }
}
