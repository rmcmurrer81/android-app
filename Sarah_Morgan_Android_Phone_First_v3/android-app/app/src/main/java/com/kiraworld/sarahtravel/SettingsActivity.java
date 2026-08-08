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
import android.widget.TextView;
import android.widget.Toast;

import java.util.Map;

public final class SettingsActivity extends Activity {
    public static final String PREFS = "sarah_settings";
    private static final String KEY_MODE = "conversation_mode";
    private static final String KEY_MODE_MIGRATED = "automatic_mode_migrated_v1";
    private static final String KEY_TEAM_MODEL_MIGRATED = "team_model_config_migrated_v1";
    private static final String KEY_VOICE_MIGRATED = "elevenlabs_voice_migrated_v1";
    private static final String KEY_ELEVENLABS_BECAME_AVAILABLE = "elevenlabs_became_available_v2";
    private static final int REQ_NOTIFICATIONS = 4401;
    private static final int REQ_LOCATION = 4402;
    private ApproximateLocationCoordinator locationCoordinator;
    private SarahLocationStore locationStore;
    private TextView locationStatus;
    private android.widget.EditText nearbyArea;
    private String activePersonId = "unknown_profile";

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
        if (ElevenLabsVoiceConfig.isConfigured()
                && !preferences.getBoolean(KEY_ELEVENLABS_BECAME_AVAILABLE, false)) {
            editor.putInt("voice_mode", 1)
                    .putBoolean(KEY_ELEVENLABS_BECAME_AVAILABLE, true);
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
        SafeAreaInsets.apply(this, findViewById(R.id.settingsScroll), null, null);

        TextView buildVersion = findViewById(R.id.buildVersionText);
        buildVersion.setText("Sarah Travel OS " + BuildConfig.VERSION_NAME);
        TextView buildDetails = findViewById(R.id.buildDetailsText);
        String buildCommit = BuildConfig.SARAH_BUILD_COMMIT == null
                ? "" : BuildConfig.SARAH_BUILD_COMMIT.trim();
        String buildIdentity = buildCommit.isEmpty()
                ? "local/unbound build"
                : "source " + buildCommit.substring(0, Math.min(12, buildCommit.length()));
        buildDetails.setText("Version " + BuildConfig.VERSION_NAME + " · " + buildIdentity);

        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        locationStore = new SarahLocationStore(this);
        locationCoordinator = new ApproximateLocationCoordinator(this);
        activePersonId = activePersonId();
        Spinner mode = findViewById(R.id.providerSpinner);
        Spinner voice = findViewById(R.id.voiceModeSpinner);
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                "Automatic — use the connected mind when available",
                "Connected preferred — retry the connection each message",
                "Offline only — use saved knowledge and on-device tools"
        }));
        voice.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{
                "Phone voice only — works without internet",
                "Sarah’s natural online voice — phone voice fallback"
        }));
        mode.setSelection(getConversationMode(this));
        voice.setSelection(preferences.getInt("voice_mode", ElevenLabsVoiceConfig.isConfigured() ? 1 : 0));
        TextView voiceStatus = findViewById(R.id.voiceRouteStatus);
        voiceStatus.setText(ElevenLabsVoiceConfig.isConfigured()
                ? "ElevenLabs Sarah voice ready · " + ElevenLabsVoiceConfig.humanModelLabel()
                    + ". The phone’s offline voice is the automatic fallback, and text never waits for audio."
                : "The phone’s offline voice is ready. Sarah’s natural online voice is not connected in this build, and text remains available.");

        CheckBox web = findViewById(R.id.webSearchCheck);
        CheckBox autoResearch = findViewById(R.id.autoResearchCheck);
        CheckBox nearbyDiscoveries = findViewById(R.id.nearbyDiscoveryCheck);
        nearbyArea = findViewById(R.id.nearbyAreaInput);
        CheckBox mediaPreviews = findViewById(R.id.mediaPreviewCheck);
        CheckBox dealAlerts = findViewById(R.id.dealAlertsCheck);
        CheckBox autoSpeak = findViewById(R.id.autoSpeakCheck);
        CheckBox learn = findViewById(R.id.learnCheck);
        CheckBox autoDeviceSync = findViewById(R.id.autoDeviceSyncCheck);
        SeekBar speed = findViewById(R.id.speedSeek);

        web.setChecked(preferences.getBoolean("web_search", true));
        autoResearch.setChecked(locationStore.backgroundResearchEnabled(activePersonId));
        Map<String, String> settingsProfile = activeProfile();
        final boolean researchOwner = "yes".equals(
                settingsProfile.getOrDefault("is_owner", "no"));
        final boolean researchMemoryConsent = "yes".equals(
                settingsProfile.getOrDefault("memory_consent", "no"));
        final boolean researchConversationConfigured =
                SarahModelConfig.fullConversationAvailable();
        final boolean researchSourceConfigured = TavilyClient.configured();
        Runnable refreshResearchAvailability = () -> {
            boolean validatedInternet = hasValidatedInternet();
            boolean localOnly = mode.getSelectedItemPosition()
                    == ConversationModePolicy.MODE_LOCAL_ONLY;
            boolean canEnable = KnowledgePackSchedulingPolicy.settingsCanEnable(
                    researchOwner,
                    researchMemoryConsent,
                    validatedInternet,
                    researchConversationConfigured,
                    researchSourceConfigured,
                    web.isChecked(),
                    localOnly);
            if (!canEnable) {
                autoResearch.setChecked(false);
                locationStore.setBackgroundResearchEnabled(activePersonId, false);
            }
            autoResearch.setEnabled(canEnable);
            autoResearch.setText(KnowledgePackSchedulingPolicy.settingsLabel(
                    researchOwner,
                    researchMemoryConsent,
                    validatedInternet,
                    researchConversationConfigured,
                    researchSourceConfigured,
                    web.isChecked(),
                    localOnly));
        };
        refreshResearchAvailability.run();
        web.setOnCheckedChangeListener((button, checked) ->
                refreshResearchAvailability.run());
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(
                    android.widget.AdapterView<?> parent,
                    android.view.View view,
                    int position,
                    long id) {
                refreshResearchAvailability.run();
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {
                refreshResearchAvailability.run();
            }
        });
        nearbyDiscoveries.setChecked(locationStore.nearbyEnabled(activePersonId));
        mediaPreviews.setChecked(preferences.getBoolean("inline_media_previews", true));
        dealAlerts.setChecked(preferences.getBoolean(
                "deal_alerts_enabled",
                BackgroundResearchPolicy.DEFAULT_BACKGROUND_MONITORING_ENABLED));
        autoSpeak.setChecked(preferences.getBoolean("auto_speak", true));
        learn.setChecked(preferences.getBoolean("learn", true));
        boolean hasPairedDevice = TrustedDeviceStore.hasPeers(this);
        boolean syncAvailable = TrustedSyncClient.isTransportAccepted() && hasPairedDevice;
        autoDeviceSync.setEnabled(syncAvailable);
        autoDeviceSync.setChecked(syncAvailable
                && preferences.getBoolean("auto_device_sync", true));
        if (!TrustedSyncClient.isTransportAccepted()) {
            autoDeviceSync.setText("Device sync is off - secure transport setup required");
        } else if (!hasPairedDevice) {
            autoDeviceSync.setText("Device sync is off · pair and approve a computer first");
        }
        speed.setProgress(preferences.getInt("speed", 45));

        locationStatus = findViewById(R.id.currentLocationStatus);
        showSavedLocation();
        findViewById(R.id.useCurrentLocationButton).setOnClickListener(v -> useCurrentLocation());

        boolean monitoringConfigured = TravelDealGateway.isConfigured(this)
                || MobilityGateway.isConfigured(this);
        if (!monitoringConfigured) {
            dealAlerts.setChecked(false);
            dealAlerts.setEnabled(false);
            dealAlerts.setText("Automatic travel monitoring · setup required");
        } else {
            dealAlerts.setText("Automatic travel monitoring");
        }

        Button save = findViewById(R.id.saveSettingsButton);
        save.setOnClickListener(v -> {
            int selectedMode = mode.getSelectedItemPosition();
            Map<String, String> saveProfile = activeProfile();
            boolean researchEnabled = KnowledgePackSchedulingPolicy.persistEnabled(
                    autoResearch.isChecked(),
                    "yes".equals(saveProfile.getOrDefault("is_owner", "no")),
                    "yes".equals(saveProfile.getOrDefault("memory_consent", "no")),
                    hasValidatedInternet(),
                    SarahModelConfig.fullConversationAvailable(),
                    TavilyClient.configured(),
                    web.isChecked(),
                    selectedMode == ConversationModePolicy.MODE_LOCAL_ONLY);
            boolean monitoringEnabled = BackgroundResearchPolicy.monitoringCanRun(
                    dealAlerts.isChecked(),
                    monitoringConfigured,
                    true);
            setConversationMode(this, selectedMode);
            preferences.edit()
                    .putString("connected_provider", SarahModelConfig.PROVIDER_ID)
                    .putString("model", SarahModelConfig.MODEL_ID)
                    .putInt("voice_mode", voice.getSelectedItemPosition())
                    .putBoolean("web_search", web.isChecked())
                    .putBoolean("inline_media_previews", mediaPreviews.isChecked())
                    .putBoolean("deal_alerts_enabled", monitoringEnabled)
                    .putBoolean("auto_speak", autoSpeak.isChecked())
                    .putBoolean("learn", learn.isChecked())
                    .putBoolean("auto_device_sync", autoDeviceSync.isChecked())
                    .putInt("speed", speed.getProgress())
                    .apply();
            locationStore.setBackgroundResearchEnabled(activePersonId, researchEnabled);
            if (!researchEnabled) autoResearch.setChecked(false);
            String area = nearbyArea.getText().toString().trim();
            locationStore.setNearbyEnabled(activePersonId, nearbyDiscoveries.isChecked());
            if (area.isEmpty()) locationStore.clear(activePersonId);
            else {
                String existingArea = locationStore.freshArea(
                        activePersonId, System.currentTimeMillis());
                boolean unchangedDeviceArea = area.equals(existingArea)
                        && CurrentLocationPolicy.SOURCE_DEVICE_RESOLVED.equals(
                                locationStore.source(activePersonId));
                if (!unchangedDeviceArea) {
                    locationStore.save(
                            activePersonId,
                            area,
                            System.currentTimeMillis(),
                            CurrentLocationPolicy.SOURCE_MANUAL);
                }
            }

            boolean researchRunnable = researchEnabled;
            boolean monitoringRunnable = monitoringEnabled;
            if (monitoringRunnable || researchRunnable) {
                DealWatchScheduler.ensureScheduled(this);
                DealWatchScheduler.runSoon(this);
            } else {
                DealWatchScheduler.cancel(this);
            }
            if (monitoringRunnable) {
                EventMonitorScheduler.ensureScheduled(this);
                EventMonitorScheduler.runSoon(this);
            }
            if (researchRunnable) {
                ProactiveDiscoveryScheduler.ensureScheduled(this);
                ProactiveDiscoveryScheduler.runSoon(this);
            } else {
                ProactiveDiscoveryScheduler.cancel(this);
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
                ? "The connected conversation is available."
                : "Saved knowledge and on-device conversation remain available; the connected mind is not included.";
        String voice = ElevenLabsVoiceConfig.isConfigured()
                ? "Sarah’s natural online voice is available with automatic phone-voice fallback."
                : "Sarah uses the phone’s offline voice.";
        Toast.makeText(this, "Sarah's settings were saved. " + conversation + " " + voice,
                Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) finishWithMessage();
        if (requestCode == REQ_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                resolveCurrentLocation();
            } else {
                locationStatus.setText(CurrentLocationPolicy.unavailableReply("permission_denied"));
            }
        }
    }

    private void useCurrentLocation() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean("coarse_location_permission_asked", true)
                .apply();
        if (!locationCoordinator.hasPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        resolveCurrentLocation();
    }

    private void resolveCurrentLocation() {
        locationStatus.setText("Finding an approximate city/area…");
        locationCoordinator.resolve(new ApproximateLocationCoordinator.Callback() {
            @Override public void onResolved(String area, long capturedAt) {
                locationStore.save(
                        activePersonId,
                        area,
                        capturedAt,
                        CurrentLocationPolicy.SOURCE_DEVICE_RESOLVED);
                nearbyArea.setText(area);
                locationStatus.setText(CurrentLocationPolicy.settingsStatus(
                        area, CurrentLocationPolicy.SOURCE_DEVICE_RESOLVED));
            }

            @Override public void onUnavailable(String reason) {
                locationStatus.setText(CurrentLocationPolicy.unavailableReply(reason));
            }
        });
    }

    private void showSavedLocation() {
        String area = locationStore.freshArea(activePersonId, System.currentTimeMillis());
        if (!area.isEmpty()) {
            nearbyArea.setText(area);
            locationStatus.setText(CurrentLocationPolicy.settingsStatus(
                    area, locationStore.source(activePersonId)));
        }
    }

    private String activePersonId() {
        Map<String, String> active = activeProfile();
        return active.getOrDefault("person_id", active.getOrDefault("name", "unknown_profile"));
    }

    private Map<String, String> activeProfile() {
        SarahDatabase ownerDb = new SarahDatabase(this);
        PersonProfileStore people = new PersonProfileStore(this);
        try {
            people.ensureOwner(ownerDb.getProfile());
            return people.getActiveProfile();
        } finally {
            people.close();
            ownerDb.close();
        }
    }

    private boolean hasValidatedInternet() {
        android.net.ConnectivityManager manager =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        android.net.Network network = manager == null ? null : manager.getActiveNetwork();
        android.net.NetworkCapabilities capabilities = network == null
                ? null : manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    @Override protected void onDestroy() {
        if (locationCoordinator != null) locationCoordinator.close();
        super.onDestroy();
    }
}
