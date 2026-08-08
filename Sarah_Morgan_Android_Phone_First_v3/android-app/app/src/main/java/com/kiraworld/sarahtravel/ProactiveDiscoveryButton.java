package com.kiraworld.sarahtravel;

import android.content.Context;
import android.util.AttributeSet;

import java.util.Map;

/** Compact profile-isolated entry point for source-bound discoveries. */
public final class ProactiveDiscoveryButton extends androidx.appcompat.widget.AppCompatButton {
    public ProactiveDiscoveryButton(Context context, AttributeSet attributes) {
        super(context, attributes);
        setOnClickListener(view -> TravelUi.start(getContext(), DiscoveryActivity.class));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        SarahDatabase database = new SarahDatabase(getContext());
        PersonProfileStore people = new PersonProfileStore(getContext());
        try {
            Map<String, String> owner = database.getProfile();
            people.ensureOwner(owner);
            Map<String, String> profile = people.getActiveProfile();
            String personId = profile.getOrDefault("person_id", "");
            String name = profile.getOrDefault(
                    "name", owner.getOrDefault("name", "Traveler"));
            ProactiveDiscoveryStore store = new ProactiveDiscoveryStore(getContext());
            try {
                store.claimLegacyProfile(personId, name);
                int count = store.count(personId);
                setText(count > 0
                        ? "\u2728 Discoveries (" + count + ")"
                        : "\u2728 Sarah discoveries");
            } finally {
                store.close();
            }
        } finally {
            people.close();
            database.close();
        }
    }
}
