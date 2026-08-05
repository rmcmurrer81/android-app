package com.kiraworld.sarahtravel;

import android.app.Activity;
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

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        Spinner provider = findViewById(R.id.providerSpinner);
        Spinner voice = findViewById(R.id.voiceModeSpinner);
        provider.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Offline companion (no account needed)", "OpenAI Responses + web search (personal key)"}));
        voice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Android voice (free)", "Sarah cloud voice (uses API)"}));
        provider.setSelection(p.getInt("provider", 0));
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
            p.edit()
                    .putInt("provider", provider.getSelectedItemPosition())
                    .putInt("voice_mode", voice.getSelectedItemPosition())
                    .putString("model", model.getText().toString().trim().isEmpty() ? "gpt-5-mini" : model.getText().toString().trim())
                    .putBoolean("web_search", web.isChecked())
                    .putBoolean("auto_speak", autoSpeak.isChecked())
                    .putBoolean("learn", learn.isChecked())
                    .putInt("speed", speed.getProgress())
                    .apply();
            String entered = api.getText().toString().trim();
            if (!entered.isEmpty()) {
                try { SecureStore.saveApiKey(this, entered); }
                catch (Exception e) { Toast.makeText(this, "The API key could not be encrypted: " + e.getMessage(), Toast.LENGTH_LONG).show(); return; }
            }
            Toast.makeText(this, "Sarah's settings were saved.", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
