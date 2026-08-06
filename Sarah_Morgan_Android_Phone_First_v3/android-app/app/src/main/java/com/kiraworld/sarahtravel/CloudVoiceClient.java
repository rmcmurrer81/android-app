package com.kiraworld.sarahtravel;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sarah's premium online voice.
 *
 * The legacy method signature is retained so older MainActivity code can call
 * this class without knowing which provider supplies speech. ElevenLabs is the
 * selected provider. Android TTS remains the automatic offline/error fallback.
 */
public final class CloudVoiceClient {
    private static final AtomicReference<MediaPlayer> ACTIVE_PLAYER = new AtomicReference<>();
    private static final int MAX_SPOKEN_CHARACTERS = 9000;

    private CloudVoiceClient() { }

    public static void speak(Context context, String ignoredModelKey, String text, Runnable fallback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            File file = null;
            try {
                if (!ElevenLabsVoiceConfig.isConfigured()) {
                    throw new IllegalStateException("ElevenLabs voice is not configured in this build.");
                }
                String spoken = normalizeForSpeech(text);
                if (spoken.isEmpty()) return;
                if (spoken.length() > MAX_SPOKEN_CHARACTERS) {
                    spoken = spoken.substring(0, MAX_SPOKEN_CHARACTERS);
                }

                byte[] audio = ElevenLabsVoiceConfig.backendConfigured()
                        ? requestFromTeamBackend(spoken)
                        : requestDirectly(spoken);
                if (audio.length < 128) throw new IllegalStateException("ElevenLabs returned no usable audio.");

                file = new File(app.getCacheDir(), "sarah_elevenlabs_" + System.currentTimeMillis() + ".mp3");
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(audio);
                }

                MediaPlayer old = ACTIVE_PLAYER.getAndSet(null);
                if (old != null) {
                    try { old.stop(); } catch (Exception ignored) { }
                    old.release();
                }

                MediaPlayer player = new MediaPlayer();
                ACTIVE_PLAYER.set(player);
                File finalFile = file;
                player.setDataSource(app, Uri.fromFile(file));
                player.setOnCompletionListener(mp -> release(mp, finalFile));
                player.setOnErrorListener((mp, what, extra) -> {
                    release(mp, finalFile);
                    if (fallback != null) fallback.run();
                    return true;
                });
                player.prepare();
                player.start();
            } catch (Exception e) {
                if (file != null) file.delete();
                if (fallback != null) fallback.run();
            }
        }, "Sarah-ElevenLabs-Voice").start();
    }

    private static byte[] requestDirectly(String text) throws Exception {
        String endpoint = "https://api.elevenlabs.io/v1/text-to-speech/"
                + Uri.encode(ElevenLabsVoiceConfig.voiceId())
                + "/stream?output_format=" + ElevenLabsVoiceConfig.OUTPUT_FORMAT;
        HttpURLConnection connection = open(endpoint);
        connection.setRequestProperty("xi-api-key", ElevenLabsVoiceConfig.apiKey());
        writeJson(connection, requestBody(text));
        return responseBytes(connection);
    }

    private static byte[] requestFromTeamBackend(String text) throws Exception {
        HttpURLConnection connection = open(ElevenLabsVoiceConfig.backendUrl());
        String token = ElevenLabsVoiceConfig.backendToken();
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        JSONObject body = requestBody(text);
        body.put("voice_id", ElevenLabsVoiceConfig.voiceId());
        body.put("output_format", ElevenLabsVoiceConfig.OUTPUT_FORMAT);
        writeJson(connection, body);
        return responseBytes(connection);
    }

    private static JSONObject requestBody(String text) throws Exception {
        JSONObject settings = new JSONObject();
        settings.put("stability", ElevenLabsVoiceConfig.STABILITY);
        settings.put("similarity_boost", ElevenLabsVoiceConfig.SIMILARITY_BOOST);
        settings.put("style", ElevenLabsVoiceConfig.STYLE);
        settings.put("speed", ElevenLabsVoiceConfig.SPEED);
        settings.put("use_speaker_boost", ElevenLabsVoiceConfig.SPEAKER_BOOST);

        JSONObject body = new JSONObject();
        body.put("text", text);
        body.put("model_id", ElevenLabsVoiceConfig.modelId());
        body.put("voice_settings", settings);
        body.put("apply_text_normalization", "auto");
        return body;
    }

    private static HttpURLConnection open(String endpoint) throws Exception {
        if (endpoint == null || !endpoint.startsWith("https://")) {
            throw new IllegalArgumentException("Sarah voice endpoint must use HTTPS.");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(120000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "audio/mpeg,application/octet-stream");
        connection.setRequestProperty("User-Agent", "SarahTravelOS/2.0");
        return connection;
    }

    private static void writeJson(HttpURLConnection connection, JSONObject body) throws Exception {
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] responseBytes(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        byte[] result;
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in != null) {
                byte[] buffer = new byte[8192];
                int count;
                int total = 0;
                while ((count = in.read(buffer)) >= 0) {
                    total += count;
                    if (total > 20_000_000) throw new IllegalStateException("Voice response was too large.");
                    out.write(buffer, 0, count);
                }
            }
            result = out.toByteArray();
        } finally {
            connection.disconnect();
        }
        if (status < 200 || status >= 300) {
            String detail = new String(result, StandardCharsets.UTF_8);
            if (detail.length() > 240) detail = detail.substring(0, 240);
            throw new IllegalStateException("ElevenLabs voice returned " + status + ": " + detail);
        }
        return result;
    }

    private static void release(MediaPlayer player, File file) {
        ACTIVE_PLAYER.compareAndSet(player, null);
        try { player.release(); } catch (Exception ignored) { }
        if (file != null) file.delete();
    }

    private static String normalizeForSpeech(String value) {
        String text = value == null ? "" : value.trim();
        text = text.replaceAll("https?://\\S+", "a link");
        text = text.replace("•", ". ")
                .replace("→", " to ")
                .replace("&", " and ")
                .replaceAll("[*_#`]+", "")
                .replaceAll("\\s+", " ")
                .trim();
        return text;
    }
}
