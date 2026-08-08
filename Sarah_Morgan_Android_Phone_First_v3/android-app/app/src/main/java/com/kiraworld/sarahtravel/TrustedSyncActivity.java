package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class TrustedSyncActivity extends Activity {
    private final List<SarahDeviceDiscovery.Peer> discoveredPeers = new ArrayList<>();
    private TextView status;
    private Spinner discovered;
    private ArrayAdapter<String> discoveredAdapter;
    private Spinner saved;
    private ArrayAdapter<String> savedAdapter;
    private EditText manualHost;
    private EditText manualCode;
    private volatile boolean working;

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
        title.setText("Devices & continuity");
        title.setTextColor(Color.WHITE);
        title.setTextSize(23);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header, fullWidth(dp(6)));

        TextView note = new TextView(this);
        note.setText("Device continuity is preserved as future work, but network sync is disabled in this R2 candidate. The earlier LAN prototype did not yet provide an accepted secure transport. Existing saved data stays on this device.");
        note.setTextSize(15);
        note.setTextColor(Color.rgb(35, 52, 65));
        note.setPadding(dp(4), dp(14), dp(4), dp(12));
        root.addView(note);

        status = new TextView(this);
        status.setText("Device sync setup required. No pairing or transfer will run in this build.");
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
        discoveredAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        discovered.setAdapter(discoveredAdapter);
        root.addView(discovered, fullWidth(dp(4)));

        LinearLayout discoveryButtons = new LinearLayout(this);
        discoveryButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button scan = button("Scan again");
        scan.setEnabled(false);
        scan.setOnClickListener(v -> scanDevices());
        discoveryButtons.addView(scan, weighted());
        Button request = button("Verify & transfer");
        request.setEnabled(false);
        request.setOnClickListener(v -> requestSelected());
        discoveryButtons.addView(request, weighted());
        root.addView(discoveryButtons, fullWidth(dp(8)));

        TextView explanation = new TextView(this);
        explanation.setText("After approval, Sarah synchronizes approved memories, conversations, trips, factual corrections, preferences, and recent sanitized trip photos in both directions. The device that already has the newest details supplies them, regardless of which program was installed first.");
        explanation.setTextSize(14);
        explanation.setTextColor(Color.DKGRAY);
        explanation.setPadding(dp(4), dp(4), dp(4), dp(8));
        root.addView(explanation);

        addSection(root, "Manual fallback");
        manualHost = new EditText(this);
        manualHost.setEnabled(false);
        manualHost.setHint("Computer address, for example 192.168.1.25");
        root.addView(manualHost, fullWidth(dp(2)));
        manualCode = new EditText(this);
        manualCode.setEnabled(false);
        manualCode.setHint("Six-digit code shown by Sarah on Windows");
        manualCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(manualCode, fullWidth(dp(3)));
        Button manualPair = button("Pair manually and synchronize");
        manualPair.setEnabled(false);
        manualPair.setOnClickListener(v -> manualPair());
        root.addView(manualPair, fullWidth(dp(10)));

        addSection(root, "Already trusted devices");
        saved = new Spinner(this);
        savedAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        saved.setAdapter(savedAdapter);
        root.addView(saved, fullWidth(dp(3)));
        Button selected = button("Sync selected device now");
        selected.setEnabled(false);
        selected.setOnClickListener(v -> syncSelected());
        root.addView(selected, fullWidth(dp(3)));
        Button all = button("Sync every trusted Sarah device");
        all.setEnabled(false);
        all.setOnClickListener(v -> runWork(() -> TrustedSyncClient.syncAll(this).optString("message", "Sync completed.")));
        root.addView(all, fullWidth(dp(3)));
        Button revoke = button("Revoke selected device");
        revoke.setOnClickListener(v -> revokeSelected());
        root.addView(revoke, fullWidth(dp(3)));

        refreshSaved();
        setContentView(scroll);
        SafeAreaInsets.apply(this, scroll, null, scroll);

        String peerHost = getIntent().getStringExtra("peer_host");
        if (TrustedSyncClient.isTransportAccepted()
                && peerHost != null && !peerHost.trim().isEmpty()) {
            String peerName = getIntent().getStringExtra("peer_name");
            int peerPort = getIntent().getIntExtra("peer_port", 8769);
            setDiscoveredPeers(java.util.Collections.singletonList(
                    new SarahDeviceDiscovery.Peer(peerHost.trim(), peerName, "", peerPort)));
            root.postDelayed(this::requestSelected, 350L);
        } else if (TrustedSyncClient.isTransportAccepted()) {
            root.postDelayed(this::scanDevices, 250L);
        }
    }

    private void scanDevices() {
        if (working) return;
        working = true;
        status.setText("Sarah is looking on this private Wi-Fi…");
        new Thread(() -> {
            try {
                List<SarahDeviceDiscovery.Peer> peers = SarahDeviceDiscovery.discover(this, 3000);
                runOnUiThread(() -> {
                    setDiscoveredPeers(peers);
                    status.setText(peers.isEmpty()
                            ? "No other Sarah device answered. Make sure Sarah is open on Windows and both devices use the same private Wi-Fi, or use the manual address below."
                            : "Sarah found " + peers.size() + " device(s). Select yours, then verify and transfer.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Discovery could not finish: " + safe(error)));
            } finally {
                working = false;
            }
        }, "Sarah-Device-Scan").start();
    }

    private void requestSelected() {
        SarahDeviceDiscovery.Peer peer = selectedPeer();
        if (peer == null || working) {
            if (peer == null) status.setText("Scan for a Sarah device first, or use the manual fallback.");
            return;
        }
        String code = String.format(java.util.Locale.US, "%06d", new SecureRandom().nextInt(1_000_000));
        String deviceName = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        new AlertDialog.Builder(this)
                .setTitle("Approve this phone on " + peer.name)
                .setMessage("The existing Sarah device should show:\n\n" + deviceName.trim() + "\nVerification code: " + code + "\n\nApprove only when the device name and code match. Sarah will then perform the first encrypted two-way sync.")
                .setPositiveButton("Start verification", (dialog, which) -> beginRequest(peer, code))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void beginRequest(SarahDeviceDiscovery.Peer peer, String code) {
        if (working) return;
        working = true;
        status.setText("Waiting for approval on " + peer.name + " • code " + code);
        new Thread(() -> {
            try {
                org.json.JSONObject result = TrustedSyncClient.requestPairAndSync(this, peer.host, peer.port, code);
                runOnUiThread(() -> {
                    refreshSaved();
                    status.setText(result.optString("pairing_message", result.optString("message", "Sarah paired and synchronized.")));
                    new AlertDialog.Builder(this)
                            .setTitle("Sarah is connected")
                            .setMessage("The trusted device approved this phone. Sarah synchronized in both directions and will continue updating approved details while the devices can reach each other.")
                            .setPositiveButton("Done", null)
                            .show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Pairing was not completed: " + safe(error)));
            } finally {
                working = false;
            }
        }, "Sarah-Approval-Pairing").start();
    }

    private void manualPair() {
        String host = manualHost.getText().toString().trim();
        String code = manualCode.getText().toString().trim();
        if (host.isEmpty() || code.length() != 6) {
            status.setText("Enter the Windows address and its six-digit code.");
            return;
        }
        runWork(() -> {
            TrustedSyncClient.pair(this, host, code);
            org.json.JSONObject result = TrustedSyncClient.syncHost(this, host);
            runOnUiThread(this::refreshSaved);
            return result.optString("message", "Sarah paired and synchronized.");
        });
    }

    private void syncSelected() {
        String host = selectedSavedHost();
        if (host.isEmpty()) {
            status.setText("No trusted device is selected.");
            return;
        }
        TrustedDeviceStore.select(this, host);
        runWork(() -> TrustedSyncClient.syncHost(this, host).optString("message", "Sync completed."));
    }

    private void revokeSelected() {
        String host = selectedSavedHost();
        if (host.isEmpty()) {
            status.setText("No trusted device is selected.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Revoke this Sarah device?")
                .setMessage(host + " will no longer receive or send Sarah’s details until you approve it again.")
                .setPositiveButton("Revoke", (dialog, which) -> {
                    TrustedDeviceStore.revoke(this, host);
                    refreshSaved();
                    status.setText("The saved trust for " + host + " was revoked on this phone.");
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
        return index >= 0 && index < discoveredPeers.size() ? discoveredPeers.get(index) : null;
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

    private interface Work { String run() throws Exception; }

    private void runWork(Work work) {
        if (working) return;
        working = true;
        status.setText("Sarah is working…");
        new Thread(() -> {
            try {
                String result = work.run();
                runOnUiThread(() -> status.setText(result));
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("Could not complete: " + safe(error)));
            } finally {
                working = false;
            }
        }, "Sarah-Trusted-Sync").start();
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safe(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
