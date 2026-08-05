package com.kiraworld.sarahtravel;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    public static final String PREFS = "sarah_settings";
    private static final String KEY_MODE = "conversation_mode";
    private static final String KEY_MODE_MIGRATED = "automatic_mode_migrated_v1";
    private static final int REQ_NOTIFICATIONS = 4401;

    public static void ensureAutomaticModeDefault(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!preferences.getBoolean(KEY_MODE_MIGRATED, false)) {
            preferences.edit()
                    .putInt(KEY_MODE, ConversationModePolicy.MODE_AUTO)
                    .putBoolean(KEY_MODE_MIGRATED, true)
                    .apply();
        }
    }

    public static int getConversationMode(Context context) {
        ensureAutomaticModeDefault(context);
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_MODE, ConversationModePolicy.MODE_AUTO);
    }

    public static void setConversationMode(Context context, int mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_MODE, mode)
                .putBoolean(KEY_MODE_MIGRATED, true)
                .apply();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        ensureAutomaticModeDefault(this);

        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        Spinner provider = findViewById(R.id.providerSpinner);
        Spinner voice = findViewById(R.id.voiceModeSpinner);
        provider.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                "Automatic — Smart online, Local when offline (recommended)",
                "Smart preferred — use connected model whenever possible",
                "Local only — never call a connected model"
        }));
        voice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                "Android voice (free)",
                "Sarah cloud voice (uses API)"
        }));
        provider.setSelection(getConversationMode(this));
        voice.setSelection(preferences.getInt("voice_mode", 0));

        EditText api = findViewById(R.id.apiKeyInput);
        EditText model = findViewById(R.id.modelInput);
        EditText backendUrl = findViewById(R.id.dealBackendUrlInput);
        EditText backendToken = findViewById(R.id.dealBackendTokenInput);
        model.setText(preferences.getString("model", "gpt-5-mini"));
        backendUrl.setText(preferences.getString("deal_backend_url", ""));

        CheckBox web = findViewById(R.id.webSearchCheck);
        CheckBox autoResearch = findViewById(R.id.autoResearchCheck);
        CheckBox dealAlerts = findViewById(R.id.dealAlertsCheck);
        CheckBox autoSpeak = findViewById(R.id.autoSpeakCheck);
        CheckBox learn = findViewById(R.id.learnCheck);
        SeekBar speed = findViewById(R.id.speedSeek);
        web.setChecked(preferences.getBoolean("web_search", true));
        autoResearch.setChecked(preferences.getBoolean("auto_destination_research", true));
        dealAlerts.setChecked(preferences.getBoolean("deal_alerts_enabled", true));
        autoSpeak.setChecked(preferences.getBoolean("auto_speak", true));
        learn.setChecked(preferences.getBoolean("learn", true));
        speed.setProgress(preferences.getInt("speed", 45));

        Button save = findViewById(R.id.saveSettingsButton);
        save.setOnClickListener(v -> {
            int selectedMode = provider.getSelectedItemPosition();
            String enteredModelKey = api.getText().toString().trim();
            String enteredBackendToken = backendToken.getText().toString().trim();
            String savedKey = SecureStore.loadApiKey(this);

            setConversationMode(this, selectedMode);
            preferences.edit()
                    .putInt("voice_mode", voice.getSelectedItemPosition())
                    .putString("connected_provider", preferences.getString("connected_provider", "openai"))
                    .putString("model", model.getText().toString().trim().isEmpty()
                            ? "gpt-5-mini"
                            : model.getText().toString().trim())
                    .putString("deal_backend_url", backendUrl.getText().toString().trim())
                    .putBoolean("web_search", web.isChecked())
                    .putBoolean("auto_destination_research", autoResearch.isChecked())
                    .putBoolean("deal_alerts_enabled", dealAlerts.isChecked())
                    .putBoolean("auto_speak", autoSpeak.isChecked())
                    .putBoolean("learn", learn.isChecked())
                    .putInt("speed", speed.getProgress())
                    .apply();

            if (!enteredModelKey.isEmpty()) {
                try {
                    SecureStore.saveApiKey(this, enteredModelKey);
                    savedKey = enteredModelKey;
                } catch (Exception e) {
                    Toast.makeText(this, "The model key could not be encrypted: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
            }
            if (!enteredBackendToken.isEmpty()) {
                try {
                    SecureStore.saveDealBackendToken(this, enteredBackendToken);
                } catch (Exception e) {
                    Toast.makeText(this, "The travel-backend token could not be encrypted: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
            }

            if (dealAlerts.isChecked() || autoResearch.isChecked()) {
                DealWatchScheduler.ensureScheduled(this);
                DealWatchScheduler.runSoon(this);
            } else {
                DealWatchScheduler.cancel(this);
            }

            if (dealAlerts.isChecked()
                    && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                return;
            }
            finishWithMessage(selectedMode, savedKey);
        });
    }

    private void finishWithMessage(int selectedMode, String savedKey) {
        if (selectedMode != ConversationModePolicy.MODE_LOCAL_ONLY && savedKey.isEmpty()) {
            Toast.makeText(
                    this,
                    "Automatic mode is saved. Sarah will stay Local until a connected-model key is added.",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Sarah's settings were saved.", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            finishWithMessage(getConversationMode(this), SecureStore.loadApiKey(this));
        }
    }
}
