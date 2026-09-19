package com.kiraworld.sarahtravel;

import android.content.Context;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Uses Android's on-device speech only.
 *
 * <p>The paid ElevenLabs route was deliberately removed. Sarah's original
 * generated voice is available in the Windows companion after local setup;
 * Android keeps a zero-per-use-cost offline voice when the desktop is absent.</p>
 */
public final class SarahVoiceRouter {
    public interface Listener { void onStatus(String status); }

    private final SarahTts local;
    private final Listener listener;
    private final AtomicLong requestSequence = new AtomicLong();
    private volatile boolean stopped;

    public SarahVoiceRouter(Context context, SarahTts local) {
        this(context, local, null);
    }

    public SarahVoiceRouter(Context context, SarahTts local, Listener listener) {
        this.local = local;
        this.listener = listener;
    }

    public void speak(String text) {
        requestSequence.incrementAndGet();
        stopped = false;
        local.stop();
        report("On-device voice used · no paid voice service");
        if (!stopped) local.speak(text);
    }

    public void stop() {
        stopped = true;
        requestSequence.incrementAndGet();
        local.stop();
    }

    private void report(String status) {
        if (listener != null) listener.onStatus(status);
    }
}
