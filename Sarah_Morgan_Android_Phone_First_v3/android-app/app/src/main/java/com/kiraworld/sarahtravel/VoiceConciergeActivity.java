package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Prepares or submits a supervised hotel-contact call request. */
public final class VoiceConciergeActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TravelContextSnapshot trip;
    private EditText hotel;
    private EditText phone;
    private EditText script;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        trip = TravelContextSnapshot.load(this);
        render();
    }

    private void render() {
        LinearLayout root = TravelUi.page(this);
        root.addView(TravelUi.hero(
                this,
                "Voice concierge",
                "Contact a hotel with supervision",
                "Prepare a clear request, let the traveler review it, and never purchase or change a reservation without explicit confirmation."));

        LinearLayout form = TravelUi.card(this, TravelUi.LAVENDER);
        form.addView(TravelUi.cardTitle(this, "📞", "Call details"));
        hotel = field("Hotel name", "", InputType.TYPE_CLASS_TEXT);
        phone = field("Hotel phone number", "", InputType.TYPE_CLASS_PHONE);
        script = field(
                "What Sarah should ask or explain",
                "Hello. I am calling on behalf of " + trip.personName
                        + " about a possible or upcoming stay in " + trip.destination
                        + ". I would like to confirm the following request. Please do not change or charge the reservation without the traveler confirming it.",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        script.setMinLines(7);
        form.addView(hotel);
        form.addView(phone);
        form.addView(script);
        form.addView(TravelUi.outlineButton(this, "Call the hotel myself",
                v -> dial()));
        form.addView(TravelUi.outlineButton(this, "Share or copy the call script",
                v -> shareScript()));
        if (VoiceConciergeConfig.isConfigured()) {
            form.addView(TravelUi.primaryButton(this, "Start supervised voice-agent call",
                    v -> confirmAgentCall()));
        }
        root.addView(form);

        LinearLayout status = TravelUi.card(this, TravelUi.SKY);
        status.addView(TravelUi.cardTitle(this, "🔌", "Integration status"));
        status.addView(TravelUi.body(this,
                VoiceConciergeConfig.isConfigured()
                        ? "The connected voice concierge is available. The traveler still reviews the script and presses the final call button."
                        : "Manual dialing and script sharing work now. The connected voice concierge is not available, and no call is placed automatically."));
        root.addView(status);

        LinearLayout truth = TravelUi.card(this, TravelUi.PEACH);
        truth.addView(TravelUi.cardTitle(this, "🛡️", "Call rules"));
        truth.addView(TravelUi.body(this,
                "The voice agent must identify itself appropriately, follow applicable call-recording and consent laws, avoid pretending to be the traveler, avoid payment-card collection, stop when asked, preserve a transcript or result status according to the privacy policy, and escalate uncertain or sensitive matters to a human."));
        root.addView(truth);
    }

    private void confirmAgentCall() {
        String hotelName = hotel.getText().toString().trim();
        String hotelPhone = phone.getText().toString().trim();
        String callScript = script.getText().toString().trim();
        if (hotelName.isEmpty() || hotelPhone.isEmpty() || callScript.isEmpty()) {
            Toast.makeText(this, "Enter the hotel, phone number and call request.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Start supervised hotel call?")
                .setMessage("Review the number and script first. Sarah will not authorize charges, make purchases, or change a reservation without a separate verified confirmation.")
                .setPositiveButton("Start call request", (dialog, which) -> startAgentCall(
                        hotelName, hotelPhone, callScript))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startAgentCall(String hotelName, String hotelPhone, String callScript) {
        Toast.makeText(this, "Submitting supervised call request…", Toast.LENGTH_LONG).show();
        executor.submit(() -> {
            try {
                VoiceConciergeClient.Result result = VoiceConciergeClient.start(
                        trip.personName, hotelName, hotelPhone, callScript);
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Voice concierge status")
                        .setMessage("Status: " + result.status
                                + (result.callId.isEmpty() ? "" : "\nCall ID: " + result.callId)
                                + (result.summary.isEmpty() ? "" : "\n\n" + result.summary))
                        .setPositiveButton("OK", null)
                        .show());
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "The supervised call could not start: " + safe(error.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private void dial() {
        String value = phone.getText().toString().trim();
        if (value.isEmpty()) {
            Toast.makeText(this, "Enter the hotel's phone number.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(value))));
        } catch (Exception e) {
            Toast.makeText(this, "No phone app could open that number.", Toast.LENGTH_LONG).show();
        }
    }

    private void shareScript() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Hotel request for " + hotel.getText().toString().trim());
        send.putExtra(Intent.EXTRA_TEXT, script.getText().toString());
        startActivity(Intent.createChooser(send, "Share hotel call script"));
    }

    private EditText field(String hint, String value, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setText(value == null ? "" : value);
        field.setInputType(inputType);
        field.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "unknown error" : value.trim();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
