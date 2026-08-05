package com.kiraworld.sarahtravel;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Year;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OnboardingActivity extends Activity {
    private static final int REQ_SPEECH = 2201;
    private static final int REQ_AUDIO_PERMISSION = 2202;

    private static final int STEP_NAME = 0;
    private static final int STEP_AGE = 1;
    private static final int STEP_HOME = 2;
    private static final int STEP_FLIGHT = 3;
    private static final int STEP_INTERESTS = 4;
    private static final int STEP_WORRIES = 5;
    private static final int STEP_MEMORY = 6;
    private static final int STEP_DONE = 7;

    private SarahDatabase db;
    private SarahTts tts;
    private LinearLayout chat;
    private ScrollView scroll;
    private EditText input;
    private TextView status;
    private LinearLayout composer;
    private Button beginButton;
    private int step = STEP_NAME;

    private String name = "";
    private String home = "";
    private int age = 0;
    private boolean firstFlight = false;
    private String interests = "";
    private String worries = "";
    private boolean memoryConsent = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_onboarding);

        db = new SarahDatabase(this);
        chat = findViewById(R.id.onboardingChat);
        scroll = findViewById(R.id.onboardingScroll);
        input = findViewById(R.id.onboardingInput);
        status = findViewById(R.id.onboardingStatus);
        composer = findViewById(R.id.onboardingComposer);
        beginButton = findViewById(R.id.beginChatButton);

        tts = new SarahTts(this, new SarahTts.Listener() {
            @Override
            public void onReady(String voiceName) {
                runOnUiThread(() -> status.setText("Voice ready • " + voiceName));
            }

            @Override
            public void onUnavailable() {
                runOnUiThread(() -> status.setText("Voice unavailable — text still works"));
            }
        });

        ImageButton send = findViewById(R.id.onboardingSend);
        send.setOnClickListener(v -> submitAnswer());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitAnswer();
                return true;
            }
            return false;
        });
        findViewById(R.id.onboardingMic).setOnClickListener(v -> startSpeech());
        beginButton.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });

        if (state != null) restoreState(state);
        else ask("Hi, I’m Sarah. Nice to meet you. What is your name?");
    }

    private void submitAnswer() {
        String answer = input.getText().toString().trim();
        if (answer.isEmpty()) return;
        input.setText("");
        addBubble("You", answer, true);

        switch (step) {
            case STEP_NAME:
                name = parseName(answer);
                if (name.isEmpty()) {
                    ask("I didn’t catch the name you want me to use. You can say something like, ‘I’m Robert.’");
                    return;
                }
                step = STEP_AGE;
                ask("Nice to meet you, " + name + ". How old are you? You can tell me your age or the year you were born.");
                break;
            case STEP_AGE:
                age = parseAge(answer);
                if (age < 1 || age > 120) {
                    ask("I couldn’t work out your age from that. You can say, for example, ‘I’m 45’ or ‘I was born in 1981.’");
                    return;
                }
                step = STEP_HOME;
                ask("Thanks. Where are you from? A city, state, or country is enough.");
                break;
            case STEP_HOME:
                home = answer;
                step = STEP_FLIGHT;
                ask("Is flying new to you, or have you flown before?");
                break;
            case STEP_FLIGHT:
                Boolean flightAnswer = parseFirstFlight(answer);
                if (flightAnswer == null) {
                    ask("You can say ‘flying is new to me’ or ‘I’ve flown before.’");
                    return;
                }
                firstFlight = flightAnswer;
                step = STEP_INTERESTS;
                ask("What kinds of things do you enjoy? Travel, movies, books, history, food, games—anything is fine. You can also say ‘skip.’");
                break;
            case STEP_INTERESTS:
                interests = isSkip(answer) ? "" : answer;
                step = STEP_WORRIES;
                ask("Is there anything that worries you about travel, or any sensory or accessibility needs I should know? You can say ‘skip.’");
                break;
            case STEP_WORRIES:
                worries = isSkip(answer) ? "" : answer;
                step = STEP_MEMORY;
                ask("May I remember useful preferences, past trips, and places you want to visit on this phone? You can say yes or no, and you can review or delete them later.");
                break;
            case STEP_MEMORY:
                Boolean consent = parseYesNo(answer);
                if (consent == null) {
                    ask("Please say yes or no. I won’t save those personal details if you say no.");
                    return;
                }
                memoryConsent = consent;
                finishOnboarding();
                break;
            default:
                break;
        }
    }

    private void finishOnboarding() {
        step = STEP_DONE;
        db.saveProfile(name, home, age, firstFlight, interests, worries, memoryConsent);
        if (memoryConsent) {
            db.addMemory("profile", "Name: " + name, "First conversation with Sarah");
            db.addMemory("profile", "From: " + home, "First conversation with Sarah");
            db.addMemory("profile", "Age: " + age, "First conversation with Sarah");
            if (firstFlight) db.addMemory("travel_experience", "Flying is new or this may be a first flight", "First conversation with Sarah");
            if (!interests.isEmpty()) db.addMemory("interest", interests, "First conversation with Sarah");
            if (!worries.isEmpty()) db.addMemory("travel_need", worries, "First conversation with Sarah");
        }

        ask("Thank you, " + name + ". That’s enough for now. I’ll learn the rest naturally while we talk.");
        composer.setVisibility(View.GONE);
        beginButton.setVisibility(View.VISIBLE);
        beginButton.requestFocus();
    }

    private void ask(String text) {
        addBubble("Sarah", text, false);
        status.setText(tts != null && tts.isReady() ? "Sarah is speaking" : "Preparing Sarah’s voice…");
        if (tts != null) tts.speak(text);
        updateHint();
    }

    private void updateHint() {
        String hint;
        switch (step) {
            case STEP_NAME: hint = "Tell Sarah your name"; break;
            case STEP_AGE: hint = "Age or birth year"; break;
            case STEP_HOME: hint = "City, state, or country"; break;
            case STEP_FLIGHT: hint = "New to flying, or flown before?"; break;
            case STEP_INTERESTS: hint = "Things you enjoy, or skip"; break;
            case STEP_WORRIES: hint = "Worries or needs, or skip"; break;
            case STEP_MEMORY: hint = "Yes or no"; break;
            default: hint = "";
        }
        input.setHint(hint);
    }

    private void addBubble(String who, String text, boolean user) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setGravity(user ? Gravity.END : Gravity.START);
        wrapper.setPadding(0, 7, 0, 7);

        TextView bubble = new TextView(this);
        bubble.setText(who + "\n" + text);
        bubble.setTextSize(17f);
        bubble.setTextColor(getColor(R.color.sarah_text));
        bubble.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        bubble.setBackgroundResource(user ? R.drawable.chat_user : R.drawable.chat_sarah);
        bubble.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.86));
        wrapper.addView(bubble);
        chat.addView(wrapper);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void startSpeech() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_PERMISSION);
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, input.getHint());
        try {
            startActivityForResult(intent, REQ_SPEECH);
        } catch (Exception e) {
            Toast.makeText(this, "No speech recognizer is available on this phone.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK && data != null) {
            ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                input.setText(results.get(0));
                submitAnswer();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_PERMISSION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startSpeech();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("step", step);
        out.putString("name", name);
        out.putString("home", home);
        out.putInt("age", age);
        out.putBoolean("firstFlight", firstFlight);
        out.putString("interests", interests);
        out.putString("worries", worries);
        out.putBoolean("memoryConsent", memoryConsent);
    }

    private void restoreState(Bundle state) {
        step = state.getInt("step", STEP_NAME);
        name = state.getString("name", "");
        home = state.getString("home", "");
        age = state.getInt("age", 0);
        firstFlight = state.getBoolean("firstFlight", false);
        interests = state.getString("interests", "");
        worries = state.getString("worries", "");
        memoryConsent = state.getBoolean("memoryConsent", true);
        ask(questionForStep());
        if (step == STEP_DONE) {
            composer.setVisibility(View.GONE);
            beginButton.setVisibility(View.VISIBLE);
        }
    }

    private String questionForStep() {
        switch (step) {
            case STEP_NAME: return "Hi, I’m Sarah. Nice to meet you. What is your name?";
            case STEP_AGE: return "How old are you? You can tell me your age or the year you were born.";
            case STEP_HOME: return "Where are you from? A city, state, or country is enough.";
            case STEP_FLIGHT: return "Is flying new to you, or have you flown before?";
            case STEP_INTERESTS: return "What kinds of things do you enjoy? You can also say ‘skip.’";
            case STEP_WORRIES: return "Any travel worries, sensory needs, or accessibility needs? You can say ‘skip.’";
            case STEP_MEMORY: return "May I remember useful preferences and trips on this phone? Yes or no?";
            default: return "We’re ready to start talking.";
        }
    }

    private static String parseName(String answer) {
        String value = answer.trim();
        value = value.replaceFirst("(?i)^(?:hi|hello|hey)(?:\\s+sarah)?[,! ]*", "");
        value = value.replaceFirst("(?i)^(?:I['’]?m|I am|my name is|call me)\\s+", "");
        value = value.replaceAll("[.!?]+$", "").trim();
        if (value.length() > 50) return "";
        return value;
    }

    private static int parseAge(String answer) {
        String value = answer.trim();
        Matcher year = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b").matcher(value);
        if (year.find()) {
            int birthYear = parseInt(year.group(1));
            int result = Year.now().getValue() - birthYear;
            if (result >= 1 && result <= 120) return result;
        }
        Matcher age = Pattern.compile("\\b(\\d{1,3})\\b").matcher(value);
        if (age.find()) {
            int result = parseInt(age.group(1));
            if (result >= 1 && result <= 120) return result;
        }
        return 0;
    }

    private static Boolean parseFirstFlight(String answer) {
        String lower = answer.toLowerCase(Locale.US);
        if (lower.contains("never") || lower.contains("new") || lower.contains("first")) return true;
        if (lower.contains("before") || lower.contains("have flown") || lower.contains("i've flown") || lower.contains("not new")) return false;
        return parseYesNo(answer);
    }

    private static Boolean parseYesNo(String answer) {
        String lower = answer.toLowerCase(Locale.US).trim();
        if (lower.matches("^(yes|yeah|yep|sure|okay|ok|please do|you can).*$")) return true;
        if (lower.matches("^(no|nope|don['’]?t|do not|please don['’]?t).*$")) return false;
        return null;
    }

    private static boolean isSkip(String answer) {
        String lower = answer.toLowerCase(Locale.US).trim();
        return lower.equals("skip") || lower.equals("none") || lower.equals("nothing") || lower.equals("not right now");
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value); }
        catch (Exception ignored) { return 0; }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        if (db != null) db.close();
        super.onDestroy();
    }
}
