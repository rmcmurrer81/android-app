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
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Toast;

public final class SettingsActivity extends Activity {
    public static final String PREFS = "sarah_settings";
    private static final String KEY_MODE = "conversation_mode";
    private static final String KEY_MODE_MIGRATED = "automatic_mode_migrated_v1";
    private static final String KEY_TEAM_MODEL_MIGRATED = "team_model_config_migrated_v1";
    private static final String KEY_VOICE_MIGRATED = "elevenlabs_voice_migrated_v1";
    private static final int REQ_NOTIFICATIONS = 4401;

    public static void ensureAutomaticModeDefault(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        if (!preferences.getBoolean(KEY_MODE_MIGRATED, false)) {
            editor.putInt(KEY_MODE, ConversationModePolicy.MODE_AUTO)
                    .putBoolean(KEY_MODE_MIGRATED, true);
            changed = true;
        }
        if (!preferences.getBoolean(KEY_TEAM_MODEL_MIGRATED, false)) {
            editor.putString("connected_provider", SarahModelConfig.PROVIDER_ID)
                    .putString("model", SarahModelConfig.MODEL_ID)
                    .putBoolean(KEY_TEAM_MODEL_MIGRATED, true);
            changed = true;
        }
        if (!preferences.getBoolean(KEY_VOICE_MIGRATED, false)) {
            editor.putInt("voice_mode", ElevenLabsVoiceConfig.isConfigured() ? 1 : 0)
                    .putBoolean(KEY_VOICE_MIGRATED, true);
            changed = true;
        }
        if (changed) editor.apply();
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
        Spinner mode = findViewById(R.id.providerSpinner);
        Spinner voice = findViewById(R.id.voiceModeSpinner);
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                "Automatic — OpenAI when included, public lookup or Local fallback otherwise",
                "OpenAI preferred — retry the team connection on each message",
                "Local only — never use the team model or public lookup"
        }));
        voice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                "Android voice only — works without internet",
                "Sarah Morgan ElevenLabs voice — Android fallback if unavailable"
        }));
        mode.setSelection(getConversationMode(this));
        voice.setSelection(preferences.getInt("voice_mode", ElevenLabsVoiceConfig.isConfigured() ? 1 : 0));

        Button voiceProvisioning = findViewById(R.id.voiceProvisioningButton);
        voiceProvisioning.setText(DeviceVoiceProvisioning.isActivated(this)
                ? "ElevenLabs voice activated on this phone"
                : "Activate Sarah Morgan voice on this phone");
        voiceProvisioning.setOnClickListener(v -> TravelUi.start(this, VoiceProvisioningActivity.class));

        CheckBox web = findViewById(R.id.webSearchCheck);
        CheckBox autoResearch = findViewById(R.id.autoResearchCheck);
        CheckBox mediaPreviews = findViewById(R.id.mediaPreviewCheck);
        CheckBox dealAlerts = findViewById(R.id.dealAlertsCheck);
        CheckBox autoSpeak = findViewById(R.id.autoSpeakCheck);
        CheckBox learn = findViewById(R.id.learnCheck);
        SeekBar speed = findViewById(R.id.speedSeek);

        web.setChecked(preferences.getBoolean("web_search", true));
        autoResearch.setChecked(preferences.getBoolean("auto_destination_research", true));
        mediaPreviews.setChecked(preferences.getBoolean("inline_media_previews", true));
        dealAlerts.setChecked(preferences.getBoolean("deal_alerts_enabled", true));
        autoSpeak.setChecked(preferences.getBoolean("auto_speak", true));
        learn.setChecked(preferences.getBoolean("learn", true));
        speed.setProgress(preferences.getInt("speed", 45));

        Button save = findViewById(R.id.saveSettingsButton);
        save.setOnClickListener(v -> {
            int selectedMode = mode.getSelectedItemPosition();
            setConversationMode(this, selectedMode);
            preferences.edit()
                    .putString("connected_provider", SarahModelConfig.PROVIDER_ID)
                    .putString("model", SarahModelConfig.MODEL_ID)
                    .putInt("voice_mode", voice.getSelectedItemPosition())
                    .putBoolean("web_search", web.isChecked())
                    .putBoolean("auto_destination_research", autoResearch.isChecked())
                    .putBoolean("inline_media_previews", mediaPreviews.isChecked())
                    .putBoolean("deal_alerts_enabled", dealAlerts.isChecked())
                    .putBoolean("auto_speak", autoSpeak.isChecked())
                    .putBoolean("learn", learn.isChecked())
                    .putInt("speed", speed.getProgress())
                    .apply();

            if (dealAlerts.isChecked() || autoResearch.isChecked()) {
                DealWatchScheduler.ensureScheduled(this);
                DealWatchScheduler.runSoon(this);
                EventMonitorScheduler.ensureScheduled(this);
                EventMonitorScheduler.runSoon(this);
            } else {
                DealWatchScheduler.cancel(this);
            }

            if (dealAlerts.isChecked()
                    && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                return;
            }
            finishWithMessage();
        });
    }

    private void finishWithMessage() {
        String conversation = SarahModelConfig.fullConversationAvailable()
                ? "OpenAI conversation is included."
                : "Public lookup and Local conversation remain available; the team OpenAI connection is not included.";
        String voice = ElevenLabsVoiceConfig.isConfigured()
                ? ElevenLabsVoiceConfig.statusLabel() + "."
                : DeviceVoiceProvisioning.status(this);
        Toast.makeText(this, "Sarah's settings were saved. " + conversation + " " + voice,
                Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) finishWithMessage();
    }
}
