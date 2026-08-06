package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fully local flight-anxiety companion. It does not call OpenAI, ElevenLabs,
 * public web services, maps, or any other network API.
 */
public final class FlightCalmActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SarahTts tts;
    private TextView sessionText;
    private TextView sessionStatus;
    private Map<String, String> profile = new LinkedHashMap<>();
    private List<Map<String, String>> trips = List.of();
    private List<Map<String, String>> wishes = List.of();
    private int age = -1;
    private int breathingGeneration;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        loadActiveProfile();
        tts = new SarahTts(this);
        buildScreen();
    }

    private void buildScreen() {
        LinearLayout root = TravelUi.page(this);
        String person = profile.getOrDefault("name", "Traveler");
        root.addView(TravelUi.hero(
                this,
                "Works without internet",
                "Offline Flight Companion",
                "Takeoff, turbulence, landing, breathing, conversation, trivia, noticing games and child-friendly sing-alongs for " + person + "."));

        LinearLayout safety = TravelUi.card(this, TravelUi.PEACH);
        safety.addView(TravelUi.cardTitle(this, "✈", "Safety comes first"));
        safety.addView(TravelUi.body(this,
                "Keep the seat belt fastened when required, follow the flight crew and use the phone only as the airline permits. Sarah cannot inspect the aircraft, replace the crew, or decide whether a sound or movement is safe. If something concerns you, ask a crew member."));
        root.addView(safety);

        LinearLayout session = TravelUi.card(this, TravelUi.SKY);
        session.addView(TravelUi.cardTitle(this, "♥", "Sarah stays with you"));
        sessionStatus = TravelUi.body(this, "Ready • all tools on this screen work in airplane mode after installation.");
        session.addView(sessionStatus);
        sessionText = TravelUi.body(this,
                age > 0 && age < 13
                        ? "Choose the part that feels hard, or start with flower-and-candle breathing."
                        : "Choose the part that feels hard, or start with a gentle paced breath.");
        sessionText.setTextSize(17f);
        sessionText.setGravity(Gravity.CENTER_HORIZONTAL);
        session.addView(sessionText);
        session.addView(TravelUi.outlineButton(this, "Stop breathing, game or song", v -> stopEverything("Stopped. Sarah is still here.")));
        root.addView(session);

        root.addView(TravelUi.section(this, "What part of the flight is happening?"));
        LinearLayout phases = TravelUi.card(this, TravelUi.LAVENDER);
        phases.addView(TravelUi.primaryButton(this, "Takeoff support", v -> speakSupport(CalmSupport.takeoffSupport(profile), "Takeoff support")));
        phases.addView(TravelUi.outlineButton(this, "Turbulence support", v -> speakSupport(CalmSupport.turbulenceSupport(profile), "Turbulence support")));
        phases.addView(TravelUi.outlineButton(this, "Landing support", v -> speakSupport(CalmSupport.landingSupport(profile), "Landing support")));
        phases.addView(TravelUi.outlineButton(this, "Just stay with me", v -> speakSupport(CalmSupport.quietCompany(profile), "Quiet company")));
        phases.addView(TravelUi.outlineButton(this, "Tell Sarah what is bothering me", v -> showConcernMenu()));
        root.addView(phases);

        root.addView(TravelUi.section(this, "Breathing and grounding"));
        LinearLayout breathing = TravelUi.card(this, TravelUi.MINT);
        breathing.addView(TravelUi.cardTitle(this, "◌", age > 0 && age < 13
                ? "Flower-and-candle breathing"
                : "Gentle paced breathing"));
        breathing.addView(TravelUi.body(this,
                age > 0 && age < 13
                        ? "Sarah counts a pretend flower breath in and a candle breath out. No breath holding is required."
                        : "Sarah guides a comfortable inhale and a slightly longer exhale. Return to ordinary breathing if counting feels uncomfortable."));
        breathing.addView(TravelUi.primaryButton(this, "Start six slow breaths", v -> startBreathing()));
        breathing.addView(TravelUi.outlineButton(this, "Five-senses grounding", v -> speakSupport(CalmSupport.groundingSupport(), "Grounding")));
        root.addView(breathing);

        root.addView(TravelUi.section(this, "Move attention somewhere else"));
        LinearLayout games = TravelUi.card(this, TravelUi.CREAM);
        games.addView(TravelUi.cardTitle(this, "★", "Offline games"));
        games.addView(TravelUi.body(this,
                "Trivia uses the active profile's age, interests and trip when available. Noticing games use only what is around you in the cabin."));
        games.addView(TravelUi.primaryButton(this, "Start personalized trivia", v -> startTrivia()));
        games.addView(TravelUi.outlineButton(this, "Start a color and noticing hunt", v -> startNoticingGame()));
        games.addView(TravelUi.outlineButton(this, "Start the alphabet travel game", v -> startAlphabetGame()));
        root.addView(games);

        root.addView(TravelUi.section(this, "Sarah's offline sing-alongs"));
        LinearLayout songs = TravelUi.card(this, TravelUi.LAVENDER);
        songs.addView(TravelUi.cardTitle(this, "♫", "Short public-domain children's songs"));
        songs.addView(TravelUi.body(this,
                "Sarah uses the phone's local Android voice with an original pitch-and-rhythm pattern. It may sound more like a gentle sing-song on some phones, but it works without signal or cloud credits."));
        for (OfflineSongCatalog.Song song : OfflineSongCatalog.all()) {
            songs.addView(TravelUi.outlineButton(this, "Sing “" + song.title + "”", v -> startSong(song)));
        }
        root.addView(songs);

        LinearLayout note = TravelUi.card(this, TravelUi.SKY);
        note.addView(TravelUi.cardTitle(this, "i", "For children and shared phones"));
        note.addView(TravelUi.body(this,
                "Sarah reads the active profile's age. Child profiles receive simpler language and games. The screen does not expose the phone owner's private messages, bookings or unrelated memories."));
        root.addView(note);
    }

    private void speakSupport(String text, String label) {
        stopTimedSession();
        sessionStatus.setText(label + " • Android offline voice");
        sessionText.setText(text);
        tts.speak(text);
    }

    private void showConcernMenu() {
        String[] choices = {
                "The sounds are bothering me",
                "The movement or bumps are bothering me",
                "I do not like being out of control",
                "I just want Sarah to talk with me"
        };
        new AlertDialog.Builder(this)
                .setTitle("What feels hardest right now?")
                .setItems(choices, (dialog, which) -> speakSupport(
                        CalmSupport.concernResponse(choices[which], profile),
                        "Talking with Sarah"))
                .setNegativeButton("Close", null)
                .show();
    }

    private void startBreathing() {
        stopTimedSession();
        final boolean child = age > 0 && age < 13;
        final int generation = ++breathingGeneration;
        String intro = child
                ? CalmSupport.childBreathingIntroduction()
                : CalmSupport.adultBreathingIntroduction();
        sessionStatus.setText("Breathing guide • cycle 1 of 6");
        sessionText.setText(intro);
        tts.speak(intro);
        handler.postDelayed(() -> runBreathingPhase(generation, 0, true, child), 4200L);
    }

    private void runBreathingPhase(int generation, int cycle, boolean inhale, boolean child) {
        if (generation != breathingGeneration || isFinishing()) return;
        if (cycle >= 6) {
            sessionStatus.setText("Breathing guide complete");
            sessionText.setText("Six breaths are complete. Notice whether your shoulders, hands or jaw feel even a little different. You can repeat the guide or choose a game.");
            tts.speak("Six breaths are complete. You can repeat the guide or choose a game.");
            return;
        }
        int inSeconds = child ? 3 : 4;
        int outSeconds = child ? 4 : 6;
        int seconds = inhale ? inSeconds : outSeconds;
        String action;
        if (child) {
            action = inhale
                    ? "Smell the flower gently. In: 1, 2, 3."
                    : "Blow out the candle slowly. Out: 1, 2, 3, 4.";
        } else {
            action = inhale
                    ? "Breathe in comfortably. 1, 2, 3, 4."
                    : "Breathe out gently. 1, 2, 3, 4, 5, 6.";
        }
        sessionStatus.setText("Breathing guide • cycle " + (cycle + 1) + " of 6");
        sessionText.setText(action);
        tts.speak(action);
        int nextCycle = inhale ? cycle : cycle + 1;
        boolean nextInhale = !inhale;
        handler.postDelayed(
                () -> runBreathingPhase(generation, nextCycle, nextInhale, child),
                seconds * 1000L + 500L);
    }

    private void startTrivia() {
        stopTimedSession();
        List<CalmSupport.Question> questions = CalmSupport.questions(profile, trips, wishes);
        showTriviaQuestion(questions, 0, 0);
    }

    private void showTriviaQuestion(List<CalmSupport.Question> questions, int index, int score) {
        if (index >= questions.size()) {
            String result = "Trivia finished. You got " + score + " out of " + questions.size()
                    + ". The purpose was to give your attention somewhere else for a while.";
            sessionStatus.setText("Trivia complete");
            sessionText.setText(result);
            tts.speak(result);
            return;
        }
        CalmSupport.Question question = questions.get(index);
        sessionStatus.setText("Trivia • question " + (index + 1) + " of " + questions.size());
        sessionText.setText(question.prompt);
        tts.speak(question.prompt);
        new AlertDialog.Builder(this)
                .setTitle("Trivia " + (index + 1) + " of " + questions.size())
                .setMessage(question.prompt)
                .setItems(question.choices, (dialog, which) -> {
                    boolean correct = which == question.correctIndex;
                    String result = (correct ? "Correct. " : "Not quite. ") + question.explanation;
                    sessionText.setText(result);
                    tts.speak(result);
                    new AlertDialog.Builder(this)
                            .setTitle(correct ? "Correct" : "Answer")
                            .setMessage(result)
                            .setPositiveButton("Next", (d, w) -> showTriviaQuestion(
                                    questions, index + 1, score + (correct ? 1 : 0)))
                            .setNegativeButton("Stop", null)
                            .show();
                })
                .setNegativeButton("Stop", null)
                .show();
    }

    private void startNoticingGame() {
        stopTimedSession();
        List<String> prompts = CalmSupport.noticingPrompts(age);
        showNoticingPrompt(prompts, 0);
    }

    private void showNoticingPrompt(List<String> prompts, int index) {
        if (index >= prompts.size()) {
            speakSupport("You finished the noticing game. Take one ordinary breath and notice that a few minutes have passed.", "Noticing game complete");
            return;
        }
        String prompt = prompts.get(index);
        sessionStatus.setText("Noticing game • " + (index + 1) + " of " + prompts.size());
        sessionText.setText(prompt);
        tts.speak(prompt);
        new AlertDialog.Builder(this)
                .setTitle("Look around")
                .setMessage(prompt)
                .setPositiveButton("Found it", (dialog, which) -> showNoticingPrompt(prompts, index + 1))
                .setNeutralButton("Try another", (dialog, which) -> showNoticingPrompt(prompts, index + 1))
                .setNegativeButton("Stop", null)
                .show();
    }

    private void startAlphabetGame() {
        stopTimedSession();
        String[] categories = age > 0 && age < 10
                ? new String[]{"animals", "foods", "cartoon or story characters", "places"}
                : new String[]{"cities", "foods", "movies or shows", "historical people", "things you pack"};
        new AlertDialog.Builder(this)
                .setTitle("Choose an alphabet category")
                .setItems(categories, (dialog, which) -> startAlphabetRound(categories[which], 0))
                .setNegativeButton("Close", null)
                .show();
    }

    private void startAlphabetRound(String category, int round) {
        char[] letters = {'A', 'B', 'C', 'M', 'P', 'S', 'T'};
        if (round >= letters.length) {
            speakSupport("Alphabet game complete. You found seven answers while the flight kept moving forward.", "Alphabet game complete");
            return;
        }
        String prompt = "Name one " + category + " answer that starts with " + letters[round] + ".";
        sessionStatus.setText("Alphabet game • " + (round + 1) + " of " + letters.length);
        sessionText.setText(prompt);
        tts.speak(prompt);
        new AlertDialog.Builder(this)
                .setTitle("Letter " + letters[round])
                .setMessage(prompt)
                .setPositiveButton("I have one", (dialog, which) -> startAlphabetRound(category, round + 1))
                .setNeutralButton("Skip", (dialog, which) -> startAlphabetRound(category, round + 1))
                .setNegativeButton("Stop", null)
                .show();
    }

    private void startSong(OfflineSongCatalog.Song song) {
        stopTimedSession();
        sessionStatus.setText("Offline sing-along • " + song.title);
        sessionText.setText(song.title + "\n" + song.rightsNote);
        tts.sing(song, () -> {
            sessionStatus.setText("Sing-along complete");
            sessionText.setText("That song is finished. We can sing another one, play a game, or sit quietly together.");
        });
    }

    private void stopEverything(String message) {
        stopTimedSession();
        tts.stop();
        sessionStatus.setText("Ready");
        sessionText.setText(message);
    }

    private void stopTimedSession() {
        breathingGeneration++;
        handler.removeCallbacksAndMessages(null);
        if (tts != null) tts.stop();
    }

    private void loadActiveProfile() {
        SarahDatabase db = new SarahDatabase(getApplicationContext());
        PersonProfileStore people = new PersonProfileStore(getApplicationContext());
        try {
            Map<String, String> owner = db.getProfile();
            people.ensureOwner(owner);
            Map<String, String> active = people.getActiveProfile();
            boolean isOwner = "yes".equals(active.getOrDefault("is_owner", "no"));
            if (isOwner) {
                profile = new LinkedHashMap<>(owner);
                profile.put("name", active.getOrDefault("name", owner.getOrDefault("name", "Traveler")));
                profile.put("age", active.getOrDefault("age", owner.getOrDefault("age", "-1")));
                trips = db.listTrips(20);
                wishes = db.listWishes(20);
            } else {
                profile = new LinkedHashMap<>(active);
                List<Map<String, String>> memories = people.listMemories(
                        active.getOrDefault("name", "Traveler"), 30);
                List<String> interests = new ArrayList<>();
                for (Map<String, String> memory : memories) {
                    String category = memory.getOrDefault("category", "").toLowerCase(Locale.US);
                    if (category.contains("interest")) {
                        String summary = memory.getOrDefault("summary", "").replaceFirst("(?i)^enjoys\\s+", "").trim();
                        if (!summary.isEmpty()) interests.add(summary);
                    }
                }
                profile.put("interests", String.join(", ", interests));
                trips = List.of();
                wishes = List.of();
            }
            age = CalmSupport.parseAge(profile.get("age"));
        } finally {
            people.close();
            db.close();
        }
    }

    @Override
    protected void onDestroy() {
        breathingGeneration++;
        handler.removeCallbacksAndMessages(null);
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
