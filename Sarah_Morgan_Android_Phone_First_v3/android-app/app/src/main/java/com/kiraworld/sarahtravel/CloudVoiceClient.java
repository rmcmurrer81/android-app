package com.kiraworld.sarahtravel;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sarah's approved online ElevenLabs voice.
 *
 * Media3 consumes ElevenLabs' progressive MP3 response directly. It does not
 * wait for the complete response or write a complete MP3 to the app cache
 * before beginning playback. Android TTS remains the automatic offline/error
 * fallback owned by MainActivity.
 */
@UnstableApi
public final class CloudVoiceClient {
    private static final AtomicReference<PlaybackSession> ACTIVE_SESSION = new AtomicReference<>();
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_SPOKEN_CHARACTERS = 9000;
    private static final long MAX_STREAM_BYTES = 20_000_000L;
    private static final long MIN_USABLE_STREAM_BYTES = 128L;

    private CloudVoiceClient() { }

    public interface ReceiptListener { void onFinished(Receipt receipt); }

    public static final class Receipt {
        public final String attemptedRoute;
        public final String actualRoute;
        public final String failureReason;
        public final long requestedAt;
        public final long synthesisStart;
        /** Compatibility alias: the exact response-complete time. */
        public final long synthesisEnd;
        public final long firstNetworkByte;
        public final long playerReady;
        public final long responseComplete;
        public final long playbackStart;
        public final long playbackEnd;
        public final boolean completed;

        Receipt(
                String attemptedRoute,
                String actualRoute,
                String failureReason,
                long requestedAt,
                long synthesisStart,
                long firstNetworkByte,
                long playerReady,
                long responseComplete,
                long playbackStart,
                long playbackEnd,
                boolean completed) {
            this.attemptedRoute = attemptedRoute;
            this.actualRoute = actualRoute;
            this.failureReason = failureReason;
            this.requestedAt = requestedAt;
            this.synthesisStart = synthesisStart;
            this.synthesisEnd = responseComplete;
            this.firstNetworkByte = firstNetworkByte;
            this.playerReady = playerReady;
            this.responseComplete = responseComplete;
            this.playbackStart = playbackStart;
            this.playbackEnd = playbackEnd;
            this.completed = completed;
        }
    }

    private static final class StreamingRequest {
        final String endpoint;
        final byte[] body;
        final Map<String, String> headers;

        StreamingRequest(String endpoint, byte[] body, Map<String, String> headers) {
            this.endpoint = endpoint;
            this.body = body;
            this.headers = Collections.unmodifiableMap(new HashMap<>(headers));
        }
    }

    /**
     * A one-connection data source that records real network milestones and
     * enforces the same bounded response size as the old full-buffer path.
     */
    private static final class TimingBoundedDataSource implements DataSource {
        private final DataSource upstream;
        private final AtomicLong firstNetworkByte;
        private final AtomicLong responseComplete;
        private final AtomicLong responseBytes;
        private final boolean requireProtectedRouteReceipt;
        private long expectedBytes = C.LENGTH_UNSET;
        private long bytesThisSource;

