package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;

/** Owner-only setup screen for device-bound ElevenLabs voice activation. */
public final class VoiceProvisioningActivity extends Activity {
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
        LinearLayout root = TravelUi.page(this);
        Map<String, String> active = activeProfile();
        boolean owner = "yes".equals(active.getOrDefault("is_owner", "no"));

        root.addView(TravelUi.hero(
                this,
                "Sarah's voice",
                "Secure phone activation",
                active.getOrDefault("name", "Phone owner")));

        if (!owner) {
            LinearLayout denied = TravelUi.card(this, TravelUi.PEACH);
            denied.addView(TravelUi.cardTitle(this, "🔒", "Phone owner only"));
            denied.addView(TravelUi.body(this,
                    "Voice-service activation is hidden from child and guest profiles. Switch to the phone owner's profile first."));
            denied.addView(TravelUi.primaryButton(this, "Close", v -> finish()));
            root.addView(denied);
            return;
        }

        LinearLayout status = TravelUi.card(this,
                DeviceVoiceProvisioning.isActivated(this) ? TravelUi.MINT : TravelUi.SKY);
        status.addView(TravelUi.cardTitle(this,
                DeviceVoiceProvisioning.isActivated(this) ? "✅" : "🔐",
                DeviceVoiceProvisioning.isActivated(this)
                        ? "ElevenLabs voice activated"
                        : "One-time secure setup"));
        status.addView(TravelUi.body(this, DeviceVoiceProvisioning.status(this)));
        status.addView(TravelUi.body(this,
                "Device fingerprint: " + DeviceVoiceProvisioning.deviceFingerprint(this)));
        root.addView(status);

        if (!DeviceVoiceProvisioning.isActivated(this)) {
            LinearLayout instructions = TravelUi.card(this, TravelUi.LAVENDER);
            instructions.addView(TravelUi.cardTitle(this, "1", "Copy this phone's setup code"));
            instructions.addView(TravelUi.body(this,
                    "This code contains only a public encryption key. It cannot spend ElevenLabs credits and it does not reveal the private key stored in Android Keystore."));

            TextView code = new TextView(this);
            code.setText(setupCode());
            code.setTextIsSelectable(true);
            code.setTextSize(11f);
            int padding = TravelUi.dp(this, 12);
            code.setPadding(padding, padding, padding, padding);
            code.setBackgroundColor(0xfff7f4ef);
            instructions.addView(code);
            instructions.addView(TravelUi.primaryButton(this, "Copy setup code",
                    v -> copy(code.getText().toString())));
            instructions.addView(TravelUi.outlineButton(this, "Share setup code",
                    v -> share(code.getText().toString())));
            root.addView(instructions);

            LinearLayout next = TravelUi.card(this, TravelUi.CREAM);
            next.addView(TravelUi.cardTitle(this, "2", "Keep Sarah installed"));
            next.addView(TravelUi.body(this,
                    "Send only the setup code to the developer. A later GitHub APK will contain an encrypted activation that only this phone can open. Install that APK over this one. Do not uninstall Sarah between the setup and activation builds, because uninstalling removes the phone's private key."));
            root.addView(next);
        } else {
            LinearLayout ready = TravelUi.card(this, TravelUi.MINT);
            ready.addView(TravelUi.cardTitle(this, "🎙️", "Ready to test"));
            ready.addView(TravelUi.body(this,
                    "Select Sarah Morgan ElevenLabs voice in Settings, keep Read Sarah's replies aloud enabled, and ask Sarah to speak a sentence with a date, fare, and place name. Android speech remains the automatic offline fallback."));
            ready.addView(TravelUi.primaryButton(this, "Open Sarah settings",
                    v -> TravelUi.start(this, SettingsActivity.class)));
            root.addView(ready);
        }

        LinearLayout security = TravelUi.card(this, TravelUi.PEACH);
        security.addView(TravelUi.cardTitle(this, "🛡️", "Private hackathon boundary"));
        security.addView(TravelUi.body(this,
                "The final public product should use an authenticated voice backend and temporary client tokens. This device-bound method is for a restricted, credit-limited hackathon key on one review phone. The raw key is never committed to the public repository."));
        root.addView(security);
    }

    private Map<String, String> activeProfile() {
        PersonProfileStore people = new PersonProfileStore(getApplicationContext());
        try {
            return people.getActiveProfile();
        } finally {
            people.close();
        }
    }

    private String setupCode() {
        try {
            return DeviceVoiceProvisioning.setupCode(this);
        } catch (Exception e) {
            return "Setup code unavailable: " + e.getMessage();
        }
    }

    private void copy(String value) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Sarah voice setup code", value));
        Toast.makeText(this, "Sarah's public setup code was copied.", Toast.LENGTH_LONG).show();
    }

    private void share(String value) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Sarah Morgan phone voice setup code");
        intent.putExtra(Intent.EXTRA_TEXT, value);
        startActivity(Intent.createChooser(intent, "Share Sarah's public setup code"));
    }
}
