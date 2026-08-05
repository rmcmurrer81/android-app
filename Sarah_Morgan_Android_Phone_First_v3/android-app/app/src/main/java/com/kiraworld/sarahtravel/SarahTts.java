package com.kiraworld.sarahtravel;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.Comparator;
import java.util.Locale;

public final class SarahTts implements TextToSpeech.OnInitListener {
    public interface Listener {
        void onReady(String voiceName);
        void onUnavailable();
    }

    private final TextToSpeech tts;
    private final Listener listener;
    private boolean ready;
    private float rate = 0.95f;
    private String pendingText = "";

    public SarahTts(Context context) {
        this(context, null);
    }

    public SarahTts(Context context, Listener listener) {
        this.listener = listener;
        tts = new TextToSpeech(context.getApplicationContext(), this);
    }

    @Override
    public void onInit(int status) {
        ready = status == TextToSpeech.SUCCESS;
        if (!ready) {
            if (listener != null) listener.onUnavailable();
            return;
        }

        int languageResult = tts.setLanguage(Locale.getDefault());
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US);
        }
        tts.setSpeechRate(rate);

        String selectedName = "Android voice";
        try {
            if (tts.getVoices() != null) {
                Voice selected = tts.getVoices().stream()
                        .filter(v -> v.getLocale() != null && v.getLocale().getLanguage().equals(Locale.ENGLISH.getLanguage()))
                        .filter(v -> !v.isNetworkConnectionRequired())
                        .min(Comparator.comparing(Voice::getName))
                        .orElse(null);
                if (selected != null) {
                    tts.setVoice(selected);
                    selectedName = selected.getName();
                }
            }
        } catch (Exception ignored) { }

        if (listener != null) listener.onReady(selectedName);

        if (!pendingText.isEmpty()) {
            String queued = pendingText;
            pendingText = "";
            speak(queued);
        }
    }

    public void setRate(float value) {
        rate = value;
        if (ready) tts.setSpeechRate(rate);
    }

    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (!ready) {
            pendingText = text.trim();
            return;
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sarah_reply_" + System.currentTimeMillis());
    }

    public boolean isReady() {
        return ready;
    }

    public void stop() {
        if (ready) tts.stop();
    }

    public void shutdown() {
        pendingText = "";
        tts.shutdown();
    }
}