        TimingBoundedDataSource(
                DataSource upstream,
                AtomicLong firstNetworkByte,
                AtomicLong responseComplete,
                AtomicLong responseBytes,
                boolean requireProtectedRouteReceipt) {
            this.upstream = upstream;
            this.firstNetworkByte = firstNetworkByte;
            this.responseComplete = responseComplete;
            this.responseBytes = responseBytes;
            this.requireProtectedRouteReceipt = requireProtectedRouteReceipt;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            upstream.addTransferListener(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            expectedBytes = upstream.open(dataSpec);
            String contentType = firstHeader(upstream.getResponseHeaders(), "Content-Type");
            String normalizedType = contentType.toLowerCase(Locale.US);
            if (!(normalizedType.startsWith("audio/mpeg")
                    || normalizedType.startsWith("audio/mp3")
                    || normalizedType.startsWith("application/octet-stream"))) {
                throw new IOException("Voice response content type was not approved audio.");
            }
            if (requireProtectedRouteReceipt) {
                String route = firstHeader(
                        upstream.getResponseHeaders(),
                        "X-Sarah-Voice-Route");
                if (!"elevenlabs-protected".equals(route)) {
                    throw new IOException(
                            "Protected voice response did not prove the approved ElevenLabs route.");
                }
            }
            return expectedBytes;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = upstream.read(buffer, offset, length);
            if (count > 0) {
                long now = System.currentTimeMillis();
                firstNetworkByte.compareAndSet(0L, now);
                bytesThisSource += count;
                long total = responseBytes.addAndGet(count);
                if (total > MAX_STREAM_BYTES) {
                    throw new IOException("Voice response exceeded the 20 MB limit.");
                }
                if (expectedBytes != C.LENGTH_UNSET && bytesThisSource >= expectedBytes) {
                    responseComplete.compareAndSet(0L, now);
                }
            } else if (count == C.RESULT_END_OF_INPUT) {
                responseComplete.compareAndSet(0L, System.currentTimeMillis());
            }
            return count;
        }

        @Override
        public Uri getUri() {
            return upstream.getUri();
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return upstream.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            upstream.close();
        }

        private static String firstHeader(Map<String, List<String>> headers, String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase(name)) continue;
                List<String> values = entry.getValue();
                return values == null || values.isEmpty() || values.get(0) == null
                        ? "" : values.get(0).trim();
            }
            return "";
        }
    }

    private static final class PlaybackSession {
        private final ExoPlayer player;
        private final String attemptedRoute;
        private final long requestedAt;
        private final long synthesisStart;
        private final AtomicLong firstNetworkByte;
        private final AtomicLong playerReady;
        private final AtomicLong responseComplete;
        private final AtomicLong responseBytes;
        private final ReceiptListener listener;
        private final AtomicBoolean reported;
        private final AtomicLong playbackStart = new AtomicLong();

        PlaybackSession(
                ExoPlayer player,
                String attemptedRoute,
                long requestedAt,
                long synthesisStart,
                AtomicLong firstNetworkByte,
                AtomicLong playerReady,
                AtomicLong responseComplete,
                AtomicLong responseBytes,
                ReceiptListener listener,
                AtomicBoolean reported) {
            this.player = player;
            this.attemptedRoute = attemptedRoute;
            this.requestedAt = requestedAt;
            this.synthesisStart = synthesisStart;
            this.firstNetworkByte = firstNetworkByte;
            this.playerReady = playerReady;
            this.responseComplete = responseComplete;
            this.responseBytes = responseBytes;
            this.listener = listener;
            this.reported = reported;
        }

        void markReady() {
            playerReady.compareAndSet(0L, System.currentTimeMillis());
        }

        void markPlaying() {
            playbackStart.compareAndSet(0L, System.currentTimeMillis());
        }

        void complete() {
            if (firstNetworkByte.get() == 0L || responseBytes.get() < MIN_USABLE_STREAM_BYTES) {
                finish(false, "stream_ended_without_usable_audio", System.currentTimeMillis());
            } else if (responseComplete.get() == 0L) {
                finish(false, "stream_ended_before_response_complete", System.currentTimeMillis());
            } else if (playerReady.get() == 0L || playbackStart.get() == 0L) {
                finish(false, "stream_completed_without_playback_start", System.currentTimeMillis());
            } else {
                finish(true, "", System.currentTimeMillis());
            }
        }

        void fail(String reason) {
            finish(false, reason, System.currentTimeMillis());
        }

        void interrupt() {
            runOnMain(() -> finish(
                    false,
                    "interrupted_by_new_voice_request",
                    System.currentTimeMillis()));
        }

        void cancelByLifecycle() {
            runOnMain(() -> finish(
                    false,
                    "cancelled_by_lifecycle",
                    System.currentTimeMillis()));
        }

        private void finish(boolean completed, String reason, long endedAt) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN.post(() -> finish(completed, reason, endedAt));
                return;
            }
            if (!reported.compareAndSet(false, true)) return;
            ACTIVE_SESSION.compareAndSet(this, null);
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            if (listener == null) return;

            long startedAt = playbackStart.get();
            String actualRoute;
            if (completed) {
                actualRoute = attemptedRoute;
            } else if (startedAt > 0L) {
                actualRoute = attemptedRoute + (reason.startsWith("interrupted")
                        ? "_started_interrupted" : "_started_failed");
            } else {
                actualRoute = "not_started";
            }
            listener.onFinished(new Receipt(
                    attemptedRoute,
                    actualRoute,
                    reason,
                    requestedAt,
                    synthesisStart,
                    firstNetworkByte.get(),
                    playerReady.get(),
                    responseComplete.get(),
                    startedAt,
                    endedAt,
                    completed));
        }
    }

    public static void speak(Context context, String ignoredModelKey, String text, Runnable fallback) {
        speak(context, ignoredModelKey, text, receipt -> {
            if (!receipt.completed
                    && VoiceFallbackPolicy.shouldStartAndroidFallback(
                            receipt.playbackStart, receipt.failureReason)
                    && fallback != null) {
                fallback.run();
            }
        });
    }

    /** Stops active/provisional cloud playback and invalidates its preparation generation. */
    public static void cancel() {
        REQUEST_SEQUENCE.incrementAndGet();
        PlaybackSession active = ACTIVE_SESSION.getAndSet(null);
        if (active != null) active.cancelByLifecycle();
    }

    public static void speak(
            Context context,
            String ignoredModelKey,
            String text,
            ReceiptListener listener) {
        Context app = context.getApplicationContext();
        long requestedAt = System.currentTimeMillis();
        long requestGeneration = REQUEST_SEQUENCE.incrementAndGet();
        PlaybackSession active = ACTIVE_SESSION.getAndSet(null);
        if (active != null) active.interrupt();

        new Thread(() -> {
            String attemptedRoute = ElevenLabsVoiceConfig.backendConfigured()
                    ? "elevenlabs_protected_backend" : "elevenlabs_direct";
            AtomicBoolean reported = new AtomicBoolean(false);
            try {
                if (!ElevenLabsVoiceConfig.isConfigured()) {
                    throw new IllegalStateException("ElevenLabs voice is not configured in this build.");
                }
                String spoken = normalizeForSpeech(text);
                if (spoken.isEmpty()) {
                    throw new IllegalArgumentException("Speech text was empty after normalization.");
                }
                if (spoken.length() > MAX_SPOKEN_CHARACTERS) {
                    spoken = spoken.substring(0, MAX_SPOKEN_CHARACTERS);
                }
                StreamingRequest request = buildStreamingRequest(spoken);
                runOnMain(() -> {
                    if (REQUEST_SEQUENCE.get() != requestGeneration) {
                        reportBeforePlayback(
                                attemptedRoute,
                                "superseded_before_playback",
                                requestedAt,
                                0L,
                                listener,
                                reported);
                        return;
                    }
                    startStreaming(
                            app,
                            requestGeneration,
                            request,
                            attemptedRoute,
                            requestedAt,
                            listener,
                            reported);
                });
            } catch (Exception e) {
                reportBeforePlayback(
                        attemptedRoute,
                        REQUEST_SEQUENCE.get() != requestGeneration
                                ? "superseded_before_playback" : boundedReason(e),
                        requestedAt,
                        0L,
                        listener,
                        reported);
            }
        }, "Sarah-ElevenLabs-Voice-Prepare").start();
    }

    private static void startStreaming(
            Context app,
            long requestGeneration,
            StreamingRequest request,
            String attemptedRoute,
            long requestedAt,
            ReceiptListener listener,
            AtomicBoolean reported) {
        if (REQUEST_SEQUENCE.get() != requestGeneration) {
            reportBeforePlayback(
                    attemptedRoute,
                    "superseded_before_playback",
                    requestedAt,
                    0L,
                    listener,
                    reported);
            return;
        }

        long synthesisStart = System.currentTimeMillis();
        AtomicLong firstNetworkByte = new AtomicLong();
        AtomicLong playerReady = new AtomicLong();
        AtomicLong responseComplete = new AtomicLong();
        AtomicLong responseBytes = new AtomicLong();
        AtomicInteger connectionCount = new AtomicInteger();
        ExoPlayer player = null;
        PlaybackSession session = null;
        try {
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent("SarahTravelOS/2.5")
                    .setConnectTimeoutMs(20_000)
                    .setReadTimeoutMs(120_000)
                    .setAllowCrossProtocolRedirects(false)
                    .setKeepPostFor302Redirects(true);

            DataSource.Factory timingFactory = () -> new TimingBoundedDataSource(
                    httpFactory.createDataSource(),
                    firstNetworkByte,
                    responseComplete,
                    responseBytes,
                    "elevenlabs_protected_backend".equals(attemptedRoute));
            ResolvingDataSource.Factory resolvingFactory = new ResolvingDataSource.Factory(
                    timingFactory,
                    dataSpec -> {
                        if (connectionCount.incrementAndGet() != 1) {
                            throw new IOException(
                                    "Duplicate voice synthesis connection was blocked; request was not retried.");
                        }
                        if (dataSpec.position != 0L) {
                            throw new IOException(
                                    "Voice stream range/seek request was blocked; synthesis POST is one-shot.");
                        }
                        return dataSpec.buildUpon()
                                .setUri(request.endpoint)
                                .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                                .setHttpBody(request.body)
                                .setHttpRequestHeaders(request.headers)
                                .build();
                    });

            MediaItem item = new MediaItem.Builder()
                    .setUri(request.endpoint)
                    .setMimeType(MimeTypes.AUDIO_MPEG)
                    .build();
            ProgressiveMediaSource mediaSource = new ProgressiveMediaSource.Factory(resolvingFactory)
                    .setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(0))
                    .createMediaSource(item);

            player = new ExoPlayer.Builder(app).build();
            session = new PlaybackSession(
                    player,
                    attemptedRoute,
                    requestedAt,
                    synthesisStart,
                    firstNetworkByte,
                    playerReady,
                    responseComplete,
                    responseBytes,
                    listener,
                    reported);
            PlaybackSession finalSession = session;
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    if (playbackState == Player.STATE_READY) {
                        finalSession.markReady();
                    } else if (playbackState == Player.STATE_ENDED) {
                        finalSession.complete();
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (isPlaying) finalSession.markPlaying();
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    finalSession.fail(boundedPlayerReason(error));
                }
            });

            PlaybackSession previous = ACTIVE_SESSION.getAndSet(session);
            if (previous != null) previous.interrupt();
            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();
        } catch (Exception e) {
            if (session != null) {
                session.fail(boundedReason(e));
            } else {
                if (player != null) {
                    try { player.release(); } catch (Exception ignored) { }
                }
                reportBeforePlayback(
                        attemptedRoute,
                        boundedReason(e),
                        requestedAt,
                        synthesisStart,
                        listener,
                        reported);
            }
        }
    }

    private static StreamingRequest buildStreamingRequest(String text) throws Exception {
        String endpoint;
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("Accept", "audio/mpeg,application/octet-stream");
        JSONObject body = requestBody(text);
        if (ElevenLabsVoiceConfig.backendConfigured()) {
            endpoint = ElevenLabsVoiceConfig.backendUrl();
            String token = ElevenLabsVoiceConfig.backendToken();
            if (!token.isEmpty()) headers.put("Authorization", "Bearer " + token);
            body.put("voice_id", ElevenLabsVoiceConfig.voiceId());
            body.put("output_format", ElevenLabsVoiceConfig.OUTPUT_FORMAT);
        } else {
            endpoint = "https://api.elevenlabs.io/v1/text-to-speech/"
                    + Uri.encode(ElevenLabsVoiceConfig.voiceId())
                    + "/stream?output_format=" + ElevenLabsVoiceConfig.OUTPUT_FORMAT;
            headers.put("xi-api-key", ElevenLabsVoiceConfig.apiKey());
        }
        if (endpoint == null || !endpoint.startsWith("https://")) {
            throw new IllegalArgumentException("Sarah voice endpoint must use HTTPS.");
        }
        return new StreamingRequest(
                endpoint,
                body.toString().getBytes(StandardCharsets.UTF_8),
                headers);
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

    private static void reportBeforePlayback(
            String attemptedRoute,
            String reason,
            long requestedAt,
            long synthesisStart,
            ReceiptListener listener,
            AtomicBoolean reported) {
        if (!reported.compareAndSet(false, true) || listener == null) return;
        listener.onFinished(new Receipt(
                attemptedRoute,
                "not_started",
                reason,
                requestedAt,
                synthesisStart,
                0L,
                0L,
                0L,
                0L,
                System.currentTimeMillis(),
                false));
    }

    private static String boundedPlayerReason(PlaybackException error) {
        String detail = error.getMessage();
        Throwable cause = error.getCause();
        if ((detail == null || detail.isEmpty()) && cause != null) {
            detail = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }
        return boundedText("exo_player_" + error.getErrorCodeName()
                + (detail == null || detail.isEmpty() ? "" : ": " + detail));
    }

    private static String boundedReason(Exception e) {
        return boundedText(e.getClass().getSimpleName() + ": "
                + (e.getMessage() == null ? "voice request failed" : e.getMessage()));
    }

    private static String boundedText(String value) {
        String result = value == null ? "voice request failed" : value;
        return result.length() > 300 ? result.substring(0, 300) : result;
    }

    private static void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            MAIN.post(action);
        }
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
