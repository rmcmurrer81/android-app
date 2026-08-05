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
        tts = new SarahTts(this);
        loadHistory();
        if (db.recentMessages(1).isEmpty()) greet();
        ImageButton send = findViewById(R.id.sendButton);
        send.setOnClickListener(v -> sendCurrent());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendCurrent(); return true; }
            return false;
        });
        findViewById(R.id.calmButton).setOnClickListener(v -> showCalmMenu());
        findViewById(R.id.settingsButton).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.notebookButton).setOnClickListener(v -> startActivity(new Intent(this, TravelNotebookActivity.class)));
        findViewById(R.id.micButton).setOnClickListener(v -> startSpeech());
        findViewById(R.id.photoButton).setOnClickListener(v -> pickPhoto());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tts != null) tts.setRate(currentSpeechRate());
    }


    private void showCalmMenu() {
        String[] choices = {"Stay with me through turbulence", "Start personalized trivia", "Five-senses grounding game"};
        new AlertDialog.Builder(this)
                .setTitle("Calm & Trivia")
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) postLocalSarahReply(CalmSupport.turbulenceSupport(db.getProfile()));
                    else if (which == 1) startTriviaGame();
                    else postLocalSarahReply(CalmSupport.groundingSupport());
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void postLocalSarahReply(String reply) {
        db.addMessage("assistant", reply);
        addBubble("Sarah", reply, false);
        status.setText("Offline calm mode");
        speak(reply);
    }

    private void startTriviaGame() {
        List<CalmSupport.Question> questions = CalmSupport.questions(db.getProfile(), db.listTrips(20), db.listWishes(20));
        showTriviaQuestion(questions, 0, 0);
    }

    private void showTriviaQuestion(List<CalmSupport.Question> questions, int index, int score) {
        if (index >= questions.size()) {
            postLocalSarahReply("Trivia finished. You got " + score + " out of " + questions.size() + ". The point was not the score—it was giving your mind somewhere else to stand for a few minutes.");
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
        Map<String, String> p = db.getProfile();
        String name = p.getOrDefault("name", "there");
        String home = p.getOrDefault("hometown", "");
        String greeting = "Hi, " + name + ". I'm Sarah. I know you're from " + home + ", and I can learn the rest of you slowly instead of making you fill out a giant form. We can talk about a trip, a place you dream about, or absolutely nothing travel-related.";
        db.addMessage("assistant", greeting);
        addBubble("Sarah", greeting, false);
        speak(greeting);
    }

    private void loadHistory() {
        for (Map<String, String> row : db.recentMessages(30)) addBubble("assistant".equals(row.get("role")) ? "Sarah" : "You", row.get("content"), !"assistant".equals(row.get("role")));
    }

    private void sendCurrent() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() && pendingPhoto == null) return;
        input.setText("");
        String display = text.isEmpty() ? "What do you think of this trip photo?" : text;
        addBubble("You", display + (pendingPhoto != null ? "\n[Photo attached]" : ""), true);
        db.addMessage("user", display);
        learnFrom(display);
        final byte[] image = pendingPhoto;
        final File imageFile = pendingPhotoFile;
        pendingPhoto = null;
        pendingPhotoFile = null;
        status.setText("Sarah is thinking…");
        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        int provider = prefs.getInt("provider", 0);
        boolean web = prefs.getBoolean("web_search", true) && needsLiveSearch(display);
        Map<String, String> profile = db.getProfile();
        List<Map<String, String>> history = db.recentMessages(12);
        String prompt = SarahPromptBuilder.build(profile, db.listMemories(40), db.listTrips(20), db.listWishes(20), image != null, web);
        executor.submit(() -> {
            String reply;
            try {
                if (provider == 1) {
                    String key = SecureStore.loadApiKey(this);
                    if (key.isEmpty()) throw new IllegalStateException("Open Sarah settings and enter your personal API key, or switch back to Demo mode.");
                    reply = OpenAIClient.respond(key, prefs.getString("model", "gpt-5-mini"), prompt, history, display, web, image);
                } else {
                    reply = DemoSarah.reply(display, profile, image != null);
                }
            } catch (Exception e) {
                reply = "I couldn't reach the conversation model just now. " + e.getMessage();
            }
            String finalReply = reply;
            runOnUiThread(() -> {
                db.addMessage("assistant", finalReply);
                if (imageFile != null) db.addPhoto(imageFile.getAbsolutePath(), display);
                addBubble("Sarah", finalReply, false);
                status.setText(provider == 1 ? "Cloud model" : "Demo mode");
                speak(finalReply);
            });
        });
    }

    private void learnFrom(String text) {
        SharedPreferences p = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        if (!p.getBoolean("learn", true) || !"yes".equals(db.getProfile().get("memory_consent"))) return;
        List<MemoryExtractor.Candidate> candidates = MemoryExtractor.extract(text);
        List<String> saved = new ArrayList<>();
        for (MemoryExtractor.Candidate c : candidates) if (db.addMemory(c.category, c.summary, text)) saved.add(c.summary);
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
        String s = text.toLowerCase(Locale.US);
        return s.contains("current") || s.contains("today") || s.contains("this week") || s.contains("deal") || s.contains("price") || s.contains("fare") || s.contains("discount") || s.contains("open") || s.contains("hours") || s.contains("weather") || s.contains("event") || s.contains("things to do") || s.contains("places to visit") || s.contains("movie") || s.contains("book about");
    }

    private void speak(String text) {
        SharedPreferences p = getSharedPreferences(SettingsActivity.PREFS, MODE_PRIVATE);
        if (!p.getBoolean("auto_speak", true)) return;
        tts.setRate(currentSpeechRate());
        if (p.getInt("voice_mode", 0) == 1) {
            String key = SecureStore.loadApiKey(this);
            if (!key.isEmpty()) { CloudVoiceClient.speak(this, key, text, () -> runOnUiThread(() -> tts.speak(text))); return; }
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
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to Sarah");
        try { startActivityForResult(i, REQ_SPEECH); }
        catch (Exception e) { Toast.makeText(this, "No speech recognizer is available on this phone.", Toast.LENGTH_LONG).show(); }
    }

    private void pickPhoto() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) i = new Intent(MediaStore.ACTION_PICK_IMAGES);
        else { i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE); }
        startActivityForResult(i, REQ_PHOTO);
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
            status.setText("Cleaning the selected photo…");
            executor.submit(() -> {
                try {
                    ImageSanitizer.Result result = ImageSanitizer.sanitize(getContentResolver(), uri, new File(getFilesDir(), "photos"));
                    pendingPhoto = result.jpeg;
                    pendingPhotoFile = result.file;
                    runOnUiThread(() -> { status.setText("Photo ready — add a question or press send"); Toast.makeText(this, "A cleaned copy is ready. Location metadata was not copied into Sarah's version.", Toast.LENGTH_LONG).show(); });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "The photo could not be prepared: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startSpeech();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (tts != null) tts.shutdown();
        if (db != null) db.close();
        super.onDestroy();
    }
}
