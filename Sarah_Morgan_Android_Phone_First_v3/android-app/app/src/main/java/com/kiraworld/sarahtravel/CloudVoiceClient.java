package com.kiraworld.sarahtravel;

import android.content.Context;
import android.media.MediaPlayer;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class CloudVoiceClient {
    private CloudVoiceClient() { }

    public static void speak(Context context, String apiKey, String text, Runnable fallback) {
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("model", "gpt-4o-mini-tts");
                body.put("voice", "marin");
                body.put("input", text.length() > 3900 ? text.substring(0, 3900) : text);
                body.put("response_format", "mp3");
                body.put("instructions", "Warm, calm, natural adult voice. Emotionally present and reassuring without sounding clinical or overly cheerful. Medium-slow pace with ordinary conversational variation.");
                HttpURLConnection c = (HttpURLConnection) new URL("https://api.openai.com/v1/audio/speech").openConnection();
                c.setConnectTimeout(30000);
                c.setReadTimeout(120000);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Authorization", "Bearer " + apiKey);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) throw new IllegalStateException("Cloud voice HTTP " + c.getResponseCode());
                File file = new File(context.getCacheDir(), "sarah_voice_" + System.currentTimeMillis() + ".mp3");
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                }
                MediaPlayer player = new MediaPlayer();
                player.setDataSource(file.getAbsolutePath());
                player.setOnCompletionListener(mp -> { mp.release(); file.delete(); });
                player.prepare();
                player.start();
            } catch (Exception e) {
                if (fallback != null) fallback.run();
            }
        }).start();
    }
}
