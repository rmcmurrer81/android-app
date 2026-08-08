package com.kiraworld.sarahtravel;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Map;

/** Owner-facing connection truth; opening this screen performs no network request. */
public final class SponsorConnectionsActivity extends Activity {
    private static final int REQ_LOCATION = 8201;
    private ApproximateLocationCoordinator locationCoordinator;
    private SarahLocationStore locationStore;
    private TextView locationStatus;
    private String activePersonId = "unknown_profile";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        locationCoordinator = new ApproximateLocationCoordinator(this);
        locationStore = new SarahLocationStore(this);
        activePersonId = activePersonId();
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pagePadding = dp(20);
        root.setPadding(pagePadding, pagePadding, pagePadding, pagePadding);
        scroll.addView(root);

        add(root, "Travel Hack NYC connections", 26, true);
        add(root, "Sarah uses each connection honestly. A handoff or search result is never labeled as a completed booking.", 15, false);
        add(root, "ElevenLabs", 20, true);
        boolean protectedVoiceReady = ElevenLabsVoiceConfig.backendConfigured()
                && ProtectedBackendCapabilities.voiceReady(this);
        boolean directVoiceReady = !ElevenLabsVoiceConfig.backendConfigured()
                && ElevenLabsVoiceConfig.directConfigured();
        add(root, protectedVoiceReady || directVoiceReady
                ? "Sarah Morgan’s ElevenLabs voice is verified for this route; Android speech remains the offline fallback."
                : ElevenLabsVoiceConfig.backendConfigured()
                    ? "The protected voice route is configured but not verified right now; Android speech remains ready."
                    : "The protected ElevenLabs voice route is not connected in this build; Android speech remains ready.", 15, false);
        add(root, "Tavily", 20, true);
        add(root, TavilyClient.configured()
                ? "Connected source-backed proactive travel and event discovery is configured."
                : "Research route is implemented, but the protected Tavily key is not included in this build.", 15, false);
        add(root, "Location", 20, true);
        add(root, "Sarah can request coarse location permission and store only the active profile’s resolved city or area, never raw coordinates.", 15, false);
        Button location = new Button(this);
        location.setText("Use my current location");
        location.setAllCaps(false);
        location.setOnClickListener(v -> useCurrentLocation());
        root.addView(location);
        String savedArea = locationStore.freshArea(activePersonId, System.currentTimeMillis());
        locationStatus = add(root, savedArea.isEmpty()
                ? "No current area is saved for this profile."
                : CurrentLocationPolicy.settingsStatus(
                        savedArea, locationStore.source(activePersonId)), 13, false);
        add(root, "Gmail", 20, true);
        add(root, GmailTravelConnection.status() + ". "
                + GmailTravelConnection.setupStatus() + " "
                + GmailTravelConnection.privacySummary(), 15, false);
        Button gmailConnect = new Button(this);
        gmailConnect.setText("Connect Gmail · setup required");
        gmailConnect.setAllCaps(false);
        gmailConnect.setEnabled(false);
        root.addView(gmailConnect);
        Button bookingImport = new Button(this);
        bookingImport.setText("Import a booking you choose to share");
        bookingImport.setAllCaps(false);
        bookingImport.setOnClickListener(v -> startActivity(new Intent(this, BookingImportActivity.class)));
        root.addView(bookingImport);
        Button gmailDisconnect = new Button(this);
        gmailDisconnect.setText("Disconnect Gmail · not connected");
        gmailDisconnect.setAllCaps(false);
        gmailDisconnect.setEnabled(GmailTravelConnection.disconnectAvailable());
        root.addView(gmailDisconnect);
        Button clearGmail = new Button(this);
        clearGmail.setText("Clear Gmail-derived data · none stored");
        clearGmail.setAllCaps(false);
        clearGmail.setEnabled(GmailTravelConnection.gmailDerivedDataExists());
        root.addView(clearGmail);
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

    private TextView add(LinearLayout root, String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, 10, 0, 4);
        root.addView(view);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void useCurrentLocation() {
        if (!locationCoordinator.hasPermission()) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQ_LOCATION);
            return;
        }
        resolveCurrentLocation();
    }

    private void resolveCurrentLocation() {
        locationStatus.setText("Finding an approximate city/area…");
        locationCoordinator.resolve(new ApproximateLocationCoordinator.Callback() {
            @Override public void onResolved(String area, long capturedAt) {
                if (!activePersonId.equals(activePersonId())) {
                    locationStatus.setText(
                            "The active profile changed; the location result was discarded.");
                    return;
                }
                locationStore.save(activePersonId, area, capturedAt,
                        CurrentLocationPolicy.SOURCE_DEVICE_RESOLVED);
                locationStatus.setText(CurrentLocationPolicy.settingsStatus(
                        area, CurrentLocationPolicy.SOURCE_DEVICE_RESOLVED));
            }

            @Override public void onUnavailable(String reason) {
                locationStatus.setText(CurrentLocationPolicy.unavailableReply(reason));
            }
        });
    }

    @Override public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            resolveCurrentLocation();
        } else {
            locationStatus.setText(CurrentLocationPolicy.unavailableReply("permission_denied"));
        }
    }

    private String activePersonId() {
        SarahDatabase ownerDb = new SarahDatabase(this);
        PersonProfileStore people = new PersonProfileStore(this);
        try {
            people.ensureOwner(ownerDb.getProfile());
            Map<String, String> active = people.getActiveProfile();
            return active.getOrDefault(
                    "person_id", active.getOrDefault("name", "unknown_profile"));
        } finally {
            people.close();
            ownerDb.close();
        }
    }

    @Override protected void onDestroy() {
        if (locationCoordinator != null) locationCoordinator.close();
        super.onDestroy();
    }
}
