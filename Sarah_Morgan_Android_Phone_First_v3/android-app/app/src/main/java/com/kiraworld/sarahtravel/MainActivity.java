package com.kiraworld.sarahtravel;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivity extends Activity {
    private static final String TAG = "SarahOwnerChat";
    private static final int REQ_SPEECH = 1201;
    private static final int REQ_PHOTO = 1202;
    private static final int REQ_AUDIO_PERMISSION = 1203;
    private static final int REQ_NOTIFICATIONS = 1204;
    private static final int REQ_LOCATION = 1205;
    private static final String STATE_PENDING_LOCATION_MESSAGE = "pending_location_message";
    private static final String STATE_PENDING_LOCATION_PERSON = "pending_location_person";
    private static final String STATE_PENDING_LOCATION_SPEAKER = "pending_location_speaker";
    private static final String STATE_PENDING_LOCATION_GENERATION = "pending_location_generation";

    private static final class ActiveVoice {
        final String personId;
        final String turnId;
        final int characterCount;
        final String attemptedRoute;
        final long requestedAt;

        ActiveVoice(
                String personId,
                String turnId,
                int characterCount,
                String attemptedRoute,
                long requestedAt) {
            this.personId = personId;
            this.turnId = turnId;
            this.characterCount = characterCount;
            this.attemptedRoute = attemptedRoute;
            this.requestedAt = requestedAt;
        }
    }

    private SarahDatabase db;
    private SarahTts tts;
    private SpeakerContext speakerContext;
    private LinearLayout chat;
    private ScrollView scroll;
    private EditText input;
    private ImageButton sendButton;
    private TextView status;
    private ConnectivityMonitor connectivityMonitor;
    private volatile boolean internetAvailable;
    private volatile boolean lastSmartCallFailed;
    private volatile boolean connectedRouteProven;
    private volatile boolean reconnecting;
    private volatile String lastTurnRoute = TurnRoute.UNKNOWN_LEGACY;
    private final ExecutorService conversationExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService backgroundResearchExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mediaExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService networkAttemptExecutor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean knowledgeRefreshInFlight = new AtomicBoolean(false);
    private final AtomicLong photoRequestSequence = new AtomicLong();
    private volatile Thread backgroundResearchThread;
    private volatile ActiveVoice activeVoice;
    private byte[] pendingPhoto;
    private File pendingPhotoFile;
    private SarahLocationStore locationStore;
    private ApproximateLocationCoordinator locationCoordinator;
    private String pendingLocationMessage = "";
    private String pendingLocationPersonId = "";
    private String pendingLocationSpeaker = "";
    private long pendingLocationGeneration;
    private final AtomicLong lifecycleGeneration = new AtomicLong(1L);
    private final AtomicLong voiceRequestSequence = new AtomicLong();
    private volatile boolean turnInFlight;
    private volatile boolean destroyed;
    private final Runnable deferredKnowledgeRefresh = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (turnInFlight) {
                mainHandler.postDelayed(this, 3_000L);
                return;
            }
            refreshKnowledgeAsync();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        EventTripPreUpgradeBackupGate.Result upgradeState =
                SarahApplication.eventTripUpgradeState();
        if (upgradeState != null && !upgradeState.mayOpenV2) {
            showEventTripUpgradeBlocked(upgradeState);
            return;
        }
        if (state != null) {
            pendingLocationMessage = state.getString(STATE_PENDING_LOCATION_MESSAGE, "");
            pendingLocationPersonId = state.getString(STATE_PENDING_LOCATION_PERSON, "");
            pendingLocationSpeaker = state.getString(STATE_PENDING_LOCATION_SPEAKER, "");
            pendingLocationGeneration = state.getLong(STATE_PENDING_LOCATION_GENERATION, 0L);
        }
        SettingsActivity.ensureAutomaticModeDefault(this);
        db = new SarahDatabase(this);
        SarahLocationStore pendingLocations = new SarahLocationStore(this);
        if (!pendingLocations.pendingOwnerMoveIds().isEmpty()) {
            startActivity(new Intent(this, OwnerIdentityCorrectionActivity.class));
            finish();
            return;
        }
        if (!db.hasProfile()) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        PersonProfileStore ownerProfiles = new PersonProfileStore(this);
        Map<String, String> ownerRecord;
        Map<String, String> activeRecord;
        try {
            if (db.isPlaceholderOwner()) {
                startActivity(new Intent(this, OwnerIdentityCorrectionActivity.class));
                finish();
                return;
            }
            ownerRecord = ownerProfiles.ensureOwner(db.getProfile());
            activeRecord = ownerProfiles.getActiveProfile();
        } finally {
            ownerProfiles.close();
        }
        LegacyEventTripOwnerClaimGate.Result legacyClaim =
                LegacyEventTripOwnerClaimGate.ensure(this, ownerRecord, activeRecord);
        if (!legacyClaim.mayProceed) {
            showLegacyEventClaimBlocked(legacyClaim.status);
            return;
        }
        db.repairPlaceholderOwnerLabels();
        DealNotificationManager.createChannel(this);
        SharedPreferences startupPreferences = getSharedPreferences(
                SettingsActivity.PREFS, MODE_PRIVATE);
        String ownerPersonId = ownerRecord.getOrDefault(
                "person_id", ownerRecord.getOrDefault("name", "unknown_profile"));
        String activePersonId = activeRecord.getOrDefault(
                "person_id", activeRecord.getOrDefault("name", ""));
        boolean activeConfirmedOwner = "yes".equals(
                    activeRecord.getOrDefault("is_owner", "no"))
                && EventTripProfilePolicy.profileKey(ownerPersonId).equals(
                    EventTripProfilePolicy.profileKey(activePersonId));
        boolean dealMonitoringAllowed = activeConfirmedOwner
                && BackgroundResearchPolicy.monitoringCanRun(
                startupPreferences.getBoolean(
                        "deal_alerts_enabled",
                        BackgroundResearchPolicy.DEFAULT_BACKGROUND_MONITORING_ENABLED),
                TravelCommerceConfig.isConfigured(),
                !db.listActiveDealWatches(1).isEmpty());
        if (dealMonitoringAllowed
                && ConfirmedOwnerLease.isExactActiveOwner(this, ownerPersonId)) {
            DealWatchScheduler.ensureScheduled(this);
        } else {
            DealWatchScheduler.cancel(this);
        }
        boolean proactiveAllowed = KnowledgePackSchedulingPolicy.canSchedule(
                    activeConfirmedOwner,
                    "yes".equals(activeRecord.getOrDefault("memory_consent", "no")),
                    ConnectivityMonitor.hasValidatedInternet(this),
                    SarahModelConfig.fullConversationAvailable(),
                    TavilyClient.configured(),
                    new SarahLocationStore(this).backgroundResearchEnabled(ownerPersonId))
                && startupPreferences.getBoolean("web_search", true)
                && SettingsActivity.getConversationMode(this)
                    != ConversationModePolicy.MODE_LOCAL_ONLY;
        if (proactiveAllowed
                && ConfirmedOwnerLease.isExactActiveOwner(this, ownerPersonId)) {
            ProactiveDiscoveryScheduler.ensureScheduled(this);
        }
        else ProactiveDiscoveryScheduler.cancel(this);
        boolean eventMonitoringAllowed = activeConfirmedOwner
                && startupPreferences.getBoolean(
                    "deal_alerts_enabled",
                    BackgroundResearchPolicy.DEFAULT_BACKGROUND_MONITORING_ENABLED)
                && hasEligiblePersistedEventMonitor(ownerPersonId);
        if (eventMonitoringAllowed
                && ConfirmedOwnerLease.isExactActiveOwner(this, ownerPersonId)) {
            EventMonitorScheduler.ensureScheduled(this);
        }
        else EventMonitorScheduler.cancel(this);
        setContentView(R.layout.activity_main);
        chat = findViewById(R.id.chatContainer);
        scroll = findViewById(R.id.chatScroll);
        input = findViewById(R.id.messageInput);
        status = findViewById(R.id.statusText);
        SafeAreaInsets.apply(
                this,
                findViewById(R.id.mainRoot),
                findViewById(R.id.bottomControls),
                scroll,
                findViewById(R.id.bottomNavigation));
        speakerContext = new SpeakerContext(db.getProfile());
        locationStore = new SarahLocationStore(this);
        locationCoordinator = new ApproximateLocationCoordinator(this);
        if (!pendingLocationMessage.isEmpty()) {
            input.setText("");
            if (locationCoordinator.hasPermission()) {
                mainHandler.post(this::resolvePendingLocation);
            } else {
                updateSpeakerStatus("Waiting for approximate-location permission…");
            }
        }

        connectivityMonitor = new ConnectivityMonitor(this, connected -> {
            boolean changed = internetAvailable != connected;
            internetAvailable = connected;
            if (changed) connectedRouteProven = false;
            runOnUiThread(() -> {
                applyProactiveResearchSchedule(connected);
                if (changed) {
                    String message = connected
                            ? "Internet is back. Sarah is checking the protected connection."
                            : "Connection lost. Sarah is continuing locally.";
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                }
                if (connected) {
                    reconnecting = changed;
                    refreshProtectedCapabilities();
                    scheduleDeferredKnowledgeRefresh();
                } else {
                    reconnecting = false;
                    mainHandler.removeCallbacks(deferredKnowledgeRefresh);
                    updateSpeakerStatus(null);
                }
            });
        });
        internetAvailable = connectivityMonitor.currentValidatedInternet();
        status.setOnClickListener(v -> showConversationModeMenu());

        tts = new SarahTts(this, new SarahTts.Listener() {
            @Override
            public void onReady(String voiceName) {
                runOnUiThread(() -> updateSpeakerStatus(null));
            }

            @Override
            public void onUnavailable() {
                runOnUiThread(() -> updateSpeakerStatus("Text ready • voice unavailable"));
            }
        });

        loadHistory();
        if (currentHistory(1).isEmpty()) greet();

        sendButton = findViewById(R.id.sendButton);
        sendButton.setOnClickListener(v -> sendCurrent());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrent();
                return true;
            }
            return false;
        });
        input.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                findViewById(R.id.tripContextPanel).setVisibility(View.GONE);
                ((TextView) findViewById(R.id.tripContextToggle))
                        .setText("Trip context and calm tools ▾");
            }
        });

        TextView contextToggle = findViewById(R.id.tripContextToggle);
        View contextPanel = findViewById(R.id.tripContextPanel);
        contextToggle.setOnClickListener(v -> {
            boolean opening = contextPanel.getVisibility() != View.VISIBLE;
            contextPanel.setVisibility(opening ? View.VISIBLE : View.GONE);
            contextToggle.setText(opening
                    ? "Trip context and calm tools ▴"
                    : "Trip context and calm tools ▾");
        });

        findViewById(R.id.calmButton).setOnClickListener(v -> showCalmMenu());
        findViewById(R.id.settingsButton).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.notebookButton).setOnClickListener(v -> startActivity(new Intent(this, TravelNotebookActivity.class)));
        findViewById(R.id.micButton).setOnClickListener(v -> startSpeech());
        findViewById(R.id.photoButton).setOnClickListener(v -> pickPhoto());
        findViewById(R.id.chatNavButton).setOnClickListener(v -> {
            contextPanel.setVisibility(View.GONE);
            contextToggle.setText("Trip context and calm tools ▾");
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            input.requestFocus();
        });
        findViewById(R.id.tripNavButton).setOnClickListener(
                v -> startActivity(new Intent(this, TravelHubActivity.class)));
        findViewById(R.id.discoverNavButton).setOnClickListener(
                v -> startActivity(new Intent(this, DiscoveryActivity.class)));
        findViewById(R.id.connectionsNavButton).setOnClickListener(
                v -> startActivity(new Intent(this, SponsorConnectionsActivity.class)));
        updateSpeakerStatus(null);
        if (internetAvailable) refreshProtectedCapabilities();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (connectivityMonitor != null) connectivityMonitor.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tts != null) tts.setRate(currentSpeechRate());
        if (connectivityMonitor != null) internetAvailable = connectivityMonitor.currentValidatedInternet();
        if (speakerContext != null) updateSpeakerStatus(null);
        if (internetAvailable) {
            refreshProtectedCapabilities();
            scheduleDeferredKnowledgeRefresh();
        }
    }

    @Override
    protected void onStop() {
        mainHandler.removeCallbacks(deferredKnowledgeRefresh);
        if (connectivityMonitor != null) connectivityMonitor.stop();
        super.onStop();
    }

    private void showEventTripUpgradeBlocked(EventTripPreUpgradeBackupGate.Result state) {
        ScrollView blockedScroll = new ScrollView(this);
        LinearLayout blocked = new LinearLayout(this);
        blocked.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        blocked.setPadding(padding, padding, padding, padding);
        TextView heading = new TextView(this);
        heading.setText("Sarah protected your existing travel data");
        heading.setTextSize(24f);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        blocked.addView(heading);
        TextView explanation = new TextView(this);
        explanation.setText("Sarah did not open or upgrade the existing event-trip database because its R1 recovery copy could not be verified. No event-trip migration ran.\n\nStatus: "
                + state.status
                + "\n\nClose Sarah and keep this build installed until the recovery evidence can be inspected.");
        explanation.setTextSize(16f);
        explanation.setPadding(0, padding / 2, 0, padding / 2);
        blocked.addView(explanation);
        android.widget.Button close = new android.widget.Button(this);
        close.setText("Close Sarah");
        close.setAllCaps(false);
        close.setOnClickListener(v -> finish());
        blocked.addView(close);
        blockedScroll.addView(blocked);
        setContentView(blockedScroll);
        SafeAreaInsets.apply(this, blockedScroll, null, blockedScroll);
    }

    private void showLegacyEventClaimBlocked(String status) {
        ScrollView blockedScroll = new ScrollView(this);
        LinearLayout blocked = new LinearLayout(this);
        blocked.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        blocked.setPadding(padding, padding, padding, padding);
        TextView heading = new TextView(this);
        heading.setText("Sarah preserved your earlier event and booking records");
        heading.setTextSize(24f);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        blocked.addView(heading);
        TextView explanation = new TextView(this);
        explanation.setText("Sarah could not yet bind the preserved R1 records to the exact confirmed owner profile. No hidden row was discarded. Close and reopen Sarah to retry.\n\nStatus: "
                + status);
        explanation.setTextSize(16f);
        explanation.setPadding(0, padding / 2, 0, padding / 2);
        blocked.addView(explanation);
        android.widget.Button close = new android.widget.Button(this);
        close.setText("Close Sarah");
        close.setOnClickListener(v -> finish());
        blocked.addView(close);
        blockedScroll.addView(blocked);
        setContentView(blockedScroll);
    }

    private void refreshProtectedCapabilities() {
        if (!internetAvailable || destroyed) {
            reconnecting = false;
            updateSpeakerStatus(null);
            return;
        }
        updateSpeakerStatus(null);
        ProtectedBackendCapabilities.refreshAsync(this, decision -> {
            if (destroyed || isFinishing()) return;
            reconnecting = false;
            SettingsActivity.ensureAutomaticModeDefault(this);
            applyProactiveResearchSchedule(internetAvailable);
            updateSpeakerStatus(null);
        });
    }

    private void showConversationModeMenu() {
        int mode = SettingsActivity.getConversationMode(this);
        String[] choices = {
                "Automatic — connect when available, continue offline when needed",
                "Connected preferred — retry Sarah's online conversation when possible",
                "Offline only — use saved knowledge and phone tools",
                "Open detailed settings"
        };
        String current = ConversationModePolicy.statusLabel(
                mode,
                internetAvailable,
                SarahModelConfig.fullConversationAvailable(),
                lastSmartCallFailed,
                connectedRouteProven,
                ProtectedBackendCapabilities.isChecking(),
                reconnecting);

        new AlertDialog.Builder(this)
                .setTitle("Choose how Sarah connects")
                .setMessage("Current: " + current
                        + "\n\nAutomatic mode reconnects when possible and keeps the conversation available offline. Current information is shown only when Sarah receives a verified source receipt. Technical connection details remain in Settings.")
                .setItems(choices, (dialog, which) -> {
                    if (which == 3) {
                        startActivity(new Intent(this, SettingsActivity.class));
                        return;
                    }
                    SettingsActivity.setConversationMode(this, which);
                    lastSmartCallFailed = false;
                    updateSpeakerStatus(null);
                    if (which != ConversationModePolicy.MODE_LOCAL_ONLY
                            && !SarahModelConfig.fullConversationAvailable()) {
                        new AlertDialog.Builder(this)
                                .setTitle("Connected conversation is unavailable")
                                .setMessage("Sarah can keep talking offline and can open supported public tools while internet is available. There is nothing you need to configure here.")
                                .setPositiveButton("OK", null)
                                .show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showCalmMenu() {
        String[] choices = {"Stay with me through turbulence", "Start personalized trivia", "Five-senses grounding game"};
        new AlertDialog.Builder(this)
                .setTitle("Calm & Trivia")
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) postLocalSarahReply(CalmSupport.turbulenceSupport(currentProfile()), "Calm support");
                    else if (which == 1) startTriviaGame();
                    else postLocalSarahReply(CalmSupport.groundingSupport(), "Grounding game");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void postLocalSarahReply(String reply, String mode) {
        postLocalSarahReply(reply, mode, TurnRoute.LOCAL_TOOL_RESULT);
    }

    private void postLocalSarahReply(String reply, String mode, String route) {
        MindEventStore.record(
                this,
                speakerContext.activeName(),
                SarahChannelResponse.spokenOnly(
                        reply,
                        "Sarah returned a local calm, trivia, grounding, or offline-support response. No booking or external action was completed."),
                route);
        lastTurnRoute = route;
        db.addMessage("assistant", reply, speakerContext.activeName(), lastTurnRoute);
        addBubble("Sarah", reply, false, lastTurnRoute);
        updateSpeakerStatus(mode);
        speak(reply);
        TrustedSyncClient.syncAllAsync(this);
    }

    private void startTriviaGame() {
        Map<String, String> profile = currentProfile();
        List<CalmSupport.Question> questions = CalmSupport.questions(
                profile,
                currentTrips(profile),
                currentWishes(profile));
        showTriviaQuestion(questions, 0, 0);
    }

    private void showTriviaQuestion(List<CalmSupport.Question> questions, int index, int score) {
        if (index >= questions.size()) {
            postLocalSarahReply(
                    "Trivia finished. You got " + score + " out of " + questions.size()
                            + ". The point was not the score—it was giving your mind somewhere else to stand for a few minutes.",
                    "Trivia finished");
            return;
        }
        CalmSupport.Question q = questions.get(index);
        new AlertDialog.Builder(this)
                .setTitle("Trivia " + (index + 1) + " of " + questions.size())
                .setMessage(q.prompt)
                .setItems(q.choices, (dialog, which) -> {
                    boolean correct = which == q.correctIndex;
                    String result = (correct ? "Correct. " : "Not quite. ") + q.explanation;
                    speak(result);
                    new AlertDialog.Builder(this)
                            .setTitle(correct ? "Correct" : "Answer")
                            .setMessage(result)
                            .setPositiveButton("Next", (d, w) -> showTriviaQuestion(questions, index + 1, score + (correct ? 1 : 0)))
                            .setNegativeButton("Stop", null)
                            .show();
                })
                .setNegativeButton("Stop", null)
                .show();
    }

    private void greet() {
        String name = speakerContext.activeName();
        String greeting = speakerContext.isOwner()
                ? "I’m glad we met, " + name
                    + ". I’m ready to talk about a trip, a place you dream about, or absolutely nothing travel-related."
                : "Hi, " + name
                    + ". I’m using your separate profile. Travel is optional—we can talk about whatever interests you.";
        lastTurnRoute = TurnRoute.OFFLINE_LOCAL;
        db.addMessage("assistant", greeting, name, lastTurnRoute);
        addBubble("Sarah", greeting, false, lastTurnRoute);
        speak(greeting);
    }

    private void loadHistory() {
        for (Map<String, String> row : currentHistory(30)) {
            boolean assistant = "assistant".equals(row.get("role"));
            String who = assistant ? "Sarah" : row.getOrDefault("speaker_name", speakerContext.activeName());
            if (who == null || who.trim().isEmpty()) who = speakerContext.activeName();
            addBubble(who, row.get("content"), !assistant, row.getOrDefault("route", TurnRoute.UNKNOWN_LEGACY));
        }
    }

    private void sendCurrent() {
        if (!TurnLifecyclePolicy.canSubmit(destroyed, turnInFlight)) {
            if (!destroyed) Toast.makeText(
                    this,
                    "Sarah is finishing the current reply so the conversation stays in order.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String text = input.getText().toString().trim();
        if (text.isEmpty() && pendingPhoto == null) return;
        if (!text.isEmpty() && ensureApproximateAreaForTurn(text)) return;
        final long turnSubmittedAt = System.currentTimeMillis();
        final String turnId = "turn-" + turnSubmittedAt + "-"
                + UUID.randomUUID().toString().replace("-", "");
        pauseBackgroundResearchForOwnerTurn();
        input.setText("");

        String display = text.isEmpty() ? "What do you think of this trip photo?" : text;
        String speakerBefore = speakerContext.activeName();
        SpeakerContext.Result speakerResult = speakerContext.handle(display);
        if (speakerResult.speakerChanged && !invalidatePriorSpeakerWork()) return;
        String turnSpeaker = speakerResult.messageBelongsToActiveSpeaker
                ? speakerContext.activeName() : speakerBefore;
        addBubble(turnSpeaker, display + (pendingPhoto != null ? "\n[Photo attached]" : ""), true);
        db.addMessage("user", display, turnSpeaker);

        if (speakerResult.handled) {
            String replySpeaker = speakerContext.activeName();
            long completedAt = System.currentTimeMillis();
            MindEventStore.record(
                    this,
                    replySpeaker,
                    SarahChannelResponse.spokenOnly(
                            speakerResult.reply,
                                    "Sarah returned a local identity, profile, consent, or calm-support response. No booking or external action was completed.")
                            .withFactualAudit(TextTurnReceipt.build(
                                    turnId,
                                    TurnRoute.LOCAL_TOOL_RESULT,
                                    "on-device",
                                    "SpeakerContext",
                                    turnSubmittedAt,
                                    completedAt)),
                    TurnRoute.LOCAL_TOOL_RESULT);
            lastTurnRoute = TurnRoute.LOCAL_TOOL_RESULT;
            db.addMessage("assistant", speakerResult.reply, replySpeaker, lastTurnRoute);
            addBubble("Sarah", speakerResult.reply, false, lastTurnRoute);
            updateSpeakerStatus(speakerContext.ageKnown() ? "Profile: " + replySpeaker : "Family-friendly until age is known");
            speak(speakerResult.reply, turnId);
            TrustedSyncClient.syncAllAsync(this);
            return;
        }

        Map<String, String> profile = currentProfile();
        if (connectivityMonitor != null) {
            internetAvailable = connectivityMonitor.currentValidatedInternet();
        }
        List<Map<String, String>> historyForActions = currentHistory(12);
        List<Map<String, String>> memoriesForActions = currentMemories(profile);
        AgenticTravelPlanner.Plan proactive = AgenticTravelPlanner.plan(
                display, profile, historyForActions, memoriesForActions);
        final String plannerReply = proactive.handled() ? proactive.reply.trim() : "";
        AgenticActionExecutor.Result actionResult = AgenticActionExecutor.apply(
                this, db, profile, proactive.actions, internetAvailable);
        if (actionResult.createdDealWatch) {
            DealNotificationManager.requestPermissionIfNeeded(this, REQ_NOTIFICATIONS);
        }
        if (actionResult.monitoringUnavailable) {
            addMemoryNote("No background travel watch was created · live monitoring unavailable");
            Toast.makeText(
                    this,
                    "Automatic monitoring is not connected, so Sarah did not start a background watch.",
                    Toast.LENGTH_LONG).show();
        }
        learnFrom(display, profile);

        final byte[] image = pendingPhoto;
        final File imageFile = pendingPhotoFile;
        pendingPhoto = null;
        pendingPhotoFile = null;
        updateSpeakerStatus("Checking the reply route…");

        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        int mode = SettingsActivity.getConversationMode(this);
        String key = SecureStore.loadApiKey(this);
        boolean teamModelAvailable = SarahModelConfig.fullConversationAvailable();
        boolean useSmart = ConversationModePolicy.ROUTE_SMART.equals(
                ConversationModePolicy.route(mode, internetAvailable, teamModelAvailable));
        boolean web = useSmart && prefs.getBoolean("web_search", true) && needsLiveSearch(display);

        List<Map<String, String>> history = currentHistory(12);
        List<Map<String, String>> memories = currentMemories(profile);
        List<Map<String, String>> trips = currentTrips(profile);
        List<Map<String, String>> wishes = currentWishes(profile);
        List<Map<String, String>> knowledgePacks = currentKnowledgePacks(profile);
        List<Map<String, String>> dealWatches = currentDealWatches(profile);
        final String plannedTurnRoute = useSmart
                ? TurnRoute.connectedRoute(SarahModelConfig.PROVIDER_ID)
                : TurnRoute.OFFLINE_LOCAL;
        String basePrompt = SarahPromptBuilder.build(
                profile, memories, trips, wishes, image != null, web, plannedTurnRoute);
        final String prompt = plannerReply.isEmpty()
                ? basePrompt
                : basePrompt
                    + "\nBOUNDED_LOCAL_PLANNING_DRAFT: " + plannerReply
                    + "\nUse that draft only as conversational planning context. Do not claim a search, monitor, download, or saved job unless the runtime action receipt proves it happened. Current prices, events, or availability still require a verified source receipt.";
        final boolean offerLiveTravelSearch = explicitExploreRequest(display);
        final boolean sourceFirstEvent = internetAvailable
                && mode != ConversationModePolicy.MODE_LOCAL_ONLY
                && prefs.getBoolean("web_search", true)
                && (KnownEventCatalog.find(display) != null
                    || !GenericEventReference.recentEvent(history, display).isEmpty());
        final String providerId = SarahModelConfig.PROVIDER_ID;
        final String model = SarahModelConfig.MODEL_ID;
        final String responseSpeaker = speakerContext.activeName();
        final String searchQuery = web
                ? TravelSearchQueryPolicy.build(display, history, profile, trips)
                : "";
        final String activeDestination = TravelContextResolver.primaryDestination(display, history);
        final long requestGeneration = lifecycleGeneration.get();
        setTurnInFlight(true);

        try {
        conversationExecutor.submit(() -> {
            String reply;
            boolean smartFallback = false;
            boolean smartSucceeded = false;
            String actualTurnRoute = plannedTurnRoute;
            String actualProvider = useSmart ? providerId : "on-device";
            String actualModel = useSmart ? model : "DemoSarah";
            String connectedAudit = "";
            String sourceDetails = "No current-source lookup was used for this reply.";
            try {
                PublicSourceResult sourceBackedEvent = sourceFirstEvent
                        ? PublicOnlineFallback.answerResult(
                                getApplicationContext(), display, history,
                                profile.getOrDefault("person_id", ""))
                        : null;
                if (sourceBackedEvent != null && !sourceBackedEvent.reply.isEmpty()) {
                    reply = sourceBackedEvent.reply;
                    actualTurnRoute = sourceBackedEvent.turnRoute();
                    actualProvider = "public-source-tool";
                    actualModel = sourceBackedEvent.verified
                            ? "verified-source-gate" : "source-unavailable-gate";
                    sourceDetails = sourceBackedEvent.ownerSourceDetails();
                } else if (useSmart) {
                    ConnectedModelResponse connected = connectedReplyWithRetry(
                            providerId, key, model, prompt, history, display,
                            web, searchQuery, image);
                    smartSucceeded = true;
                    actualProvider = connected.provider;
                    actualModel = connected.model;
                    connectedAudit = connected.auditFact(turnSubmittedAt);
                    sourceDetails = connected.ownerSourceDetails();
                    FinalDisplayedResponsePolicy.Selection selection = FinalDisplayedResponsePolicy.select(
                            display,
                            activeDestination,
                            plannerReply,
                            connected.reply,
                            connected.turnRoute(),
                            web,
                            connected.hasVerifiedWebReceipt());
                    reply = selection.reply;
                    actualTurnRoute = selection.route;
                    if (!selection.usedConnectedReply) {
                        actualProvider = "on-device-response-selection";
                        actualModel = "FinalDisplayedResponsePolicy";
                        sourceDetails = "Connected reply was not displayed: " + selection.reason
                                + ". No unsupported current-source claim was passed through.";
                    }
                } else {
                    reply = plannerReply.isEmpty()
                            ? DemoSarah.reply(
                                    display, profile, image != null, history, memories,
                                    trips, wishes, knowledgePacks, dealWatches, TurnRoute.OFFLINE_LOCAL)
                            : plannerReply;
                    actualTurnRoute = plannerReply.isEmpty()
                            ? TurnRoute.OFFLINE_LOCAL : TurnRoute.LOCAL_TOOL_RESULT;
                    actualProvider = "on-device";
                    actualModel = plannerReply.isEmpty()
                            ? "DemoSarah" : "AgenticTravelPlanner";
                }
            } catch (Exception e) {
                reply = plannerReply.isEmpty()
                        ? DemoSarah.reply(
                                display, profile, image != null, history, memories,
                                trips, wishes, knowledgePacks, dealWatches,
                                TurnRoute.ONLINE_FAILED_FELL_BACK_OFFLINE)
                        : plannerReply
                            + " The connected conversation was unavailable for this turn, so I stayed with the on-device planning answer and did not invent live results.";
                smartFallback = useSmart;
                actualTurnRoute = useSmart
                        ? TurnRoute.ONLINE_FAILED_FELL_BACK_OFFLINE
                        : TurnRoute.OFFLINE_LOCAL;
                actualProvider = "on-device";
                actualModel = "DemoSarah";
            }

            SarahChannelResponse parsedChannels = SarahChannelResponse.parse(reply);
            SarahChannelResponse guardedChannels = ReplyTruthGuard.enforce(
                    parsedChannels,
                    actionResult.hasDurableBackgroundWork(),
                    actionResult.receiptSummary(),
                    actionResult.pendingSummary());
            if (actionResult.hasFailedForegroundAction()) {
                guardedChannels = guardedChannels.withGroundingCorrection(
                        actionResult.failedForegroundSummary(),
                        "The planner's requested foreground change did not match saved active-profile state; exact execution truth replaced the proposed wording.");
            } else if (actionResult.hasCompletedForegroundAction()) {
                guardedChannels = guardedChannels.withGroundingCorrection(
                        actionResult.completedForegroundSummary(),
                        "The displayed response is the exact completed foreground-action receipt, not a background-work claim.");
            }
            String exactTuringAnswer = OfflineTuringPolicy.answer(
                    display,
                    profile,
                    actualTurnRoute);
            if (!exactTuringAnswer.isEmpty()) {
                guardedChannels = SarahChannelResponse.spokenOnly(
                        exactTuringAnswer,
                        "The application supplied exact identity or route truth after the actual turn route was recorded; model wording was not used for this explicit acceptance prompt.");
            }
            long textCompletedAt = System.currentTimeMillis();
            SarahChannelResponse finalChannels = guardedChannels
                    .withFactualAudit(connectedAudit)
                    .withFactualAudit(TextTurnReceipt.build(
                            turnId,
                            actualTurnRoute,
                            actualProvider,
                            actualModel,
                            turnSubmittedAt,
                            textCompletedAt));
            String finalReply = finalChannels.spoken;
            boolean finalSmartFallback = smartFallback;
            boolean finalSmartSucceeded = smartSucceeded;
            String finalTurnRoute = actualTurnRoute;
            String finalSourceDetails = sourceDetails;
            runOnUiThreadIfActive(requestGeneration, () -> {
                boolean assistantCommitted = false;
                try {
                if (finalSmartSucceeded) {
                    lastSmartCallFailed = false;
                    connectedRouteProven = true;
                }
                if (finalSmartFallback) {
                    lastSmartCallFailed = true;
                    connectedRouteProven = false;
                }
                MindEventStore.record(
                        this,
                        responseSpeaker,
                        finalChannels,
                        finalTurnRoute);
                lastTurnRoute = finalTurnRoute;
                db.addMessage("assistant", finalReply, responseSpeaker, finalTurnRoute);
                assistantCommitted = true;
                if (imageFile != null) db.addPhoto(imageFile.getAbsolutePath(), display);
                if (speakerContext.activeName().equalsIgnoreCase(responseSpeaker)) {
                    addBubble("Sarah", finalReply, false, finalTurnRoute, finalSourceDetails);
                    updateSpeakerStatus(null);
                    speak(finalReply, turnId);
                    TrustedSyncClient.syncAllAsync(this);
                } else {
                    Toast.makeText(
                            this,
                            "Sarah saved a reply in " + responseSpeaker + "’s separate conversation.",
                            Toast.LENGTH_SHORT).show();
                }
                if (finalSmartFallback) {
                    Toast.makeText(
                            this,
                            "The connected mind did not answer, so Sarah continued with supported public or offline tools. Automatic mode will try the connection again on the next message.",
                            Toast.LENGTH_LONG).show();
                }
                if (offerLiveTravelSearch) TravelSearchHelper.show(this, display, profile);
                if (finalSmartSucceeded || actionResult.queuedKnowledge) {
                    scheduleDeferredKnowledgeRefresh();
                }
                } finally {
                    setTurnInFlight(false);
                    if (!assistantCommitted) {
                        updateSpeakerStatus("Reply could not be committed · try again");
                    }
                }
            });
        });
        } catch (RuntimeException submissionFailure) {
            setTurnInFlight(false);
            throw submissionFailure;
        }
    }

    private void refreshKnowledgeAsync() {
        if (destroyed || turnInFlight) return;
        if (!knowledgeRefreshInFlight.compareAndSet(false, true)) {
            mainHandler.removeCallbacks(deferredKnowledgeRefresh);
            mainHandler.postDelayed(deferredKnowledgeRefresh, 3_000L);
            return;
        }
        final long requestGeneration = lifecycleGeneration.get();
        SharedPreferences researchPrefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        int researchMode = SettingsActivity.getConversationMode(this);
        Map<String, String> researchProfile = currentProfile();
        List<Map<String, String>> researchTrips = currentTrips(researchProfile);
        String researchDestination = researchTrips.isEmpty()
                ? ""
                : researchTrips.get(0).getOrDefault("destination", "");
        String researchPersonId = researchProfile.getOrDefault(
                "person_id", researchProfile.getOrDefault("name", "unknown_profile"));
        boolean researchEnabled = researchMode != ConversationModePolicy.MODE_LOCAL_ONLY
                && researchPrefs.getBoolean("web_search", true)
                && locationStore.backgroundResearchEnabled(researchPersonId);
        if (!BackgroundResearchPolicy.canRun(
                internetAvailable,
                SarahModelConfig.fullConversationAvailable(),
                researchEnabled,
                isOwner(researchProfile),
                "yes".equals(researchProfile.getOrDefault("memory_consent", "no")),
                researchDestination,
                ProfileLearningContext.interests(researchProfile))) {
            knowledgeRefreshInFlight.set(false);
            return;
        }
        String key = SecureStore.loadApiKey(this);
        try {
        backgroundResearchExecutor.submit(() -> {
            backgroundResearchThread = Thread.currentThread();
            SarahDatabase backgroundDb = new SarahDatabase(getApplicationContext());
            try {
                ConfirmedOwnerLease ownerLease = ConfirmedOwnerLease.capture(
                        getApplicationContext());
                if (ownerLease == null) {
                    throw new IllegalStateException(
                            "FOREGROUND_RESEARCH_CONFIRMED_OWNER_LEASE_REQUIRED");
                }
                ownerLease.requireActive();
                int refreshed = DestinationKnowledgeCoordinator.refreshPending(
                        backgroundDb,
                        KnowledgeProfileKey.forProfile(researchProfile),
                        SarahModelConfig.PROVIDER_ID,
                        key,
                        SarahModelConfig.MODEL_ID,
                        BackgroundResearchPolicy.MAX_PACKS_PER_RUN,
                        ownerLease);
                ownerLease.requireActive();
                try {
                    ProactiveDiscoveryCoordinator.refresh(
                            getApplicationContext(),
                            researchProfile,
                            researchTrips,
                            "profile_opted_in_foreground",
                            ownerLease);
                    ownerLease.requireActive();
                } catch (Exception proactiveFailure) {
                    Log.e(TAG,
                            "Proactive discovery failed; its append-only failure receipt was retained",
                            proactiveFailure);
                }
                if (refreshed > 0) {
                    runOnUiThreadIfActive(requestGeneration, () -> Toast.makeText(
                            this,
                            "Sarah refreshed " + refreshed + " destination knowledge pack"
                                    + (refreshed == 1 ? "." : "s."),
                            Toast.LENGTH_SHORT).show());
                }
            } catch (Exception failure) {
                runOnUiThreadIfActive(requestGeneration, () -> Toast.makeText(
                        this,
                        "Destination research did not complete. The failure receipt was saved and no result was claimed.",
                        Toast.LENGTH_LONG).show());
            } finally {
                backgroundDb.close();
                backgroundResearchThread = null;
                knowledgeRefreshInFlight.set(false);
            }
        });
        } catch (RuntimeException rejected) {
            knowledgeRefreshInFlight.set(false);
            throw rejected;
        }
    }

    private void scheduleDeferredKnowledgeRefresh() {
        if (destroyed) return;
        mainHandler.removeCallbacks(deferredKnowledgeRefresh);
        mainHandler.postDelayed(deferredKnowledgeRefresh, 8_000L);
    }

    /** Chat owns the foreground latency budget; interrupted work remains FAILED/pending with receipts. */
    private void pauseBackgroundResearchForOwnerTurn() {
        mainHandler.removeCallbacks(deferredKnowledgeRefresh);
        Thread worker = backgroundResearchThread;
        if (worker == null) return;
        TavilyClient.cancel(worker);
        ConnectedModelGateway.cancel(worker);
        worker.interrupt();
    }

    private Map<String, String> currentProfile() {
        Map<String, String> profile = speakerContext.profileFor(db.getProfile());
        if (locationStore != null) {
            String personId = profile.getOrDefault("person_id", speakerContext.activeName());
            String area = locationStore.freshArea(
                    personId,
                    System.currentTimeMillis());
            if (!area.isEmpty()) {
                profile.put("runtime_current_area", area);
                profile.put("runtime_current_area_source", locationStore.source(personId));
            }
        }
        return profile;
    }

    private List<Map<String, String>> currentHistory(int limit) {
        return db.recentMessagesForSpeaker(speakerContext.activeName(), limit);
    }

    private List<Map<String, String>> currentMemories(Map<String, String> profile) {
        if (isOwner(profile)) return db.listMemories(40);
        PersonProfileStore people = new PersonProfileStore(getApplicationContext());
        try {
            return people.listMemories(profile.getOrDefault("name", speakerContext.activeName()), 40);
        } finally {
            people.close();
        }
    }

    private List<Map<String, String>> currentTrips(Map<String, String> profile) {
        if (isOwner(profile)) return db.listTrips(20);
        if (!"going".equals(profile.getOrDefault("current_shared_trip_participation", "unknown"))) {
            return Collections.emptyList();
        }
        String destination = profile.getOrDefault("current_shared_trip", "").trim();
        if (destination.isEmpty()) return Collections.emptyList();
        Map<String, String> trip = new LinkedHashMap<>();
        trip.put("title", "Shared trip");
        trip.put("destination", destination);
        trip.put("status", "shared");
        trip.put("notes", "The active profile is recorded as joining this trip; owner-private details are omitted.");
        return Collections.singletonList(trip);
    }

    private List<Map<String, String>> currentWishes(Map<String, String> profile) {
        return isOwner(profile) ? db.listWishes(20) : Collections.emptyList();
    }

    private List<Map<String, String>> currentKnowledgePacks(Map<String, String> profile) {
        return db.listKnowledgePacks(KnowledgeProfileKey.forProfile(profile), 40);
    }

    private void applyProactiveResearchSchedule(boolean validatedInternet) {
        if (destroyed || speakerContext == null || locationStore == null) return;
        Map<String, String> profile = currentProfile();
        String personId = profile.getOrDefault(
                "person_id", profile.getOrDefault("name", "unknown_profile"));
        SharedPreferences preferences = getSharedPreferences(
                SettingsActivity.PREFS, MODE_PRIVATE);
        boolean exactOwner = ConfirmedOwnerLease.isExactActiveOwner(this, personId);
        boolean runnable = KnowledgePackSchedulingPolicy.canSchedule(
                    exactOwner,
                    "yes".equals(profile.getOrDefault("memory_consent", "no")),
                    validatedInternet,
                    SarahModelConfig.fullConversationAvailable(),
                    TavilyClient.configured(),
                    locationStore.backgroundResearchEnabled(personId))
                && preferences.getBoolean("web_search", true)
                && SettingsActivity.getConversationMode(this)
                    != ConversationModePolicy.MODE_LOCAL_ONLY;
        if (runnable
                && ConfirmedOwnerLease.isExactActiveOwner(this, personId)) {
            ProactiveDiscoveryScheduler.ensureScheduled(this);
        }
        else ProactiveDiscoveryScheduler.cancel(this);
    }

    private List<Map<String, String>> currentDealWatches(Map<String, String> profile) {
        return isOwner(profile) ? db.listDealWatches(50) : Collections.emptyList();
    }

    private static boolean isOwner(Map<String, String> profile) {
        return "yes".equalsIgnoreCase(profile.getOrDefault("active_speaker_is_owner", "no"))
                || "yes".equalsIgnoreCase(profile.getOrDefault("is_owner", "no"));
    }

    private void setTurnInFlight(boolean inFlight) {
        turnInFlight = inFlight;
        if (input != null) input.setEnabled(!inFlight);
        if (sendButton != null) sendButton.setEnabled(!inFlight);
    }

    private boolean requestMayApply(long requestGeneration) {
        return TurnLifecyclePolicy.completionMayApply(
                destroyed, requestGeneration, lifecycleGeneration.get())
                && !isFinishing();
    }

    private boolean requestMayApplyToSpeaker(
            long requestGeneration,
            String expectedPersonId,
            String expectedSpeaker) {
        return TurnLifecyclePolicy.speakerCompletionMayApply(
                    destroyed,
                    requestGeneration,
                    lifecycleGeneration.get(),
                    expectedPersonId,
                    speakerContext == null ? "" : speakerContext.activePersonId(),
                    expectedSpeaker,
                    speakerContext == null ? "" : speakerContext.activeName())
                && !isFinishing();
    }

    private boolean invalidatePriorSpeakerWork() {
        recordActiveVoiceCancellation("profile_switch_cancelled");
        voiceRequestSequence.incrementAndGet();
        lifecycleGeneration.incrementAndGet();
        CloudVoiceClient.cancel();
        if (tts != null) tts.stop();
        mainHandler.removeCallbacks(deferredKnowledgeRefresh);
        pauseBackgroundResearchForOwnerTurn();
        photoRequestSequence.incrementAndGet();
        pendingLocationGeneration++;
        clearPendingLocationTurn();
        discardPendingPhoto();
        // Background jobs may contain the confirmed owner's private travel
        // state. A speaker change revokes their lease immediately; an owner
        // startup or explicit Settings save can re-establish it later.
        DealWatchScheduler.cancel(this);
        EventMonitorScheduler.cancel(this);
        ProactiveDiscoveryScheduler.cancel(this);
        PersonProfileStore profiles = new PersonProfileStore(this);
        LegacyEventTripOwnerClaimGate.Result claim;
        try {
            Map<String, String> owner = profiles.ensureOwner(db.getProfile());
            Map<String, String> active = profiles.getActiveProfile();
            claim = LegacyEventTripOwnerClaimGate.ensure(this, owner, active);
        } finally {
            profiles.close();
        }
        if (!claim.mayProceed) {
            showLegacyEventClaimBlocked(claim.status);
            return false;
        }
        return true;
    }

    /** Revoke the current speaker's work before ProfileButton changes identity. */
    boolean prepareForProfileSwitch() {
        return invalidatePriorSpeakerWork();
    }

    private void runOnUiThreadIfActive(long requestGeneration, Runnable action) {
        runOnUiThread(() -> {
            if (requestMayApply(requestGeneration)) action.run();
        });
    }

    private void updateSpeakerStatus(String event) {
        if (status == null || speakerContext == null) return;
        String label;
        if (event == null || event.trim().isEmpty() || "Ready".equals(event)) {
            int mode = SettingsActivity.getConversationMode(this);
            String nextRoute = ConversationModePolicy.statusLabel(
                    mode,
                    internetAvailable,
                    SarahModelConfig.fullConversationAvailable(),
                    lastSmartCallFailed,
                    connectedRouteProven,
                    ProtectedBackendCapabilities.isChecking(),
                    reconnecting);
            label = TurnRoute.UNKNOWN_LEGACY.equals(lastTurnRoute)
                    ? nextRoute + " • tap to switch"
                    : "Last reply: " + TurnRoute.sourceLabel(lastTurnRoute)
                        + " • Next: " + nextRoute + " • tap to switch";
        } else {
            label = event;
        }
        StringBuilder text = new StringBuilder(label);
        text.append(" • ").append(speakerContext.activeName());
        if (!speakerContext.ageKnown()) text.append(" • family-friendly");
        status.setText(text.toString());
    }

    private void learnFrom(String text, Map<String, String> profile) {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("learn", true)
                || !"yes".equals(profile.getOrDefault("memory_consent", "no"))) return;
        List<MemoryExtractor.Candidate> candidates = MemoryExtractor.extract(text);
        List<String> saved = new ArrayList<>();
        PersonProfileStore people = new PersonProfileStore(getApplicationContext());
        try {
            people.ensureOwner(db.getProfile());
            String name = profile.getOrDefault("name", speakerContext.activeName());
            for (MemoryExtractor.Candidate candidate : candidates) {
                boolean added = people.addMemory(name, candidate.category, candidate.summary, text);
                if (isOwner(profile)) {
                    added |= db.addMemory(candidate.category, candidate.summary, text);
                }
                if (added) saved.add(candidate.summary);
            }
        } finally {
            people.close();
        }
        if (!saved.isEmpty()) {
            addMemoryNote("Sarah saved in " + speakerContext.activeName() + "’s profile: "
                    + String.join("; ", saved));
        }
    }

    private void addBubble(String who, String text, boolean user) {
        addBubble(who, text, user, user ? "" : TurnRoute.UNKNOWN_LEGACY);
    }

    private void addBubble(String who, String text, boolean user, String route) {
        addBubble(who, text, user, route, "");
    }

    private void addBubble(
            String who,
            String text,
            boolean user,
            String route,
            String sourceDetails) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setGravity(user ? Gravity.END : Gravity.START);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, 6, 0, 6);
        TextView bubble = new TextView(this);
        bubble.setText(who + "\n" + text);
        bubble.setTextSize(16f);
        bubble.setTextColor(getColor(R.color.sarah_text));
        bubble.setBackgroundResource(user ? R.drawable.chat_user : R.drawable.chat_sarah);
        bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.84));
        wrapper.addView(bubble);
        if (!user && route != null && !route.trim().isEmpty()) {
            TextView source = new TextView(this);
            boolean hasDetails = sourceDetails != null && !sourceDetails.trim().isEmpty();
            source.setText(TurnRoute.sourceLabel(route)
                    + (hasDetails ? " · tap for source details" : ""));
            source.setTextSize(11f);
            source.setTextColor(getColor(R.color.sarah_blue));
            source.setPadding(10, 2, 10, 0);
            source.setGravity(Gravity.START);
            if (hasDetails) {
                source.setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle("Reply source details")
                        .setMessage(sourceDetails)
                        .setPositiveButton("Close", null)
                        .show());
            }
            wrapper.addView(source);
        }
        chat.addView(wrapper);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private ConnectedModelResponse connectedReplyWithRetry(
            String providerId,
            String key,
            String model,
            String prompt,
            List<Map<String, String>> history,
            String message,
            boolean web,
            String searchQuery,
            byte[] image) throws Exception {
        long startedAtNanos = System.nanoTime();
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= ConnectedTurnPolicy.ATTEMPTS_PER_TURN; attempt++) {
            long remainingMs = ConnectedTurnPolicy.remainingBudgetMs(
                    startedAtNanos, System.nanoTime());
            if (remainingMs <= 0L) break;
            AtomicReference<Thread> attemptThread = new AtomicReference<>();
            Future<ConnectedModelResponse> attemptFuture = networkAttemptExecutor.submit(() -> {
                attemptThread.set(Thread.currentThread());
                try {
                    return ConnectedModelGateway.respondDetailed(
                            providerId, key, model, prompt, history, message,
                            web, searchQuery, image);
                } finally {
                    attemptThread.set(null);
                }
            });
            try {
                ConnectedModelResponse connected = attemptFuture.get(
                        remainingMs, TimeUnit.MILLISECONDS);
                if (!connected.online) {
                    throw new IllegalStateException(
                            "Connected route returned an offline response without fallback telemetry");
                }
                return connected;
            } catch (TimeoutException deadline) {
                Thread worker = attemptThread.get();
                attemptFuture.cancel(true);
                ConnectedModelGateway.cancel(worker);
                lastFailure = new IllegalStateException(
                        "Connected reply exceeded the shared owner-wait deadline.", deadline);
                break;
            } catch (InterruptedException interrupted) {
                Thread worker = attemptThread.get();
                attemptFuture.cancel(true);
                ConnectedModelGateway.cancel(worker);
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (ExecutionException execution) {
                Throwable cause = execution.getCause();
                lastFailure = cause instanceof Exception
                        ? (Exception) cause
                        : new IllegalStateException("Connected reply failed", cause);
            } catch (Exception failure) {
                lastFailure = failure;
            }
            long remainingAfterFailure = ConnectedTurnPolicy.remainingBudgetMs(
                    startedAtNanos, System.nanoTime());
            if (!ConnectedTurnPolicy.mayRetry(attempt, remainingAfterFailure)) break;
            long backoff = Math.min(
                    ConnectedTurnPolicy.RETRY_BACKOFF_MS,
                    Math.max(0L, remainingAfterFailure - 1L));
            if (backoff > 0L) Thread.sleep(backoff);
        }
        throw new IllegalStateException(
                "The connected mind did not answer within the bounded two-attempt owner wait.",
                lastFailure);
    }

    private void addMemoryNote(String text) {
        TextView note = new TextView(this);
        note.setText(text);
        note.setTextSize(12f);
        note.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        note.setGravity(Gravity.CENTER);
        note.setPadding(8, 4, 8, 4);
        chat.addView(note);
    }

    private boolean needsLiveSearch(String text) {
        String lower = text.toLowerCase(Locale.US);
        return GenericEventReference.looksLikeEvent(text)
                || TripWindowParser.parse(text).found()
                || lower.contains("current")
                || lower.contains("today")
                || lower.contains("this week")
                || lower.contains("next week")
                || lower.contains("next month")
                || lower.contains("tomorrow")
                || lower.contains("weekend")
                || lower.contains("deal")
                || lower.contains("cheapest")
                || lower.contains("lowest cost")
                || lower.contains("low cost")
                || lower.contains("low-cost")
                || lower.contains("least expensive")
                || lower.contains("budget")
                || lower.contains("price")
                || lower.contains("fare")
                || lower.contains("discount")
                || lower.contains("open")
                || lower.contains("hours")
                || lower.contains("weather")
                || lower.contains("event")
                || lower.contains("when is it")
                || lower.contains("what date")
                || lower.contains("ticket")
                || lower.contains("schedule")
                || lower.contains("things to do")
                || lower.contains("places to visit")
                || lower.contains("movie")
                || lower.contains("book about");
    }

    private boolean explicitExploreRequest(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.US);
        return lower.contains("show map")
                || lower.contains("show me a map")
                || lower.contains("show photos")
                || lower.contains("show pictures")
                || lower.contains("show videos")
                || lower.contains("open live search")
                || lower.contains("show fares")
                || lower.contains("open the route")
                || lower.contains("show the route");
    }

    private boolean hasEligiblePersistedEventMonitor(String personId) {
        EventTripStore store = null;
        try {
            store = new EventTripStore(this, personId);
            for (Map<String, String> event : store.listActiveEventTrips(100)) {
                if (!"yes".equals(event.getOrDefault("monitor_enabled", "no"))) continue;
                if (TavilyClient.configured()
                        || KnownEventCatalog.findByEventName(
                                event.getOrDefault("event_name", "")) != null) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (store != null) store.close();
        }
        return false;
    }

    private void speak(String text) {
        speak(text, "voice-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().replace("-", ""));
    }

    private void speak(String text, String turnId) {
        final long voiceRequest = voiceRequestSequence.incrementAndGet();
        // Every new voice request owns both possible playback engines. This
        // closes cloud-to-local and local-to-cloud overlap, while the sequence
        // lease suppresses obsolete fallback callbacks.
        recordActiveVoiceCancellation("superseded_by_new_voice_request");
        CloudVoiceClient.cancel();
        if (tts != null) tts.stop();
        final long voiceGeneration = lifecycleGeneration.get();
        Map<String, String> voiceProfile = currentProfile();
        final String expectedSpeaker = speakerContext.activeName();
        final String personId = voiceProfile.getOrDefault(
                "person_id", speakerContext.activePersonId());
        if (!requestMayApplyToSpeaker(voiceGeneration, personId, expectedSpeaker)) return;
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("auto_speak", true)) return;
        tts.setRate(currentSpeechRate());
        boolean verifiedProtectedVoice = ElevenLabsVoiceConfig.backendConfigured()
                && ProtectedBackendCapabilities.voiceReady(this);
        boolean verifiedDirectVoice = !ElevenLabsVoiceConfig.backendConfigured()
                && ElevenLabsVoiceConfig.directConfigured();
        int voiceMode = prefs.getInt(
                "voice_mode", verifiedProtectedVoice || verifiedDirectVoice ? 1 : 0);
        boolean validatedInternet = connectivityMonitor != null
                ? connectivityMonitor.currentValidatedInternet() : internetAvailable;
        boolean protectedBackend = verifiedProtectedVoice;
        boolean directCredential = verifiedDirectVoice;
        boolean attemptPremium = VoiceRoutePolicy.shouldAttemptPremium(
                voiceMode, validatedInternet, protectedBackend, directCredential);
        String attemptedVoiceRoute = attemptPremium
                ? (protectedBackend ? "elevenlabs_protected_backend" : "elevenlabs_direct")
                : (voiceMode == 1 ? "elevenlabs_approved_route" : "android_tts");
        markActiveVoice(personId, turnId, text.length(), attemptedVoiceRoute);
        if (attemptPremium) {
            updateSpeakerStatus("Generating Sarah’s online voice…");
            CloudVoiceClient.speak(this, "", text, receipt -> {
                if (voiceRequest != voiceRequestSequence.get()) return;
                if (!requestMayApplyToSpeaker(
                        voiceGeneration, personId, expectedSpeaker)) return;
                runOnUiThreadIfActive(voiceGeneration, () -> updateSpeakerStatus(null));
                if (receipt.completed) {
                    recordVoiceReceipt(personId, turnId, text.length(), receipt, "");
                    clearActiveVoice(personId, turnId);
                } else if (!VoiceFallbackPolicy.shouldStartAndroidFallback(
                        receipt.playbackStart, receipt.failureReason)) {
                    String detail = receipt.playbackStart > 0
                            ? "approved progressive playback began; partial route failure recorded; full Android replay suppressed"
                            : "newer voice request owns playback; obsolete Android fallback suppressed";
                    recordVoiceReceipt(personId, turnId, text.length(), receipt, detail);
                    clearActiveVoice(personId, turnId);
                } else {
                    runOnUiThreadIfActive(voiceGeneration, () -> speakLocallyWithReceipt(
                            personId, expectedSpeaker, turnId, text,
                            receipt.attemptedRoute, receipt.failureReason, receipt,
                            voiceGeneration, voiceRequest));
                }
            });
            return;
        }
        speakLocallyWithReceipt(
                personId,
                expectedSpeaker,
                turnId,
                text,
                voiceMode == 1 ? "elevenlabs_approved_route" : "android_tts",
                VoiceRoutePolicy.fallbackReason(
                        voiceMode, validatedInternet, protectedBackend, directCredential),
                null,
                voiceGeneration,
                voiceRequest);
    }

    private void speakLocallyWithReceipt(
            String personId,
            String expectedSpeaker,
            String turnId,
            String text,
            String attemptedRoute,
            String fallbackReason,
            CloudVoiceClient.Receipt failedPremium,
            long voiceGeneration,
            long voiceRequest) {
        if (voiceRequest != voiceRequestSequence.get()) return;
        if (!requestMayApplyToSpeaker(
                voiceGeneration, personId, expectedSpeaker)) return;
        long requestedAt = System.currentTimeMillis();
        AtomicLong playbackStarted = new AtomicLong(0L);
        tts.speak(text, new SarahTts.SpeechListener() {
            @Override public void onStart(long startedAt) {
                if (voiceRequest == voiceRequestSequence.get()
                        && requestMayApplyToSpeaker(
                        voiceGeneration, personId, expectedSpeaker)) {
                    playbackStarted.set(startedAt);
                } else if (tts != null) {
                    tts.stop();
                }
            }

            @Override public void onDone(long completedAt) {
                if (voiceRequest != voiceRequestSequence.get()
                        || !requestMayApplyToSpeaker(
                        voiceGeneration, personId, expectedSpeaker)) return;
                recordLocalVoiceReceipt(
                        personId, turnId, text.length(), attemptedRoute, fallbackReason,
                        requestedAt, playbackStarted.get(), completedAt, true, failedPremium);
                clearActiveVoice(personId, turnId);
            }

            @Override public void onError(long failedAt, String reason) {
                if (voiceRequest != voiceRequestSequence.get()
                        || !requestMayApplyToSpeaker(
                        voiceGeneration, personId, expectedSpeaker)) return;
                recordLocalVoiceReceipt(
                        personId, turnId, text.length(), attemptedRoute,
                        fallbackReason + "; " + reason,
                        requestedAt, playbackStarted.get(), failedAt, false, failedPremium);
                clearActiveVoice(personId, turnId);
            }
        });
    }

    private void recordLocalVoiceReceipt(
            String personId,
            String turnId,
            int characterCount,
            String attemptedRoute,
            String fallbackReason,
            long requestedAt,
            long playbackStart,
            long playbackEnd,
            boolean completed,
            CloudVoiceClient.Receipt failedPremium) {
        CloudVoiceClient.Receipt local = new CloudVoiceClient.Receipt(
                attemptedRoute,
                "android_tts",
                fallbackReason,
                requestedAt,
                requestedAt,
                0L,
                playbackStart,
                playbackStart,
                playbackStart,
                playbackEnd,
                completed);
        recordVoiceReceipt(personId, turnId, characterCount, local, failedPremium == null
                ? "" : "premium_failure_synthesis_start=" + failedPremium.synthesisStart
                        + "; premium_failure_synthesis_end=" + failedPremium.synthesisEnd
                        + "; premium_failure_first_network_byte=" + failedPremium.firstNetworkByte
                        + "; premium_failure_player_ready=" + failedPremium.playerReady
                        + "; premium_failure_response_complete=" + failedPremium.responseComplete
                        + "; premium_failure_playback_start=" + failedPremium.playbackStart
                        + "; premium_failure_playback_end=" + failedPremium.playbackEnd);
    }

    private void recordVoiceReceipt(
            String personId,
            String turnId,
            int characterCount,
            CloudVoiceClient.Receipt receipt,
            String additionalDetail) {
        try {
            JSONObject json = new JSONObject();
            json.put("attempted_route", receipt.attemptedRoute);
            json.put("actual_route", receipt.actualRoute);
            json.put("fallback_reason", receipt.failureReason);
            json.put("requested_at", receipt.requestedAt);
            json.put("synthesis_start", receipt.synthesisStart);
            json.put("first_network_byte", receipt.firstNetworkByte);
            json.put("player_ready", receipt.playerReady);
            json.put("response_complete", receipt.responseComplete);
            // Retained for prior receipt readers; equals response_complete.
            json.put("synthesis_end", receipt.synthesisEnd);
            json.put("playback_start", receipt.playbackStart);
            json.put("playback_end", receipt.playbackEnd);
            json.put("completed", receipt.completed);
            json.put("character_count", characterCount);
            json.put("voice_id", ElevenLabsVoiceConfig.voiceId());
            json.put("voice_model", ElevenLabsVoiceConfig.modelId());
            json.put("detail", additionalDetail == null ? "" : additionalDetail);
            VoiceReceiptStore.append(this, personId, turnId, json);
        } catch (Exception ignored) { }
    }

    private void markActiveVoice(
            String personId,
            String turnId,
            int characterCount,
            String attemptedRoute) {
        activeVoice = new ActiveVoice(
                personId,
                turnId,
                characterCount,
                attemptedRoute,
                System.currentTimeMillis());
    }

    private void clearActiveVoice(String personId, String turnId) {
        ActiveVoice active = activeVoice;
        if (active != null
                && active.personId.equals(personId)
                && active.turnId.equals(turnId)) {
            activeVoice = null;
        }
    }

    /** Keep append-only evidence while suppressing all stale UI and fallback work. */
    private void recordActiveVoiceCancellation(String reason) {
        ActiveVoice active = activeVoice;
        if (active == null) return;
        activeVoice = null;
        long cancelledAt = System.currentTimeMillis();
        CloudVoiceClient.Receipt cancellation = new CloudVoiceClient.Receipt(
                active.attemptedRoute,
                "unknown_cancelled",
                reason,
                active.requestedAt,
                active.requestedAt,
                0L,
                0L,
                0L,
                0L,
                cancelledAt,
                false);
        recordVoiceReceipt(
                active.personId,
                active.turnId,
                active.characterCount,
                cancellation,
                "Active speech was stopped at an exact speaker boundary; playback phase was not yet reported, so it is recorded as unknown_cancelled. Stale playback and fallback were suppressed.");
    }

    private float currentSpeechRate() {
        int value = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE).getInt("speed", 45);
        return 0.70f + (value / 100f) * 0.65f;
    }

    private void startSpeech() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_PERMISSION);
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to Sarah");
        try {
            startActivityForResult(intent, REQ_SPEECH);
        } catch (Exception e) {
            Toast.makeText(this, "No speech recognizer is available on this phone.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean ensureApproximateAreaForTurn(String message) {
        if (!CurrentLocationPolicy.asksForCurrentArea(message) || locationStore == null || locationCoordinator == null) {
            return false;
        }
        Map<String, String> profile = speakerContext.profileFor(db.getProfile());
        String personId = profile.getOrDefault("person_id", speakerContext.activeName());
        if (!locationStore.freshArea(personId, System.currentTimeMillis()).isEmpty()) return false;

        if (!pendingLocationMessage.isEmpty()) {
            Toast.makeText(
                    this,
                    "Sarah is still finding the current area. Your newer message remains in the composer.",
                    Toast.LENGTH_LONG).show();
            return true;
        }
        pendingLocationMessage = message;
        pendingLocationPersonId = personId;
        pendingLocationSpeaker = profile.getOrDefault("name", speakerContext.activeName());
        pendingLocationGeneration++;
        input.setText("");
        if (!locationCoordinator.hasPermission()) {
            SharedPreferences preferences = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
            if (preferences.getBoolean("coarse_location_permission_asked", false)) {
                completeLocationFailure("permission_denied", pendingLocationGeneration);
                return true;
            }
            preferences.edit().putBoolean("coarse_location_permission_asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return true;
        }
        resolvePendingLocation();
        return true;
    }

    private void resolvePendingLocation() {
        final long requestGeneration = pendingLocationGeneration;
        final String personId = pendingLocationPersonId;
        final String initiatingSpeaker = pendingLocationSpeaker;
        updateSpeakerStatus("Finding your approximate current area…");
        locationCoordinator.resolve(new ApproximateLocationCoordinator.Callback() {
            @Override public void onResolved(String area, long capturedAt) {
                if (!locationTurnActive(requestGeneration)) return;
                locationStore.save(
                        personId,
                        area,
                        capturedAt,
                        CurrentLocationPolicy.SOURCE_DEVICE_RESOLVED);
                String message = pendingLocationMessage;
                clearPendingLocationTurn();
                if (!sameActiveProfile(personId, initiatingSpeaker)) {
                    db.addMessage("user", message, initiatingSpeaker, TurnRoute.LOCAL_TOOL_RESULT);
                    db.addMessage(
                            "assistant",
                            "I found the approximate current area for your profile, but I did not send the waiting question after the active phone user changed. Ask it again when your profile is active.",
                            initiatingSpeaker,
                            TurnRoute.LOCAL_TOOL_RESULT);
                    Toast.makeText(
                            MainActivity.this,
                            "Current area was saved to " + initiatingSpeaker
                                    + "'s profile; the waiting message was not sent under another person.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(MainActivity.this, "Using approximate current area: " + area, Toast.LENGTH_SHORT).show();
                input.setText(message);
                sendCurrent();
            }

            @Override public void onUnavailable(String reason) {
                completeLocationFailure(reason, requestGeneration);
            }
        });
    }

    private void completeLocationFailure(String reason, long requestGeneration) {
        if (!locationTurnActive(requestGeneration)) return;
        String message = pendingLocationMessage;
        String personId = pendingLocationPersonId;
        String initiatingSpeaker = pendingLocationSpeaker;
        clearPendingLocationTurn();
        String reply = CurrentLocationPolicy.unavailableReply(reason);
        if (!sameActiveProfile(personId, initiatingSpeaker)) {
            if (!message.isEmpty()) {
                db.addMessage("user", message, initiatingSpeaker, TurnRoute.TOOL_UNAVAILABLE);
            }
            db.addMessage("assistant", reply, initiatingSpeaker, TurnRoute.TOOL_UNAVAILABLE);
            Toast.makeText(
                    this,
                    "The location result stayed with " + initiatingSpeaker
                            + " and was not sent under the newly active profile.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!message.isEmpty()) {
            addBubble(initiatingSpeaker, message, true);
            db.addMessage("user", message, initiatingSpeaker);
        }
        postLocalSarahReply(
                reply,
                "Current area unavailable",
                TurnRoute.TOOL_UNAVAILABLE);
    }

    private boolean locationTurnActive(long requestGeneration) {
        return !destroyed
                && !pendingLocationMessage.isEmpty()
                && pendingLocationGeneration == requestGeneration;
    }

    private boolean sameActiveProfile(String personId, String speaker) {
        if (destroyed || speakerContext == null || db == null) return false;
        Map<String, String> active = speakerContext.profileFor(db.getProfile());
        String activeId = active.getOrDefault("person_id", speakerContext.activeName());
        return personId.equals(activeId)
                && speaker.equalsIgnoreCase(speakerContext.activeName());
    }

    private void clearPendingLocationTurn() {
        pendingLocationMessage = "";
        pendingLocationPersonId = "";
        pendingLocationSpeaker = "";
    }

    private void pickPhoto() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 33) {
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
        }
        startActivityForResult(intent, REQ_PHOTO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_SPEECH) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) input.setText(results.get(0));
        } else if (requestCode == REQ_PHOTO) {
            Uri uri = data.getData();
            if (uri == null) return;
            final String approvedPhotoMime =
                    PrivateContentSnapshot.normalizeApprovedImageMime(
                            getContentResolver().getType(uri));
            if (approvedPhotoMime.isEmpty()) {
                Toast.makeText(
                        this,
                        "Choose a JPEG, PNG, or WebP photo.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            final long photoGeneration = lifecycleGeneration.get();
            final long photoRequest = photoRequestSequence.incrementAndGet();
            Map<String, String> photoProfile = currentProfile();
            final String photoPersonId = photoProfile.getOrDefault(
                    "person_id", speakerContext.activePersonId());
            final String photoSpeaker = speakerContext.activeName();
            updateSpeakerStatus("Cleaning the selected photo…");
            mediaExecutor.submit(() -> {
                final ImageSanitizer.Result[] prepared = new ImageSanitizer.Result[1];
                try {
                    File photoDirectory = new File(getFilesDir(), "photos");
                    ImageSanitizer.Result result;
                    try (PrivateContentSnapshot snapshot = PrivateContentSnapshot.capture(
                            getContentResolver(),
                            uri,
                            getFilesDir(),
                            photoDirectory,
                            PrivateContentSnapshot.MAX_IMAGE_BYTES,
                            "chat_photo",
                            approvedPhotoMime)) {
                        result = ImageSanitizer.sanitize(
                                snapshot.file(),
                                photoDirectory,
                                snapshot.approvedMimeType());
                        prepared[0] = result;
                    }
                    runOnUiThread(() -> {
                        if (photoRequestSequence.get() != photoRequest
                                || !requestMayApplyToSpeaker(
                                    photoGeneration, photoPersonId, photoSpeaker)) {
                            discardSanitizedDerivative(result.file);
                            return;
                        }
                        discardPendingPhoto();
                        pendingPhoto = result.jpeg;
                        pendingPhotoFile = result.file;
                        updateSpeakerStatus("Photo ready — add a question or press send");
                        Toast.makeText(
                                this,
                                "A cleaned copy is ready. Location metadata was not copied into Sarah’s version.",
                                Toast.LENGTH_LONG).show();
                    });
                } catch (Exception e) {
                    String residual = cleanupSanitizedDerivative(
                            prepared[0] == null ? null : prepared[0].file);
                    String detail = "The photo could not be prepared: " + e.getMessage();
                    if (!residual.isEmpty()) {
                        detail += ". A private residual requires owner cleanup at " + residual;
                    }
                    Log.e(TAG, detail, e);
                    final String failure = detail;
                    runOnUiThread(() -> {
                        if (photoRequestSequence.get() == photoRequest
                                && requestMayApplyToSpeaker(
                                    photoGeneration, photoPersonId, photoSpeaker)) {
                            Toast.makeText(
                                    this,
                                    failure,
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
        }
    }

    private void discardPendingPhoto() {
        pendingPhoto = null;
        File file = pendingPhotoFile;
        pendingPhotoFile = null;
        discardSanitizedDerivative(file);
    }

    private void discardSanitizedDerivative(File file) {
        String residual = cleanupSanitizedDerivative(file);
        if (!residual.isEmpty()) {
            Log.e(TAG, "Private sanitized photo cleanup failed at " + residual);
        }
    }

    private String cleanupSanitizedDerivative(File file) {
        if (file == null || !file.exists()) return "";
        try {
            File photoRoot = new File(getFilesDir(), "photos").getCanonicalFile();
            File exactFile = file.getCanonicalFile();
            if (!exactFile.getPath().startsWith(photoRoot.getPath() + File.separator)
                    || !exactFile.isFile()) {
                return exactFile.getCanonicalPath();
            }
            if (!exactFile.delete() || exactFile.exists()) {
                return exactFile.getCanonicalPath();
            }
            return "";
        } catch (Exception e) {
            return file.getAbsolutePath();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startSpeech();
        } else if (requestCode == REQ_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                resolvePendingLocation();
            } else {
                completeLocationFailure("permission_denied", pendingLocationGeneration);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PENDING_LOCATION_MESSAGE, pendingLocationMessage);
        outState.putString(STATE_PENDING_LOCATION_PERSON, pendingLocationPersonId);
        outState.putString(STATE_PENDING_LOCATION_SPEAKER, pendingLocationSpeaker);
        outState.putLong(STATE_PENDING_LOCATION_GENERATION, pendingLocationGeneration);
    }

    @Override
    protected void onDestroy() {
        recordActiveVoiceCancellation("activity_destroyed_or_profile_recreated");
        destroyed = true;
        voiceRequestSequence.incrementAndGet();
        lifecycleGeneration.incrementAndGet();
        photoRequestSequence.incrementAndGet();
        turnInFlight = false;
        pendingLocationGeneration++;
        clearPendingLocationTurn();
        mainHandler.removeCallbacks(deferredKnowledgeRefresh);
        pauseBackgroundResearchForOwnerTurn();
        if (connectivityMonitor != null) connectivityMonitor.stop();
        conversationExecutor.shutdownNow();
        backgroundResearchExecutor.shutdownNow();
        mediaExecutor.shutdownNow();
        networkAttemptExecutor.shutdownNow();
        CloudVoiceClient.cancel();
        if (tts != null) tts.shutdown();
        discardPendingPhoto();
        if (speakerContext != null) speakerContext.close();
        if (locationCoordinator != null) locationCoordinator.close();
        if (db != null) db.close();
        super.onDestroy();
    }
}
