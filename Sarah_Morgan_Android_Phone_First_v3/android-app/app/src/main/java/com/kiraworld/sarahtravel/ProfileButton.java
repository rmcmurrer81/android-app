package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageButton;
import android.widget.Toast;

import java.util.List;
import java.util.Map;

/** Visible profile switch for families and other shared-phone users. */
public final class ProfileButton extends ImageButton {
    public ProfileButton(Context context) {
        super(context);
        initialize();
    }

    public ProfileButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public ProfileButton(Context context, AttributeSet attrs, int style) {
        super(context, attrs, style);
        initialize();
    }

    private void initialize() {
        setContentDescription("Switch the person talking with Sarah");
        setImageResource(android.R.drawable.ic_menu_myplaces);
        setOnClickListener(v -> showProfiles());
    }

    private void showProfiles() {
        Context context = getContext();
        if (!(context instanceof Activity)) return;
        SarahDatabase ownerDb = new SarahDatabase(context.getApplicationContext());
        Map<String, String> owner;
        try {
            owner = ownerDb.getProfile();
        } finally {
            ownerDb.close();
        }

        PersonProfileStore people = new PersonProfileStore(context.getApplicationContext());
        List<Map<String, String>> profiles;
        try {
            people.ensureOwner(owner);
            profiles = people.listProfiles();
        } finally {
            people.close();
        }

        String[] labels = new String[profiles.size() + 1];
        for (int i = 0; i < profiles.size(); i++) {
            Map<String, String> profile = profiles.get(i);
            String age = "yes".equals(profile.get("age_known"))
                    ? ", age " + profile.get("age") : ", age not set";
            String ownerLabel = "yes".equals(profile.get("is_owner")) ? " — phone owner" : "";
            String active = "yes".equals(profile.get("active")) ? " ✓" : "";
            labels[i] = profile.get("name") + age + ownerLabel + active;
        }
        labels[labels.length - 1] = "Someone new is using the phone";

        new AlertDialog.Builder(context)
                .setTitle("Who is talking with Sarah?")
                .setMessage("Each saved person has a separate age, interests, memory permission, and trip participation. Sarah does not merge profiles.")
                .setItems(labels, (dialog, which) -> {
                    if (which == profiles.size()) {
                        Toast.makeText(
                                context,
                                "The new person can say “My name is …” in chat. Sarah will create a separate profile and ask their age.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    String name = profiles.get(which).get("name");
                    PersonProfileStore store = new PersonProfileStore(context.getApplicationContext());
                    try {
                        store.setActiveByName(name);
                    } finally {
                        store.close();
                    }
                    Toast.makeText(context, "Sarah is now talking with " + name + ".", Toast.LENGTH_SHORT).show();
                    ((Activity) context).recreate();
                })
                .setNegativeButton("Close", null)
                .show();
    }
}
