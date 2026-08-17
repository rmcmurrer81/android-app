package com.kiraworld.sarahtravel;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.util.concurrent.atomic.AtomicLong;

/** Uses Sarah Morgan on ElevenLabs when connected and local Android speech otherwise. */
public final class SarahVoiceRouter {
    public interface Listener { void onStatus(String status); }

    private final Context context;
    private final SarahTts local;
    private final Listener listener;
    private final AtomicLong requestSequence = new AtomicLong();
    private final android.os.Handler mainHandler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private volatile boolean stopped;
    public SarahVoiceRouter(Context context, SarahTts local) {
        this(context, local, null);
    }
    public SarahVoiceRouter(Context context, SarahTts local, Listener listener) {
        this.context = context.getApplicationContext();
        this.local = local;
        this.listener = listener;
    }
    public void speak(String text) {
        long request = requestSequence.incrementAndGet();
        stopped = false;
        CloudVoiceClient.cancel();
        local.stop();
        boolean protectedReady = ElevenLabsVoiceConfig.backendConfigured()
                && ProtectedBackendCapabilities.voiceReady(context);
        boolean directReady = !ElevenLabsVoiceConfig.backendConfigured()
                && ElevenLabsVoiceConfig.directConfigured();
        if ((protectedReady || directReady) && online()) {
            report("Generating ElevenLabs Sarah voice · " + ElevenLabsVoiceConfig.humanModelLabel());
            CloudVoiceClient.speak(context, "", text, receipt -> {
                if (stopped || request != requestSequence.get()) return;
                if (receipt.completed) {
                    report("ElevenLabs Sarah voice played · " + ElevenLabsVoiceConfig.humanModelLabel());
                } else if (VoiceFallbackPolicy.shouldStartAndroidFallback(
                        receipt.playbackStart,
                        receipt.failureReason)) {
                    mainHandler.post(() -> {
                        if (stopped || request != requestSequence.get()) return;
                        report("Online voice unavailable · phone voice fallback started");
                        local.speak(text);
                    });
                } else if (receipt.playbackStart > 0L) {
                    report("ElevenLabs playback ended early · full phone replay suppressed");
                }
            });
        } else {
            report("Phone voice used · offline or online voice unavailable");
            local.speak(text);
        }
    }
    public void stop() {
        stopped = true;
        requestSequence.incrementAndGet();
        CloudVoiceClient.cancel();
        local.stop();
    }
    private void report(String status) { if (listener != null) listener.onStatus(status); }
    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception ignored) { return false; }
    }
}
