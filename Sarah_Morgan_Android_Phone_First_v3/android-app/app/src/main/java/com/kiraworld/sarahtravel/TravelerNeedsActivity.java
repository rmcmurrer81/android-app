package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.Map;

/** Lets each active profile describe needs in their own words. */
public final class TravelerNeedsActivity extends Activity {
    private Map<String, String> person;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
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
        TravelerNeedsStore.Needs needs = TravelerNeedsStore.load(this, personId);

        LinearLayout root = TravelUi.page(this);
        root.addView(TravelUi.hero(
                this,
                "Travel that fits the person",
                name + "'s needs and priorities",
                "Sarah should use these when suggesting routes, hotels, schedules, food, events, and places—not treat them as an afterthought."));

        LinearLayout form = TravelUi.card(this, TravelUi.MINT);
        form.addView(TravelUi.cardTitle(this, "♿", "Accessibility and pace"));
        form.addView(TravelUi.body(this,
                "Use plain language. Examples: wheelchair user; avoid stairs; can walk about 20 minutes; need frequent seating; low-noise breaks; captions; step-free transit; avoid flashing lights."));

        EditText mobility = field("Mobility device, step-free, seating, transfers...", needs.mobility);
        EditText walking = field("Walking distance or rest-break limits", needs.walking);
        EditText stairs = field("Stairs, elevators, escalators, platform gaps...", needs.stairs);
        EditText sensory = field("Noise, crowds, lights, touch, quiet breaks...", needs.sensory);
        EditText visionHearing = field("Vision, hearing, captions, audio description...", needs.visionHearing);
        EditText dietary = field("Allergies, dietary needs, food preferences", needs.dietary);
        EditText pace = field("Trip pace: slow, balanced, packed, mornings only...", needs.pace);
        EditText sustainability = field("Green priorities: rail first, transit, EV, fewer flights...", needs.sustainability);
        EditText notes = field("Anything else Sarah should consider", needs.notes);
        form.addView(mobility);
        form.addView(walking);
        form.addView(stairs);
        form.addView(sensory);
        form.addView(visionHearing);
        form.addView(dietary);
        form.addView(pace);
        form.addView(sustainability);
        form.addView(notes);
        form.addView(TravelUi.primaryButton(this, "Save for " + name, v -> {
            TravelerNeedsStore.save(this, personId, new TravelerNeedsStore.Needs(
                    mobility.getText().toString(),
                    walking.getText().toString(),
                    stairs.getText().toString(),
                    sensory.getText().toString(),
                    visionHearing.getText().toString(),
                    dietary.getText().toString(),
                    pace.getText().toString(),
                    sustainability.getText().toString(),
                    notes.getText().toString()));
            Toast.makeText(this, "Saved for " + name + " only.", Toast.LENGTH_LONG).show();
            finish();
        }));
        root.addView(form);

        LinearLayout truth = TravelUi.card(this, TravelUi.SKY);
        truth.addView(TravelUi.cardTitle(this, "✅", "How Sarah should use this"));
        truth.addView(TravelUi.body(this,
                "Sarah can prioritize suitable routes and places, but she must still verify current elevator outages, step-free entrances, accessible rooms, seating, sensory accommodations, food-allergy handling, and local service changes with official sources before the trip."));
        root.addView(truth);
    }

    private EditText field(String hint, String value) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value == null ? "" : value);
        field.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setMinLines(2);
        field.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }
}
