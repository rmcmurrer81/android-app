package com.kiraworld.sarahtravel;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class SarahTts implements TextToSpeech.OnInitListener {
    public interface Listener {
        void onReady(String voiceName);
        void onUnavailable();
    }

    private final TextToSpeech tts;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object songLock = new Object();
    private boolean ready;
    private float rate = 0.95f;
    private String pendingText = "";
    private OfflineSongCatalog.Song pendingSong;
    private Runnable pendingSongComplete;
    private List<OfflineSongCatalog.Line> songLines = List.of();
    private int songIndex;
    private long songGeneration;
    private String activeSongPrefix = "";
    private Runnable songComplete;
    private boolean singing;

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
        tts.setPitch(1.0f);
        tts.setSpeechRate(rate);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) { }

            @Override
            public void onDone(String utteranceId) {
                if (utteranceId != null && utteranceId.startsWith(activeSongPrefix)) {
                    mainHandler.post(SarahTts.this::speakNextSongLine);
                }
            }

            @Override
            public void onError(String utteranceId) {
                if (utteranceId != null && utteranceId.startsWith(activeSongPrefix)) {
                    mainHandler.post(SarahTts.this::finishSong);
                }
            }
        });

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

        if (pendingSong != null) {
            OfflineSongCatalog.Song queuedSong = pendingSong;
            Runnable queuedComplete = pendingSongComplete;
            pendingSong = null;
            pendingSongComplete = null;
            sing(queuedSong, queuedComplete);
        } else if (!pendingText.isEmpty()) {
            String queued = pendingText;
            pendingText = "";
            speak(queued);
        }
    }

    public void setRate(float value) {
        rate = Math.max(0.55f, Math.min(1.35f, value));
        if (ready && !singing) tts.setSpeechRate(rate);
    }

    public void speak(String text) {
        if (text == null || text.trim().isEmpty()) return;
        cancelSong(false);
        if (!ready) {
            pendingText = text.trim();
            return;
        }
        restoreNormalVoice();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sarah_reply_" + System.currentTimeMillis());
    }

    /**
     * Performs a short offline sing-along with the installed Android voice.
     * It changes pitch and rhythm between lines; quality depends on the phone's
     * local TTS engine, but no network, ElevenLabs credit, or downloaded audio
     * is required.
     */
    public void sing(OfflineSongCatalog.Song song, Runnable onComplete) {
        if (song == null || song.lines.isEmpty()) return;
        pendingText = "";
        if (!ready) {
            pendingSong = song;
            pendingSongComplete = onComplete;
            return;
        }
        cancelSong(false);
        synchronized (songLock) {
            songGeneration++;
            activeSongPrefix = "sarah_song_" + songGeneration + "_";
            songLines = song.lines;
            songIndex = 0;
            songComplete = onComplete;
            singing = true;
        }
        tts.stop();
        speakNextSongLine();
    }

    private void speakNextSongLine() {
        OfflineSongCatalog.Line line;
        String utteranceId;
        synchronized (songLock) {
            if (!singing) return;
            if (songIndex >= songLines.size()) {
                finishSong();
                return;
            }
            line = songLines.get(songIndex);
            utteranceId = activeSongPrefix + songIndex;
            songIndex++;
        }
        if (!ready) {
            finishSong();
            return;
        }
        tts.setPitch(line.pitch);
        tts.setSpeechRate(line.rate);
        int result = tts.speak(line.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (result == TextToSpeech.ERROR) finishSong();
    }

    private void finishSong() {
        Runnable complete;
        synchronized (songLock) {
            if (!singing) return;
            singing = false;
            songLines = List.of();
            songIndex = 0;
            activeSongPrefix = "";
            complete = songComplete;
            songComplete = null;
        }
        restoreNormalVoice();
        if (complete != null) mainHandler.post(complete);
    }

    private void cancelSong(boolean runComplete) {
        Runnable complete = null;
        synchronized (songLock) {
            if (singing && runComplete) complete = songComplete;
            singing = false;
            songLines = List.of();
            songIndex = 0;
            activeSongPrefix = "";
            songComplete = null;
            pendingSong = null;
            pendingSongComplete = null;
        }
        if (ready) {
            tts.stop();
            restoreNormalVoice();
        }
        if (complete != null) mainHandler.post(complete);
    }

    private void restoreNormalVoice() {
        if (!ready) return;
        tts.setPitch(1.0f);
        tts.setSpeechRate(rate);
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isSinging() {
        return singing;
    }

    public void stop() {
        pendingText = "";
        cancelSong(false);
        if (ready) tts.stop();
    }

    public void shutdown() {
        pendingText = "";
        pendingSong = null;
        pendingSongComplete = null;
        cancelSong(false);
        tts.shutdown();
    }
}
