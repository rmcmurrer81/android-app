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
import android.provider.MediaStore;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQ_SPEECH = 1201;
    private static final int REQ_PHOTO = 1202;
    private static final int REQ_AUDIO_PERMISSION = 1203;
    private static final int REQ_NOTIFICATIONS = 1204;

    private SarahDatabase db;
    private SarahTts tts;
    private SpeakerContext speakerContext;
    private LinearLayout chat;
    private ScrollView scroll;
    private EditText input;
    private TextView status;
    private ConnectivityMonitor connectivityMonitor;
    private volatile boolean internetAvailable;
    private volatile boolean lastSmartCallFailed;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private byte[] pendingPhoto;
    private File pendingPhotoFile;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SettingsActivity.ensureAutomaticModeDefault(this);
        db = new SarahDatabase(this);
        DealNotificationManager.createChannel(this);
        if (!db.listActiveDealWatches(1).isEmpty()) DealWatchScheduler.ensureScheduled(this);
        if (!db.hasProfile()) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        chat = findViewById(R.id.chatContainer);
        scroll = findViewById(R.id.chatScroll);
        input = findViewById(R.id.messageInput);
        status = findViewById(R.id.statusText);
        speakerContext = new SpeakerContext(db.getProfile());

        connectivityMonitor = new ConnectivityMonitor(this, connected -> {
            boolean changed = internetAvailable != connected;
            internetAvailable = connected;
            if (connected) lastSmartCallFailed = false;
            runOnUiThread(() -> {
                updateSpeakerStatus(null);
                if (changed) {
                    String message = connected
                            ? (SarahModelConfig.fullConversationAvailable()
                                ? "Internet is back. Sarah will return to OpenAI automatically."
                                : "Internet is back. Sarah can use public lookup, maps, and media; the team OpenAI connection is not included in this build.")
                            : "Connection lost. Sarah is continuing locally.";
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                }
                if (connected) refreshKnowledgeAsync();
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

        ImageButton send = findViewById(R.id.sendButton);
        send.setOnClickListener(v -> sendCurrent());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrent();
                return true;
            }
            return false;
        });

        findViewById(R.id.calmButton).setOnClickListener(v -> showCalmMenu());
        findViewById(R.id.settingsButton).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.notebookButton).setOnClickListener(v -> startActivity(new Intent(this, TravelNotebookActivity.class)));
        findViewById(R.id.micButton).setOnClickListener(v -> startSpeech());
        findViewById(R.id.photoButton).setOnClickListener(v -> pickPhoto());
        updateSpeakerStatus(null);
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
        refreshKnowledgeAsync();
    }

    @Override
    protected void onStop() {
        if (connectivityMonitor != null) connectivityMonitor.stop();
        super.onStop();
    }

    private void showConversationModeMenu() {
        int mode = SettingsActivity.getConversationMode(this);
        String[] choices = {
                "Automatic — OpenAI when included, public/local fallback otherwise",
                "OpenAI preferred — retry the team connection whenever possible",
                "Local only — never use the team model or public lookup",
                "Open detailed settings"
        };
        String current = ConversationModePolicy.statusLabel(
                mode,
                internetAvailable,
                SarahModelConfig.fullConversationAvailable(),
                lastSmartCallFailed);

        new AlertDialog.Builder(this)
                .setTitle("Choose how Sarah connects")
                .setMessage("Current: " + current
                        + "\n\nOpenAI is selected by the app team in the source code. People who install Sarah are not asked to choose a provider, model, or API key. Automatic mode uses the team OpenAI connection when it is included in the APK, public lookup while online when it is not, and the Local Travel Brain without internet.")
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
                                .setTitle("OpenAI is controlled by the app build")
                                .setMessage("This APK does not contain the team OpenAI connection. Public lookup, maps, media, and Local mode remain available. A team member must configure the GitHub build secret or protected backend and build a new APK; there is nothing for the person installing Sarah to enter.")
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
        db.addMessage("assistant", reply, speakerContext.activeName());
        addBubble("Sarah", reply, false);
        updateSpeakerStatus(mode);
        speak(reply);
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
        db.addMessage("assistant", greeting, name);
        addBubble("Sarah", greeting, false);
        speak(greeting);
    }

    private void loadHistory() {
        for (Map<String, String> row : currentHistory(30)) {
            boolean assistant = "assistant".equals(row.get("role"));
            String who = assistant ? "Sarah" : row.getOrDefault("speaker_name", speakerContext.activeName());
            if (who == null || who.trim().isEmpty()) who = speakerContext.activeName();
            addBubble(who, row.get("content"), !assistant);
        }
    }

    private void sendCurrent() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() && pendingPhoto == null) return;
        input.setText("");

        String display = text.isEmpty() ? "What do you think of this trip photo?" : text;
        String speakerBefore = speakerContext.activeName();
        SpeakerContext.Result speakerResult = speakerContext.handle(display);
        String turnSpeaker = speakerResult.messageBelongsToActiveSpeaker
                ? speakerContext.activeName() : speakerBefore;
        addBubble(turnSpeaker, display + (pendingPhoto != null ? "\n[Photo attached]" : ""), true);
        db.addMessage("user", display, turnSpeaker);

        if (speakerResult.handled) {
            String replySpeaker = speakerContext.activeName();
            db.addMessage("assistant", speakerResult.reply, replySpeaker);
            addBubble("Sarah", speakerResult.reply, false);
            updateSpeakerStatus(speakerContext.ageKnown() ? "Profile: " + replySpeaker : "Family-friendly until age is known");
            speak(speakerResult.reply);
            return;
        }

        Map<String, String> profile = currentProfile();
        List<Map<String, String>> historyForActions = currentHistory(12);
        List<Map<String, String>> memoriesForActions = currentMemories(profile);
        AgenticTravelPlanner.Plan proactive = AgenticTravelPlanner.plan(
                display, profile, historyForActions, memoriesForActions);
        AgenticActionExecutor.Result actionResult = AgenticActionExecutor.apply(
                this, db, profile, proactive.actions);
        if (actionResult.createdDealWatch) {
            DealNotificationManager.requestPermissionIfNeeded(this, REQ_NOTIFICATIONS);
        }
        learnFrom(display, profile);

        final byte[] image = pendingPhoto;
        final File imageFile = pendingPhotoFile;
        pendingPhoto = null;
        pendingPhotoFile = null;
        updateSpeakerStatus("Sarah is thinking…");

        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        int mode = SettingsActivity.getConversationMode(this);
        if (connectivityMonitor != null) internetAvailable = connectivityMonitor.currentValidatedInternet();
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
        String prompt = SarahPromptBuilder.build(
                profile, memories, trips, wishes, image != null, web);
        final boolean offerLiveTravelSearch = explicitExploreRequest(display);
        final boolean sourceFirstEvent = internetAvailable
                && mode != ConversationModePolicy.MODE_LOCAL_ONLY
                && (KnownEventCatalog.find(display) != null
                    || !GenericEventReference.recentEvent(history, display).isEmpty());
        final String providerId = SarahModelConfig.PROVIDER_ID;
        final String model = SarahModelConfig.MODEL_ID;
        final String responseSpeaker = speakerContext.activeName();

        executor.submit(() -> {
            String reply;
            boolean smartFallback = false;
            boolean smartSucceeded = false;
            try {
                String sourceBackedEvent = sourceFirstEvent
                        ? PublicOnlineFallback.answer(getApplicationContext(), display, history)
                        : null;
                if (sourceBackedEvent != null && !sourceBackedEvent.trim().isEmpty()) {
                    reply = sourceBackedEvent.trim();
                } else if (useSmart) {
                    reply = ConnectedModelGateway.respond(
                            providerId, key, model, prompt, history, display, web, image);
                    smartSucceeded = true;
                } else {
                    reply = DemoSarah.reply(
                            display, profile, image != null, history, memories,
                            trips, wishes, knowledgePacks, dealWatches);
                }
            } catch (Exception e) {
                reply = DemoSarah.reply(
                        display, profile, image != null, history, memories,
                        trips, wishes, knowledgePacks, dealWatches);
                smartFallback = useSmart;
            }

            String finalReply = reply;
            boolean finalSmartFallback = smartFallback;
            boolean finalSmartSucceeded = smartSucceeded;
            runOnUiThread(() -> {
                if (finalSmartSucceeded) lastSmartCallFailed = false;
                if (finalSmartFallback) lastSmartCallFailed = true;
                db.addMessage("assistant", finalReply, responseSpeaker);
                if (imageFile != null) db.addPhoto(imageFile.getAbsolutePath(), display);
                if (speakerContext.activeName().equalsIgnoreCase(responseSpeaker)) {
                    addBubble("Sarah", finalReply, false);
                    updateSpeakerStatus(null);
                    speak(finalReply);
                } else {
                    Toast.makeText(
                            this,
                            "Sarah saved a reply in " + responseSpeaker + "’s separate conversation.",
                            Toast.LENGTH_SHORT).show();
                }
                if (finalSmartFallback) {
                    Toast.makeText(
                            this,
                            "The team OpenAI connection did not answer, so Sarah continued with public or Local tools. Automatic mode will try OpenAI again on the next message.",
                            Toast.LENGTH_LONG).show();
                }
                if (offerLiveTravelSearch) TravelSearchHelper.show(this, display, profile);
                if (finalSmartSucceeded || actionResult.queuedKnowledge) refreshKnowledgeAsync();
            });
        });
    }

    private void refreshKnowledgeAsync() {
        if (!internetAvailable || !SarahModelConfig.fullConversationAvailable()) return;
        String key = SecureStore.loadApiKey(this);
        executor.submit(() -> {
            SarahDatabase backgroundDb = new SarahDatabase(getApplicationContext());
            try {
                int refreshed = DestinationKnowledgeCoordinator.refreshPending(
                        backgroundDb,
                        SarahModelConfig.PROVIDER_ID,
                        key,
                        SarahModelConfig.MODEL_ID,
                        2);
                if (refreshed > 0) {
                    runOnUiThread(() -> Toast.makeText(
                            this,
                            "Sarah refreshed " + refreshed + " destination knowledge pack"
                                    + (refreshed == 1 ? "." : "s."),
                            Toast.LENGTH_SHORT).show());
                }
            } finally {
                backgroundDb.close();
            }
        });
    }

    private Map<String, String> currentProfile() {
        return speakerContext.profileFor(db.getProfile());
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
        return List.of(trip);
    }

    private List<Map<String, String>> currentWishes(Map<String, String> profile) {
        return isOwner(profile) ? db.listWishes(20) : Collections.emptyList();
    }

    private List<Map<String, String>> currentKnowledgePacks(Map<String, String> profile) {
        List<Map<String, String>> all = db.listKnowledgePacks(40);
        if (isOwner(profile)) return all;
        if (!"going".equals(profile.getOrDefault("current_shared_trip_participation", "unknown"))) {
            return Collections.emptyList();
        }
        String destination = profile.getOrDefault("current_shared_trip", "");
        List<Map<String, String>> filtered = new ArrayList<>();
        for (Map<String, String> row : all) {
            if (destination.equalsIgnoreCase(row.getOrDefault("destination", ""))) filtered.add(row);
        }
        return filtered;
    }

    private List<Map<String, String>> currentDealWatches(Map<String, String> profile) {
        return isOwner(profile) ? db.listDealWatches(50) : Collections.emptyList();
    }

    private static boolean isOwner(Map<String, String> profile) {
        return "yes".equals(profile.getOrDefault("active_speaker_is_owner", "yes"));
    }

    private void updateSpeakerStatus(String event) {
        if (status == null || speakerContext == null) return;
        String label;
        if (event == null || event.trim().isEmpty() || "Ready".equals(event)) {
            int mode = SettingsActivity.getConversationMode(this);
            label = ConversationModePolicy.statusLabel(
                    mode,
                    internetAvailable,
                    SarahModelConfig.fullConversationAvailable(),
                    lastSmartCallFailed) + " • tap to switch";
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
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setGravity(user ? Gravity.END : Gravity.START);
        wrapper.setPadding(0, 6, 0, 6);
        TextView bubble = new TextView(this);
        bubble.setText(who + "\n" + text);
        bubble.setTextSize(16f);
        bubble.setTextColor(getColor(R.color.sarah_text));
        bubble.setBackgroundResource(user ? R.drawable.chat_user : R.drawable.chat_sarah);
        bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.84));
        wrapper.addView(bubble);
        chat.addView(wrapper);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
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

    private void speak(String text) {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("auto_speak", true)) return;
        tts.setRate(currentSpeechRate());
        if (prefs.getInt("voice_mode", 0) == 1) {
            String key = SarahModelConfig.apiKey();
            if (!key.isEmpty() && internetAvailable) {
                CloudVoiceClient.speak(this, key, text, () -> runOnUiThread(() -> tts.speak(text)));
                return;
            }
        }
        tts.speak(text);
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
            updateSpeakerStatus("Cleaning the selected photo…");
            executor.submit(() -> {
                try {
                    ImageSanitizer.Result result = ImageSanitizer.sanitize(
                            getContentResolver(), uri, new File(getFilesDir(), "photos"));
                    pendingPhoto = result.jpeg;
                    pendingPhotoFile = result.file;
                    runOnUiThread(() -> {
                        updateSpeakerStatus("Photo ready — add a question or press send");
                        Toast.makeText(
                                this,
                                "A cleaned copy is ready. Location metadata was not copied into Sarah’s version.",
                                Toast.LENGTH_LONG).show();
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(
                            this,
                            "The photo could not be prepared: " + e.getMessage(),
                            Toast.LENGTH_LONG).show());
                }
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_PERMISSION
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startSpeech();
        }
    }

    @Override
    protected void onDestroy() {
        if (connectivityMonitor != null) connectivityMonitor.stop();
        executor.shutdownNow();
        if (tts != null) tts.shutdown();
        if (speakerContext != null) speakerContext.close();
        if (db != null) db.close();
        super.onDestroy();
    }
}
