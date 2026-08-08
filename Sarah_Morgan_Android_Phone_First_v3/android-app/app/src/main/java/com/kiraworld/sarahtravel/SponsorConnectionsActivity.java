package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Owner-facing connection truth; opening this screen performs no network request. */
public final class SponsorConnectionsActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(30, 30, 30, 30);
        scroll.addView(root);

        add(root, "Travel Hack NYC connections", 26, true);
        add(root, "Sarah uses each connection honestly. A handoff or search result is never labeled as a completed booking.", 15, false);
        add(root, "ElevenLabs", 20, true);
        add(root, ElevenLabsVoiceConfig.isConfigured()
                ? "Connected Sarah Morgan online voice is included in this build; Android speech remains the offline fallback."
                : "Voice route is implemented, but this build does not contain the protected ElevenLabs connection.", 15, false);
        add(root, "Tavily", 20, true);
        add(root, TavilyClient.configured()
                ? "Connected source-backed proactive travel and event discovery is configured."
                : "Research route is implemented, but the protected Tavily key is not included in this build.", 15, false);
        add(root, "Gmail", 20, true);
        add(root, "Gmail mailbox access is not connected. Sarah does not read your mailbox. Share an exact booking email, link, text, or screenshot to Sarah from Android when you choose.", 15, false);
        Button bookingImport = new Button(this);
        bookingImport.setText("Import a booking you choose to share");
        bookingImport.setOnClickListener(v -> startActivity(new Intent(this, BookingImportActivity.class)));
        root.addView(bookingImport);
        add(root, "Devices and sync", 20, true);
        add(root, "Pairing is owner initiated. Sarah does not sync until you choose and approve a device.", 15, false);
        Button sync = new Button(this);
        sync.setText("Open devices and sync");
        sync.setOnClickListener(v -> startActivity(new Intent(this, TrustedSyncActivity.class)));
        root.addView(sync);
        add(root, "Stay22", 20, true);
        add(root, "The Stay finder includes an explicitly labeled, user-initiated Stay22 Direct Travel API keyless demo. It sends only the entered destination, traveler and room counts, and complete dates, is limited to 5 requests per minute per network, keeps results temporary, and never treats a listing, quote, or provider link as a booking.", 15, false);
        add(root, "Rove", 20, true);
        add(root, "Sarah can compare rewards-aware travel options and open the official Rove path without claiming an undocumented booking API.", 15, false);
        add(root, "AeroXplorer", 20, true);
        add(root, "Sarah can use aviation news and airline or airport context as sourced talking points, never as aircraft telemetry.", 15, false);
        add(root, "Propellic and Lovable", 20, true);
        add(root, "They are represented in the destination-marketing and product-presentation story. Sarah does not pretend to use a technical API that was not actually connected.", 15, false);
        setContentView(scroll);
        SafeAreaInsets.apply(this, scroll, null, scroll);
    }

    private void add(LinearLayout root, String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, 10, 0, 4);
        root.addView(view);
    }
}
