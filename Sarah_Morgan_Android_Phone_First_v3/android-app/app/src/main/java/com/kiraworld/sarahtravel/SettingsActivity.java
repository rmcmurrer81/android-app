package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
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

    public static void ensureAutomaticModeDefault(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_MODE_MIGRATED, false)) {
            p.edit()
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

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
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
        voice.setSelection(p.getInt("voice_mode", 0));

        EditText api = findViewById(R.id.apiKeyInput);
        EditText model = findViewById(R.id.modelInput);
        model.setText(p.getString("model", "gpt-5-mini"));
        CheckBox web = findViewById(R.id.webSearchCheck);
        CheckBox autoSpeak = findViewById(R.id.autoSpeakCheck);
        CheckBox learn = findViewById(R.id.learnCheck);
        SeekBar speed = findViewById(R.id.speedSeek);
        web.setChecked(p.getBoolean("web_search", true));
        autoSpeak.setChecked(p.getBoolean("auto_speak", true));
        learn.setChecked(p.getBoolean("learn", true));
        speed.setProgress(p.getInt("speed", 45));

        Button save = findViewById(R.id.saveSettingsButton);
        save.setOnClickListener(v -> {
            int selectedMode = provider.getSelectedItemPosition();
            String entered = api.getText().toString().trim();
            String savedKey = SecureStore.loadApiKey(this);

            setConversationMode(this, selectedMode);
            p.edit()
                    .putInt("voice_mode", voice.getSelectedItemPosition())
                    .putString("connected_provider", p.getString("connected_provider", "openai"))
                    .putString("model", model.getText().toString().trim().isEmpty()
                            ? "gpt-5-mini"
                            : model.getText().toString().trim())
                    .putBoolean("web_search", web.isChecked())
                    .putBoolean("auto_speak", autoSpeak.isChecked())
                    .putBoolean("learn", learn.isChecked())
                    .putInt("speed", speed.getProgress())
                    .apply();

            if (!entered.isEmpty()) {
                try {
                    SecureStore.saveApiKey(this, entered);
                    savedKey = entered;
                } catch (Exception e) {
                    Toast.makeText(this, "The API key could not be encrypted: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
            }

            if (selectedMode != ConversationModePolicy.MODE_LOCAL_ONLY && savedKey.isEmpty()) {
                Toast.makeText(
                        this,
                        "Automatic mode is saved. Sarah will stay Local until a connected-model key is added.",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Sarah's settings were saved.", Toast.LENGTH_SHORT).show();
            }
            finish();
        });
    }
}
