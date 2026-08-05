package com.kiraworld.sarahtravel;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;

import java.util.Comparator;
import java.util.Locale;

public final class SarahTts implements TextToSpeech.OnInitListener {
    private final TextToSpeech tts;
    private boolean ready;
    private float rate = 0.95f;

    public SarahTts(Context context) {
        tts = new TextToSpeech(context.getApplicationContext(), this);
    }

    @Override
    public void onInit(int status) {
        ready = status == TextToSpeech.SUCCESS;
        if (!ready) return;
        tts.setLanguage(Locale.US);
        tts.setSpeechRate(rate);
        try {
            if (tts.getVoices() == null) return;
            Voice selected = tts.getVoices().stream()
                    .filter(v -> v.getLocale() != null && v.getLocale().getLanguage().equals(Locale.ENGLISH.getLanguage()))
                    .filter(v -> !v.isNetworkConnectionRequired())
                    .min(Comparator.comparing(Voice::getName))
                    .orElse(null);
            if (selected != null) tts.setVoice(selected);
        } catch (Exception ignored) { }
    }

    public void setRate(float value) {
        rate = value;
        if (ready) tts.setSpeechRate(rate);
    }

    public void speak(String text) {
        if (!ready || text == null || text.trim().isEmpty()) return;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sarah_reply_" + System.currentTimeMillis());
    }

    public void stop() { if (ready) tts.stop(); }
    public void shutdown() { tts.shutdown(); }
}
