package com.kiraworld.sarahtravel;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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

import java.util.List;
import java.util.Map;

public final class SettingsActivity extends Activity {
    public static final String EXTRA_OPEN_ONLINE_ACCESS =
            "com.kiraworld.sarahtravel.OPEN_ONLINE_ACCESS";
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
    private TextView gmailConnectionStatus;
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
            editor.putInt("voice_mode", approvedOnlineVoiceReady(context) ? 1 : 0)
                    .putBoolean(KEY_VOICE_MIGRATED, true);
            changed = true;
        }
        if (approvedOnlineVoiceReady(context)
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
        TextView buildProduct = findViewById(R.id.buildProductText);
        buildProduct.setText("Sarah Travel OS " + BuildConfig.VERSION_NAME);
        TextView buildDetails = findViewById(R.id.buildDetailsText);
        String buildCommit = BuildConfig.SARAH_BUILD_COMMIT == null
                ? "" : BuildConfig.SARAH_BUILD_COMMIT.trim();
        String buildIdentity = buildCommit.isEmpty()
                ? "local/unbound build"
                : "source " + buildCommit.substring(0, Math.min(12, buildCommit.length()));
        EventTripPreUpgradeBackupGate.Result upgradeState =
                SarahApplication.eventTripUpgradeState();
        String recoveryStatus = upgradeState == null
                ? "not checked in this process"
                : upgradeState.status
                    + (upgradeState.manifestSha256.isEmpty()
                        ? "" : "\nR1 backup manifest SHA-256: "
                            + upgradeState.manifestSha256);
        buildDetails.setText("Version " + BuildConfig.VERSION_NAME + " · " + buildIdentity);
        buildDetails.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("About this build")
                .setMessage("Sarah Travel OS " + BuildConfig.VERSION_NAME
                        + "\nBuild: " + buildIdentity
                        + "\nOnline provider: " + SarahModelConfig.providerLabel()
                        + "\nModel requested by this build: " + SarahModelConfig.modelLabel()
                        + "\nProtected route: "
                        + (ProtectedBackendCapabilities.conversationReady(this)
                            ? "contract verified" : "not currently verified")
                        + "\nEvent-trip upgrade safety: " + recoveryStatus)
                .setPositiveButton("Close", null)
                .show());

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
        voice.setSelection(preferences.getInt("voice_mode", approvedOnlineVoiceReady(this) ? 1 : 0));
        TextView voiceStatus = findViewById(R.id.voiceRouteStatus);
        updateVoiceStatus(voiceStatus);

        gmailConnectionStatus = findViewById(R.id.gmailConnectionStatus);
        Button manageGmail = findViewById(R.id.manageGmailConnectionButton);
        String gmailProfileId = EventTripStore.activePersonId(this);
        refreshGmailConnectionStatus();
        manageGmail.setEnabled(!gmailProfileId.isEmpty());
        manageGmail.setOnClickListener(v -> startActivity(
                new Intent(this, GmailAuthorizationActivity.class)));

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
        TextView onlineMindStatus = findViewById(R.id.onlineMindAccessStatus);
        Button configureOnlineMind = findViewById(R.id.configureOnlineMindAccessButton);
        boolean confirmedOwnerCanConfigure = ConfirmedOwnerLease.capture(this) != null;
        configureOnlineMind.setEnabled(confirmedOwnerCanConfigure);
        updateOnlineMindAccessStatus(onlineMindStatus, confirmedOwnerCanConfigure);
        Runnable refreshResearchAvailability = () -> {
            boolean validatedInternet = hasValidatedInternet();
            boolean localOnly = mode.getSelectedItemPosition()
                    == ConversationModePolicy.MODE_LOCAL_ONLY;
            boolean canEnable = KnowledgePackSchedulingPolicy.settingsCanEnable(
                    researchOwner,
                    researchMemoryConsent,
                    validatedInternet,
                    SarahModelConfig.fullConversationAvailable(),
                    TavilyClient.configured(),
                    web.isChecked(),
                    localOnly);
            // Availability is not the owner's durable choice. A temporary
            // capability, network, or source failure must fail closed for
            // execution without silently erasing an earlier opt-in merely
            // because Settings was opened. Keeping the control available to
            // an eligible profile also lets the owner explicitly revoke it.
            autoResearch.setEnabled(researchOwner && researchMemoryConsent);
            String researchLabel = KnowledgePackSchedulingPolicy.settingsLabel(
                    researchOwner,
                    researchMemoryConsent,
                    validatedInternet,
                    SarahModelConfig.fullConversationAvailable(),
                    TavilyClient.configured(),
                    web.isChecked(),
                    localOnly);
            if (autoResearch.isChecked() && !canEnable) {
                researchLabel += " (your saved opt-in is preserved, but no background work can run now)";
            }
            autoResearch.setText(researchLabel);
        };
        refreshResearchAvailability.run();
        configureOnlineMind.setOnClickListener(v -> showOnlineMindAccessDialog(
                onlineMindStatus,
                voiceStatus,
                refreshResearchAvailability));
        if (getIntent().getBooleanExtra(EXTRA_OPEN_ONLINE_ACCESS, false)
                && confirmedOwnerCanConfigure
                && !SecureStore.hasSarahBackendAccess(this)) {
            configureOnlineMind.post(configureOnlineMind::performClick);
        }
        ProtectedBackendCapabilities.refreshAsync(this, decision -> {
            updateOnlineMindAccessStatus(
                    onlineMindStatus,
                    ConfirmedOwnerLease.capture(this) != null);
            updateVoiceStatus(voiceStatus);
            refreshResearchAvailability.run();
        });
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
        dealAlerts.setEnabled(researchOwner);
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

        boolean dealMonitoringConfigured = TravelDealGateway.isConfigured(this)
                || MobilityGateway.isConfigured(this);
        boolean currentEventSourceConfigured = TavilyClient.configured();
        dealAlerts.setText(!researchOwner
                ? "Automatic travel and event monitoring · owner profile required"
                : dealMonitoringConfigured || currentEventSourceConfigured
                    ? "Automatic travel and event monitoring"
                    : "Automatic event monitoring · known official events only until a current-source route is verified");

        Button save = findViewById(R.id.saveSettingsButton);
        save.setOnClickListener(v -> {
            int selectedMode = mode.getSelectedItemPosition();
            Map<String, String> saveProfile = activeProfile();
            String savePersonId = saveProfile.getOrDefault(
                    "person_id", saveProfile.getOrDefault("name", "unknown_profile"));
            if (!activePersonId.equals(savePersonId)) {
                Toast.makeText(this,
                        "The active profile changed. Nothing was saved; reopen Settings for that person.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (researchOwner
                    && !ConfirmedOwnerLease.isExactActiveOwner(this, activePersonId)) {
                Toast.makeText(this,
                        "The confirmed owner profile changed. Nothing was saved; reopen Settings.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            boolean researchRequested = autoResearch.isChecked()
                    && "yes".equals(saveProfile.getOrDefault("is_owner", "no"))
                    && "yes".equals(saveProfile.getOrDefault("memory_consent", "no"));
            boolean researchRunnable = KnowledgePackSchedulingPolicy.persistEnabled(
                    researchRequested,
                    "yes".equals(saveProfile.getOrDefault("is_owner", "no")),
                    "yes".equals(saveProfile.getOrDefault("memory_consent", "no")),
                    hasValidatedInternet(),
                    SarahModelConfig.fullConversationAvailable(),
                    TavilyClient.configured(),
                    web.isChecked(),
                    selectedMode == ConversationModePolicy.MODE_LOCAL_ONLY);
            // Preserve the owner's explicit opt-in separately from provider
            // availability. Each provider-specific job still fails closed.
            boolean monitoringOptIn = researchOwner
                    ? dealAlerts.isChecked()
                    : preferences.getBoolean(
                            "deal_alerts_enabled",
                            BackgroundResearchPolicy.DEFAULT_BACKGROUND_MONITORING_ENABLED);
            setConversationMode(this, selectedMode);
            preferences.edit()
                    .putString("connected_provider", SarahModelConfig.PROVIDER_ID)
                    .putString("model", SarahModelConfig.MODEL_ID)
                    .putInt("voice_mode", voice.getSelectedItemPosition())
                    .putBoolean("web_search", web.isChecked())
                    .putBoolean("inline_media_previews", mediaPreviews.isChecked())
                    .putBoolean("deal_alerts_enabled", monitoringOptIn)
                    .putBoolean("auto_speak", autoSpeak.isChecked())
                    .putBoolean("learn", learn.isChecked())
                    .putBoolean("auto_device_sync", autoDeviceSync.isChecked())
                    .putInt("speed", speed.getProgress())
                    .apply();
            // Store the explicit eligible-profile choice, not transient
            // provider availability. Every worker independently rechecks all
            // execution gates before doing network or memory work.
            if (researchOwner) {
                locationStore.setBackgroundResearchEnabled(activePersonId, researchRequested);
            }
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

            boolean dealMonitoringRunnable = BackgroundResearchPolicy.monitoringCanRun(
                    monitoringOptIn,
                    dealMonitoringConfigured,
                    true);
            boolean eventMonitoringRunnable = monitoringOptIn
                    && hasEligibleEventMonitoringWork(TavilyClient.configured());
            if (researchOwner) {
                if (dealMonitoringRunnable || researchRunnable) {
                    if (ConfirmedOwnerLease.isExactActiveOwner(this, activePersonId)) {
                        DealWatchScheduler.ensureScheduled(this);
                    }
                    if (ConfirmedOwnerLease.isExactActiveOwner(this, activePersonId)) {
                        DealWatchScheduler.runSoon(this);
                    }
                } else {
                    DealWatchScheduler.cancel(this);
                }
                if (eventMonitoringRunnable) {
                    if (ConfirmedOwnerLease.isExactActiveOwner(this, activePersonId)) {
                        EventMonitorScheduler.ensureScheduled(this);
                    }
                    if (ConfirmedOwnerLease.isExactActiveOwner(this, activePersonId)) {
                        EventMonitorScheduler.runSoon(this);
                    }
                } else {
                    EventMonitorScheduler.cancel(this);
                }
                if (researchRunnable) {
                    if (ConfirmedOwnerLease.isExactActiveOwner(this, activePersonId)) {
                        ProactiveDiscoveryScheduler.ensureScheduled(this);
                    }
                    if (ConfirmedOwnerLease.isExactActiveOwner(this, activePersonId)) {
                        ProactiveDiscoveryScheduler.runSoon(this);
                    }
                } else {
                    ProactiveDiscoveryScheduler.cancel(this);
                }
            }

            if (researchOwner
                    && ConfirmedOwnerLease.isExactActiveOwner(this, activePersonId)
                    && dealAlerts.isChecked()
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
        String voice = approvedOnlineVoiceReady(this)
                ? "Sarah’s natural online voice is available with automatic phone-voice fallback."
                : "Sarah uses the phone’s offline voice.";
        Toast.makeText(this, "Sarah's settings were saved. " + conversation + " " + voice,
                Toast.LENGTH_LONG).show();
        finish();
    }

    private static boolean approvedOnlineVoiceReady(Context context) {
        if (ElevenLabsVoiceConfig.backendConfigured()) {
            return ProtectedBackendCapabilities.voiceReady(context);
        }
        return ElevenLabsVoiceConfig.directConfigured();
    }

    private void updateVoiceStatus(TextView voiceStatus) {
        if (approvedOnlineVoiceReady(this)) {
            voiceStatus.setText("ElevenLabs Sarah voice ready · "
                    + ElevenLabsVoiceConfig.humanModelLabel()
                    + ". The phone’s offline voice is the automatic fallback, and text never waits for audio.");
        } else if (ElevenLabsVoiceConfig.backendConfigured()
                && ProtectedBackendCapabilities.isChecking()) {
            voiceStatus.setText("Checking Sarah’s protected online voice… The phone’s offline voice remains ready.");
        } else if (ElevenLabsVoiceConfig.backendConfigured()) {
            voiceStatus.setText("Sarah’s protected online voice is configured but not verified right now. The phone’s offline voice remains ready.");
        } else {
            voiceStatus.setText("The phone’s offline voice is ready. Sarah’s natural online voice is not connected in this build, and text remains available.");
        }
    }

    private void updateOnlineMindAccessStatus(
            TextView status,
            boolean confirmedOwnerCanConfigure) {
        if (!confirmedOwnerCanConfigure) {
            status.setText("Only the active confirmed phone owner can change Sarah's protected connection.");
            return;
        }
        if (SecureStore.hasSarahBackendAccess(this)) {
            status.setText(ProtectedBackendCapabilities.conversationReady(this)
                    ? "Sarah's protected online connection is activated and verified."
                    : "Sarah's protected connection is saved securely, but is not verified right now.");
            return;
        }
        String suggested = BuildConfig.SARAH_MODEL_BACKEND_URL == null
                ? "" : BuildConfig.SARAH_MODEL_BACKEND_URL.trim();
        status.setText(suggested.startsWith("https://")
                ? "Sarah's connection address is included, but an owner access code has not been activated."
                : "Sarah's protected online connection is not activated.");
    }

    private void showOnlineMindAccessDialog(
            TextView onlineStatus,
            TextView voiceStatus,
            Runnable refreshResearchAvailability) {
        ConfirmedOwnerLease lease = ConfirmedOwnerLease.capture(this);
        if (lease == null) {
            updateOnlineMindAccessStatus(onlineStatus, false);
            Toast.makeText(this,
                    "Only the active confirmed phone owner can activate this connection.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        android.widget.LinearLayout fields = new android.widget.LinearLayout(this);
        fields.setOrientation(android.widget.LinearLayout.VERTICAL);
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        fields.setPadding(padding, padding / 2, padding, 0);

        android.widget.EditText address = new android.widget.EditText(this);
        address.setHint("https://Sarah protected backend address");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        String savedAddress = SecureStore.loadSarahBackendUrl(this);
        if (savedAddress.isEmpty()) savedAddress = BuildConfig.SARAH_MODEL_BACKEND_URL;
        address.setText(savedAddress == null ? "" : savedAddress.trim());
        fields.addView(address, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.EditText accessCode = new android.widget.EditText(this);
        accessCode.setHint("Revocable Sarah access code");
        accessCode.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(accessCode, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Activate Sarah's online mind")
                .setMessage("Enter the Sarah backend address and its revocable app access code. Do not enter a Cloudflare, OpenAI, ElevenLabs, or other provider key. Both values are encrypted for this Android installation.")
                .setView(fields)
                .setPositiveButton("Activate", null)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Disconnect", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    lease.requireActive();
                    SecureStore.saveSarahBackendAccess(
                            this,
                            address.getText().toString(),
                            accessCode.getText().toString());
                    ProtectedBackendCapabilities.clearCached(this);
                    updateOnlineMindAccessStatus(onlineStatus, true);
                    updateVoiceStatus(voiceStatus);
                    refreshResearchAvailability.run();
                    ProtectedBackendCapabilities.refreshAsync(this, decision -> {
                        updateOnlineMindAccessStatus(
                                onlineStatus,
                                ConfirmedOwnerLease.capture(this) != null);
                        updateVoiceStatus(voiceStatus);
                        refreshResearchAvailability.run();
                    });
                    Toast.makeText(this,
                            "Sarah's protected connection was saved securely and is being verified.",
                            Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                } catch (IllegalArgumentException error) {
                    accessCode.setText("");
                    accessCode.setError(error.getMessage());
                } catch (Exception error) {
                    accessCode.setText("");
                    Toast.makeText(this,
                            "Sarah could not securely save that connection. Nothing was activated.",
                            Toast.LENGTH_LONG).show();
                }
            });
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v ->
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Disconnect Sarah's online mind?")
                            .setMessage("This removes only the encrypted Sarah connection address and access code from this installation. Offline Sarah remains available.")
                            .setPositiveButton("Disconnect", (confirmation, which) -> {
                                try {
                                    lease.requireActive();
                                    SecureStore.clearSarahBackendAccess(this);
                                    ProtectedBackendCapabilities.clearCached(this);
                                    updateOnlineMindAccessStatus(onlineStatus, true);
                                    updateVoiceStatus(voiceStatus);
                                    refreshResearchAvailability.run();
                                    dialog.dismiss();
                                } catch (RuntimeException error) {
                                    Toast.makeText(this,
                                            "The active confirmed owner changed. Nothing was disconnected.",
                                            Toast.LENGTH_LONG).show();
                                }
                            })
                            .setNegativeButton("Keep connected", null)
                            .show());
        });
        dialog.show();
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
                if (!activePersonId.equals(activePersonId())) {
                    locationStatus.setText(
                            "The active profile changed; the location result was discarded.");
                    return;
                }
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

    @Override protected void onResume() {
        super.onResume();
        refreshGmailConnectionStatus();
    }

    private void refreshGmailConnectionStatus() {
        if (gmailConnectionStatus == null) return;
        String gmailProfileId = EventTripStore.activePersonId(this);
        GmailTokenVault gmailVault = new GmailTokenVault(this);
        boolean gmailConnected = !gmailProfileId.isEmpty()
                && gmailVault.hasAuthorizedGrant(gmailProfileId);
        gmailConnectionStatus.setText(gmailConnected
                ? "Gmail read-only: " + gmailVault.accountEmail(gmailProfileId)
                    + " · monitoring "
                    + (gmailVault.monitoringEnabled(gmailProfileId) ? "on" : "off")
                    + " · last check "
                    + (gmailVault.lastSyncAt() == 0L ? "never"
                        : java.time.Instant.ofEpochMilli(gmailVault.lastSyncAt()))
                    + (gmailVault.reauthorizationRequired() ? " · reconnect required" : "")
                : "Gmail not connected · monitoring off. Google—not Sarah—handles sign-in; Sarah never asks for your Gmail password.");
    }

    private String activePersonId() {
        Map<String, String> active = activeProfile();
        return active.getOrDefault("person_id", active.getOrDefault("name", "unknown_profile"));
    }

    private boolean hasEligibleEventMonitoringWork(boolean currentSourceReady) {
        EventTripStore store = null;
        try {
            store = new EventTripStore(this, activePersonId);
            List<Map<String, String>> events = store.listActiveEventTrips(100);
            for (Map<String, String> event : events) {
                if (!"yes".equals(event.getOrDefault("monitor_enabled", "no"))) continue;
                if (currentSourceReady
                        || KnownEventCatalog.findByEventName(
                                event.getOrDefault("event_name", "")) != null) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // The database backup/open gate remains authoritative. Settings
            // must not schedule work when event state cannot be read safely.
            return false;
        } finally {
            if (store != null) store.close();
        }
        return false;
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
