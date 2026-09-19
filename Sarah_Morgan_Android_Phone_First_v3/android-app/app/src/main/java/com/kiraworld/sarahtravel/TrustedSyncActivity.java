package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Owner-facing discovery and explicit two-screen identity proof. */
public final class TrustedSyncActivity extends Activity {
    private final List<SarahDeviceDiscovery.Peer> discoveredPeers = new ArrayList<>();
    private TextView status;
    private Spinner discovered;
    private ArrayAdapter<String> discoveredAdapter;
    private Spinner saved;
    private ArrayAdapter<String> savedAdapter;
    private volatile boolean working;
    private SarahPairingTransport.Pending pending;
    private SarahReverseSyncResponder reverseResponder;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        root.setBackgroundColor(Color.rgb(247, 249, 250));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable headerBackground = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(11, 27, 43), Color.rgb(23, 106, 123)});
        headerBackground.setCornerRadius(dp(22));
        header.setBackground(headerBackground);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_sarah_orbit);
        header.addView(logo, new LinearLayout.LayoutParams(dp(54), dp(54)));
        TextView title = new TextView(this);
        title.setText("Your Sarah devices");
        title.setTextColor(Color.WHITE);
        title.setTextSize(23);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header, fullWidth(dp(6)));

        TextView note = new TextView(this);
        note.setText("Sarah can notice another Sarah app on the same private Wi-Fi, but finding it does not prove it is yours. You must see the same short-lived code and approve it on both devices. Discovery and pairing never copy your profile, email, model access, passwords, or provider credentials.");
        note.setTextSize(15);
        note.setTextColor(Color.rgb(35, 52, 65));
        note.setPadding(dp(4), dp(14), dp(4), dp(12));
        root.addView(note);

        status = new TextView(this);
        status.setText("Ready to look for Sarah on your private Wi-Fi.");
        status.setTextSize(15);
        status.setTextColor(Color.rgb(15, 72, 88));
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable statusBackground = new GradientDrawable();
        statusBackground.setColor(Color.rgb(226, 246, 249));
        statusBackground.setCornerRadius(dp(16));
        status.setBackground(statusBackground);
        root.addView(status, fullWidth(dp(12)));

        addSection(root, "Sarah devices found on this Wi-Fi");
        discovered = new Spinner(this);
        discoveredAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new ArrayList<>());
        discovered.setAdapter(discoveredAdapter);
        root.addView(discovered, fullWidth(dp(4)));

        LinearLayout discoveryButtons = new LinearLayout(this);
        discoveryButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button scan = button("Find my devices");
        scan.setOnClickListener(view -> scanDevices());
        discoveryButtons.addView(scan, weighted());
        Button request = button("Is this your device?");
        request.setOnClickListener(view -> requestSelected());
        discoveryButtons.addView(request, weighted());
        root.addView(discoveryButtons, fullWidth(dp(8)));

        TextView boundary = new TextView(this);
        boundary.setText("Pairing establishes one revocable device credential. On this build, a newly paired Android phone may pull an encrypted preview from an established Windows Sarah and decide whether to import profile continuity, approved memories, trips, and conversation history. Nothing imports automatically. A new Windows installation cannot yet pull from an established Android phone and must fail closed rather than imply it synced.");
        boundary.setTextSize(14);
        boundary.setTextColor(Color.DKGRAY);
        boundary.setPadding(dp(4), dp(4), dp(4), dp(8));
        root.addView(boundary);

        addSection(root, "Devices you approved");
        saved = new Spinner(this);
        savedAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new ArrayList<>());
        saved.setAdapter(savedAdapter);
        root.addView(saved, fullWidth(dp(3)));
        Button reviewContinuity = button("Review continuity from selected device");
        reviewContinuity.setOnClickListener(view -> offerContinuityPreview(selectedSavedHost()));
        root.addView(reviewContinuity, fullWidth(dp(3)));
        Button revoke = button("Remove approval for selected device");
        revoke.setOnClickListener(view -> revokeSelected());
        root.addView(revoke, fullWidth(dp(3)));

        refreshSaved();
        setContentView(scroll);
        SafeAreaInsets.apply(this, scroll, null, scroll);
        startReverseResponder();

        String peerHost = getIntent().getStringExtra("peer_host");
        String peerInstance = getIntent().getStringExtra("peer_instance_id");
        if (peerHost != null && !peerHost.trim().isEmpty()
                && peerInstance != null && !peerInstance.trim().isEmpty()) {
            try {
                SarahDeviceDiscovery.Peer peer = new SarahDeviceDiscovery.Peer(
                        peerHost,
                        getIntent().getStringExtra("peer_name"),
                        peerInstance,
                        getIntent().getStringExtra("peer_device_type"),
                        getIntent().getIntExtra("peer_port", 0),
                        getIntent().getLongExtra("peer_expires_at", 0L));
                setDiscoveredPeers(Collections.singletonList(peer));
                root.postDelayed(this::requestSelected, 250L);
            } catch (Exception ignored) {
                status.setText("That discovery notice is no longer valid. Scan again.");
            }
        } else {
            root.postDelayed(this::scanDevices, 250L);
        }
    }

    @Override protected void onDestroy() {
        SarahPairingTransport.Pending active = pending;
        pending = null;
        if (active != null) active.close();
        SarahReverseSyncResponder responder = reverseResponder;
        reverseResponder = null;
        if (responder != null) responder.close();
        super.onDestroy();
    }

    private void startReverseResponder() {
        try {
            reverseResponder = new SarahReverseSyncResponder(this, new SarahReverseSyncResponder.Listener() {
                @Override public void onPairingPending(SarahReverseSyncResponder.Pending request) {
                    runOnUiThread(() -> new AlertDialog.Builder(TrustedSyncActivity.this)
                            .setTitle("Connect this Windows Sarah?")
                            .setMessage(request.deviceName + " is asking to connect. Continue only if that Windows computer shows the same code: "
                                    + request.sasCode + ". No profile or account data has moved.")
                            .setPositiveButton("Codes match - approve", (dialog, which) -> request.approve())
                            .setNegativeButton("Reject", (dialog, which) -> request.reject())
                            .setOnCancelListener(dialog -> request.reject())
                            .show());
                }
                @Override public void onTrusted(String deviceName) {
                    runOnUiThread(() -> {
                        refreshSaved();
                        status.setText(deviceName + " is trusted after matching approval on both screens. Windows may now request an encrypted preview; Android sends nothing until that authenticated request.");
                    });
                }
                @Override public void onError(String message) {
                    runOnUiThread(() -> status.setText(message));
                }
            });
            int port = reverseResponder.start();
            status.setText("Ready to find Sarah devices and to answer an owner-approved Windows pairing request on private Wi-Fi (secure port " + port + ").");
        } catch (Exception error) {
            reverseResponder = null;
            status.setText("Android could not offer reverse device setup: " + safe(error)
                    + ". Existing local data remains unchanged.");
        }
    }

    private void scanDevices() {
        if (working || pending != null) return;
        working = true;
        status.setText("Sarah is looking on this private Wi-Fi...");
        new Thread(() -> {
            try {
                List<SarahDeviceDiscovery.Peer> peers = SarahDeviceDiscovery.discover(this, 3000);
                runOnUiThread(() -> {
                    setDiscoveredPeers(peers);
                    status.setText(peers.isEmpty()
                            ? "No other Sarah device answered. Open Sarah's Devices screen on the other device and keep both devices on the same private Wi-Fi."
                            : "Sarah found " + peers.size() + " device(s). Select one and ask whether it is yours.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText(
                        "Discovery could not finish: " + safe(error)));
            } finally {
                working = false;
            }
        }, "Sarah-Device-Discovery-V2").start();
    }

    private void requestSelected() {
        SarahDeviceDiscovery.Peer peer = selectedPeer();
        if (peer == null || working || pending != null) {
            if (peer == null) status.setText("Find and select a Sarah device first.");
            return;
        }
        if (System.currentTimeMillis() / 1000L > peer.expiresAt) {
            status.setText("That discovery notice expired. Scan again.");
            return;
        }
        if (peer.pairingPort <= 0) {
            status.setText(peer.name + " was found, but that build is not accepting secure pairing yet. Nothing was shared.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Is this your device?")
                .setMessage(peer.name + " at " + peer.host
                        + " is running Sarah on this Wi-Fi. Continue only if you recognize it. Sarah will next show a short-lived code on both devices; finding the device alone transfers nothing.")
                .setPositiveButton("Yes - compare codes", (dialog, which) -> beginPairing(peer))
                .setNegativeButton("No", null)
                .show();
    }

    private void beginPairing(SarahDeviceDiscovery.Peer peer) {
        if (working || pending != null) return;
        working = true;
        status.setText("Opening a short-lived encrypted identity check with " + peer.name + "...");
        new Thread(() -> {
            try {
                String manufacturer = android.os.Build.MANUFACTURER == null
                        ? "Android" : android.os.Build.MANUFACTURER.trim();
                String model = android.os.Build.MODEL == null
                        ? "phone" : android.os.Build.MODEL.trim();
                SarahPairingTransport.Pending created = SarahPairingTransport.begin(
                        peer,
                        SarahPairingProtocol.newInstanceId(),
                        (manufacturer + " " + model).trim(),
                        "android-phone");
                runOnUiThread(() -> showMatchingCode(created));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText(
                        "Pairing did not start: " + safe(error) + ". Nothing was shared."));
            } finally {
                working = false;
            }
        }, "Sarah-Pairing-Begin").start();
    }

    private void showMatchingCode(SarahPairingTransport.Pending created) {
        if (isFinishing()) {
            created.close();
            return;
        }
        pending = created;
        status.setText("Compare the code on both devices: " + created.sasCode);
        new AlertDialog.Builder(this)
                .setTitle("Do both devices show " + created.sasCode + "?")
                .setMessage("Go to " + created.peer.name
                        + " and confirm that it shows this exact code. Approve there too. If either code differs, choose No and no credential will be saved.")
                .setPositiveButton("Yes - approve on this phone", (dialog, which) -> completePairing(created))
                .setNegativeButton("No - cancel", (dialog, which) -> cancelPairing(created))
                .setOnCancelListener(dialog -> cancelPairing(created))
                .show();
    }

    private void completePairing(SarahPairingTransport.Pending active) {
        if (working || pending != active) return;
        working = true;
        status.setText("Waiting for the matching approval on " + active.peer.name + "...");
        new Thread(() -> {
            try {
                SarahPairingProtocol.Credential credential = active.complete(true);
                if (!TrustedDeviceStore.saveFinalizedPeer(
                        this, active.peer.host, active.peer.pairingPort, credential)) {
                    throw new SecurityException(
                            "Android Keystore could not verify the saved device credential");
                }
                runOnUiThread(() -> {
                    pending = null;
                    working = false;
                    refreshSaved();
                    status.setText("Both devices proved the same short-lived code. Device approval is saved securely. Review the separate continuity preview before importing anything.");
                    new android.os.Handler(getMainLooper()).postDelayed(
                            () -> offerContinuityPreview(active.peer.host), 750L);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    pending = null;
                    status.setText("Pairing was not completed: " + safe(error)
                            + ". No device credential was saved.");
                });
            } finally {
                working = false;
            }
        }, "Sarah-Pairing-Confirm").start();
    }

    private void offerContinuityPreview(String host) {
        if (working || host == null || host.trim().isEmpty()) {
            if (host == null || host.trim().isEmpty()) status.setText("Select an approved device first.");
            return;
        }
        working = true;
        status.setText("Requesting an encrypted continuity preview from the approved device...");
        new Thread(() -> {
            try {
                SecureSyncPreviewClient.Preview preview = SecureSyncPreviewClient.fetch(this, host);
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Import this Sarah continuity?")
                        .setMessage(preview.summary()
                                + "\n\nNo existing item is silently overwritten. A provenance receipt is appended if you import.")
                        .setPositiveButton("Import reviewed data", (dialog, which) -> applyPreview(preview))
                        .setNegativeButton("Not now", (dialog, which) ->
                                status.setText("Preview declined. Nothing was imported; device trust remains available."))
                        .setOnCancelListener(dialog ->
                                status.setText("Preview closed. Nothing was imported."))
                        .show());
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Continuity preview was not available: " + safe(error)
                        + ". Nothing was imported."));
            } finally {
                working = false;
            }
        }, "Sarah-Secure-Sync-Preview").start();
    }

    private void applyPreview(SecureSyncPreviewClient.Preview preview) {
        if (working) return;
        working = true;
        status.setText("Importing only the continuity package you reviewed...");
        new Thread(() -> {
            try {
                int imported = preview.apply(this);
                runOnUiThread(() -> status.setText("Imported " + imported
                        + " new continuity item(s). Existing records were kept; an append-only receipt was recorded."));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Continuity import stopped: " + safe(error)
                        + ". Review the owner identity and try a fresh preview."));
            } finally {
                working = false;
            }
        }, "Sarah-Secure-Sync-Import").start();
    }

    private void cancelPairing(SarahPairingTransport.Pending active) {
        if (pending == active) pending = null;
        active.close();
        status.setText("Pairing cancelled. No device credential was saved.");
    }

    private void revokeSelected() {
        String host = selectedSavedHost();
        if (host.isEmpty()) {
            status.setText("No approved device is selected.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Remove this device approval?")
                .setMessage(host + " will need a new two-screen code approval before it can be trusted again.")
                .setPositiveButton("Remove approval", (dialog, which) -> {
                    TrustedDeviceStore.revoke(this, host);
                    refreshSaved();
                    status.setText("The saved credential for " + host + " was removed from this phone.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setDiscoveredPeers(List<SarahDeviceDiscovery.Peer> peers) {
        discoveredPeers.clear();
        discoveredPeers.addAll(peers);
        List<String> labels = new ArrayList<>();
        for (SarahDeviceDiscovery.Peer peer : peers) labels.add(peer.toString());
        discoveredAdapter.clear();
        discoveredAdapter.addAll(labels);
        discoveredAdapter.notifyDataSetChanged();
    }

    private SarahDeviceDiscovery.Peer selectedPeer() {
        int index = discovered.getSelectedItemPosition();
        return index >= 0 && index < discoveredPeers.size()
                ? discoveredPeers.get(index) : null;
    }

    private void refreshSaved() {
        List<String> hosts = TrustedDeviceStore.hosts(this);
        savedAdapter.clear();
        savedAdapter.addAll(hosts);
        savedAdapter.notifyDataSetChanged();
        String selected = TrustedDeviceStore.host(this);
        int index = hosts.indexOf(selected);
        if (index >= 0) saved.setSelection(index);
    }

    private String selectedSavedHost() {
        Object value = saved.getSelectedItem();
        return value == null ? "" : value.toString();
    }

    private void addSection(LinearLayout root, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(17);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setTextColor(Color.rgb(17, 40, 58));
        label.setPadding(dp(2), dp(12), dp(2), dp(5));
        root.addView(label);
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setMinHeight(dp(48));
        return button;
    }

    private LinearLayout.LayoutParams fullWidth(int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safe(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
