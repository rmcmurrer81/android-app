package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

public final class OnboardingActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_onboarding);
        SarahDatabase db = new SarahDatabase(this);
        EditText name = findViewById(R.id.nameInput);
        EditText home = findViewById(R.id.homeInput);
        EditText age = findViewById(R.id.ageInput);
        EditText interests = findViewById(R.id.interestsInput);
        EditText worries = findViewById(R.id.worriesInput);
        CheckBox firstFlight = findViewById(R.id.firstFlightCheck);
        CheckBox consent = findViewById(R.id.memoryConsentCheck);
        Button start = findViewById(R.id.startButton);
        start.setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            String h = home.getText().toString().trim();
            String ageText = age.getText().toString().trim();
            int ageValue;
            try { ageValue = Integer.parseInt(ageText); }
            catch (Exception ignored) { ageValue = 0; }
            if (n.isEmpty() || h.isEmpty() || ageValue < 1 || ageValue > 120) {
                Toast.makeText(this, "Please enter your name, where you are from, and an age from 1 to 120.", Toast.LENGTH_LONG).show();
                return;
            }
            db.saveProfile(n, h, ageValue, firstFlight.isChecked(), interests.getText().toString(), worries.getText().toString(), consent.isChecked());
            if (consent.isChecked()) {
                db.addMemory("profile", "Name: " + n, "First-install onboarding");
                db.addMemory("profile", "From: " + h, "First-install onboarding");
                db.addMemory("profile", "Age: " + ageValue, "First-install onboarding");
                if (firstFlight.isChecked()) db.addMemory("travel_experience", "Flying is new or this may be a first flight", "First-install onboarding");
                if (!interests.getText().toString().trim().isEmpty()) db.addMemory("travel_interest", interests.getText().toString().trim(), "First-install onboarding");
                if (!worries.getText().toString().trim().isEmpty()) db.addMemory("travel_need", worries.getText().toString().trim(), "First-install onboarding");
            }
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
    }
}
