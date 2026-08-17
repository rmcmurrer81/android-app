package com.kiraworld.sarahtravel;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/** Uses Sarah Morgan on ElevenLabs when connected and local Android speech otherwise. */
public final class SarahVoiceRouter {
    private final Context context;
    private final SarahTts local;
    public SarahVoiceRouter(Context context, SarahTts local) {
        this.context = context.getApplicationContext(); this.local = local;
    }
    public void speak(String text) {
        if (ElevenLabsVoiceConfig.isConfigured() && online()) {
            CloudVoiceClient.speak(context, "", text, () -> local.speak(text));
        } else local.speak(text);
    }
    public void stop() { local.stop(); }
    private boolean online() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception ignored) { return false; }
    }
}
