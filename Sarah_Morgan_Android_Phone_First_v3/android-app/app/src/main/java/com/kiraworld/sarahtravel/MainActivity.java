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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQ_SPEECH = 1201;
    private static final int REQ_PHOTO = 1202;
    private static final int REQ_AUDIO_PERMISSION = 1203;

    private SarahDatabase db;
    private SarahTts tts;
    private SpeakerContext speakerContext;
    private LinearLayout chat;
    private ScrollView scroll;
    private EditText input;
    private TextView status;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private byte[] pendingPhoto;
    private File pendingPhotoFile;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new SarahDatabase(this);
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

        tts = new SarahTts(this, new SarahTts.Listener() {
            @Override
            public void onReady(String voiceName) {
                runOnUiThread(() -> updateSpeakerStatus("Voice ready"));
            }

            @Override
            public void onUnavailable() {
                runOnUiThread(() -> updateSpeakerStatus("Voice unavailable — text works"));
            }
        });

        loadHistory();
        if (db.recentMessages(1).isEmpty()) greet();

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
        updateSpeakerStatus("v0.4 ready");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tts != null) tts.setRate(currentSpeechRate());
        if (speakerContext != null) updateSpeakerStatus("Ready");
    }

    private void showCalmMenu() {
        String[] choices = {"Stay with me through turbulence", "Start personalized trivia", "Five-senses grounding game"};
        new AlertDialog.Builder(this)
                .setTitle("Calm & Trivia")
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) postLocalSarahReply(CalmSupport.turbulenceSupport(currentProfile()), "Offline calm mode");
                    else if (which == 1) startTriviaGame();
                    else postLocalSarahReply(CalmSupport.groundingSupport(), "Offline grounding");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void postLocalSarahReply(String reply, String mode) {
        db.addMessage("assistant", reply);
        addBubble("Sarah", reply, false);
        updateSpeakerStatus(mode);
        speak(reply);
    }

    private void startTriviaGame() {
        List<CalmSupport.Question> questions = CalmSupport.questions(currentProfile(), db.listTrips(20), db.listWishes(20));
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
        Map<String, String> profile = db.getProfile();
        String name = profile.getOrDefault("name", "there");
        String greeting = "I’m glad we met, " + name
                + ". I’m ready to talk about a trip, a place you dream about, or absolutely nothing travel-related.";
        db.addMessage("assistant", greeting);
        addBubble("Sarah", greeting, false);
        speak(greeting);
    }

    private void loadHistory() {
        for (Map<String, String> row : db.recentMessages(30)) {
            boolean assistant = "assistant".equals(row.get("role"));
            addBubble(assistant ? "Sarah" : "You", row.get("content"), !assistant);
        }
    }

    private void sendCurrent() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() && pendingPhoto == null) return;
        input.setText("");

        String display = text.isEmpty() ? "What do you think of this trip photo?" : text;
        String speakerBefore = speakerContext.activeName();
        addBubble(speakerBefore, display + (pendingPhoto != null ? "\n[Photo attached]" : ""), true);

        String storedUserText = speakerContext.isGuest() ? speakerBefore + ": " + display : display;
        db.addMessage("user", storedUserText);

        SpeakerContext.Result speakerResult = speakerContext.handle(display);
        if (speakerResult.handled) {
            db.addMessage("assistant", speakerResult.reply);
            addBubble("Sarah", speakerResult.reply, false);
            updateSpeakerStatus(speakerContext.ageKnown() ? "Speaker changed" : "Family-friendly until age is known");
            speak(speakerResult.reply);
            return;
        }

        if (!speakerContext.isGuest()) learnFrom(display);

        final byte[] image = pendingPhoto;
        final File imageFile = pendingPhotoFile;
        pendingPhoto = null;
        pendingPhotoFile = null;
        updateSpeakerStatus("Sarah is thinking…");

        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        int provider = prefs.getInt("provider", 0);
        boolean web = prefs.getBoolean("web_search", true) && needsLiveSearch(display);
        Map<String, String> profile = currentProfile();
        List<Map<String, String>> history = db.recentMessages(12);
        String prompt = SarahPromptBuilder.build(
                profile,
                db.listMemories(40),
                db.listTrips(20),
                db.listWishes(20),
                image != null,
                web);

        executor.submit(() -> {
            String reply;
            try {
                if (provider == 1) {
                    String key = SecureStore.loadApiKey(this);
                    if (key.isEmpty()) {
                        throw new IllegalStateException("Open Sarah settings and enter your personal API key, or switch back to Demo mode.");
                    }
                    reply = OpenAIClient.respond(
                            key,
                            prefs.getString("model", "gpt-5-mini"),
                            prompt,
                            history,
                            display,
                            web,
                            image);
                } else {
                    reply = DemoSarah.reply(display, profile, image != null);
                }
            } catch (Exception e) {
                reply = "I couldn’t reach the conversation model just now. " + e.getMessage();
            }

            String finalReply = reply;
            runOnUiThread(() -> {
                db.addMessage("assistant", finalReply);
                if (imageFile != null) db.addPhoto(imageFile.getAbsolutePath(), display);
                addBubble("Sarah", finalReply, false);
                updateSpeakerStatus(provider == 1 ? "Cloud model" : "Demo mode");
                speak(finalReply);
            });
        });
    }

    private Map<String, String> currentProfile() {
        return speakerContext.profileFor(db.getProfile());
    }

    private void updateSpeakerStatus(String mode) {
        if (status == null || speakerContext == null) return;
        StringBuilder text = new StringBuilder(mode == null ? "Ready" : mode);
        text.append(" • talking with ").append(speakerContext.activeName());
        if (!speakerContext.ageKnown()) text.append(" • family-friendly");
        status.setText(text.toString());
    }

    private void learnFrom(String text) {
        if (speakerContext.isGuest()) return;
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("learn", true) || !"yes".equals(db.getProfile().get("memory_consent"))) return;

        List<MemoryExtractor.Candidate> candidates = MemoryExtractor.extract(text);
        List<String> saved = new ArrayList<>();
        for (MemoryExtractor.Candidate candidate : candidates) {
            if (db.addMemory(candidate.category, candidate.summary, text)) saved.add(candidate.summary);
        }
        if (!saved.isEmpty()) addMemoryNote("Sarah remembered: " + String.join("; ", saved));
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
        return lower.contains("current")
                || lower.contains("today")
                || lower.contains("this week")
                || lower.contains("deal")
                || lower.contains("price")
                || lower.contains("fare")
                || lower.contains("discount")
                || lower.contains("open")
                || lower.contains("hours")
                || lower.contains("weather")
                || lower.contains("event")
                || lower.contains("things to do")
                || lower.contains("places to visit")
                || lower.contains("movie")
                || lower.contains("book about");
    }

    private void speak(String text) {
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean("auto_speak", true)) return;
        tts.setRate(currentSpeechRate());
        if (prefs.getInt("voice_mode", 0) == 1) {
            String key = SecureStore.loadApiKey(this);
            if (!key.isEmpty()) {
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
                            getContentResolver(),
                            uri,
                            new File(getFilesDir(), "photos"));
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
        executor.shutdownNow();
        if (tts != null) tts.shutdown();
        if (db != null) db.close();
        super.onDestroy();
    }
}
