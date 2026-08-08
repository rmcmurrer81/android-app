package com.kiraworld.sarahtravel;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Uses Sarah Morgan on ElevenLabs when connected and local Android speech otherwise. */
public final class SarahVoiceRouter {
    public interface Listener { void onStatus(String status); }

    private final Context context;
    private final SarahTts local;
    private final Listener listener;
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
        stopped = false;
        if (ElevenLabsVoiceConfig.isConfigured() && online()) {
            report("Generating ElevenLabs Sarah voice · " + ElevenLabsVoiceConfig.humanModelLabel());
            CloudVoiceClient.speak(context, "", text, receipt -> {
                if (stopped) return;
                if (receipt.completed) {
                    report("ElevenLabs Sarah voice played · " + ElevenLabsVoiceConfig.humanModelLabel());
                } else if (VoiceFallbackPolicy.shouldStartAndroidFallback(
                        receipt.playbackStart,
                        receipt.failureReason)) {
                    report("Online voice unavailable · phone voice fallback started");
                    local.speak(text);
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
