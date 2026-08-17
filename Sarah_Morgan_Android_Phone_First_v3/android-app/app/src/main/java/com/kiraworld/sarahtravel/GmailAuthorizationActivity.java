package com.kiraworld.sarahtravel;

import android.accounts.Account;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.RevokeAccessRequest;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/** Owner-facing, optional Gmail read-only authorization and receipt surface. */
public final class GmailAuthorizationActivity extends Activity {
    private static final int REQUEST_GOOGLE_AUTHORIZATION = 901;

    private GmailTokenVault vault;
    private String profileId = "";
    private TextView status;
    private TextView results;
    private CheckBox monitor;
    private Button connect;
    private Button checkNow;
    private Button disconnect;
    private boolean refreshing;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        vault = new GmailTokenVault(this);
        profileId = ensureOwnerProfile();
        buildUi();
        refreshUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(247, 249, 250));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Connect travel email", 26, true);
        title.setTextColor(Color.rgb(10, 47, 69));
        root.addView(title);
        TextView explanation = text(
                "Optional. Google—not Sarah—shows the account and consent screen. "
                        + "Google's gmail.readonly scope authorizes reading Gmail; Sarah's client further limits itself to bounded travel-message metadata and a short preview used to propose likely travel or events. Sarah never asks for your Gmail password, "
                        + "and has no send, delete, modify, mark-read, draft, or settings permission.",
                15,
                false);
        explanation.setPadding(0, dp(8), 0, dp(12));
        root.addView(explanation);

        status = text("Checking connection…", 15, true);
        status.setTextColor(Color.rgb(17, 91, 108));
        root.addView(status, fullWidth(dp(12)));

        connect = button("Connect or refresh with Google");
        connect.setOnClickListener(v -> beginAuthorization());
        root.addView(connect, fullWidth(dp(5)));

        checkNow = button("Check recent travel email now");
        checkNow.setOnClickListener(v -> checkNow());
        root.addView(checkNow, fullWidth(dp(8)));

        monitor = new CheckBox(this);
        monitor.setText("Check for likely travel messages about every 6 hours when connected and the battery is not low");
        monitor.setTextSize(15);
        monitor.setOnCheckedChangeListener((button, enabled) -> {
            if (refreshing) return;
            try {
                GmailMonitorScheduler.setEnabled(this, profileId, enabled);
                refreshUi();
            } catch (Exception error) {
                status.setText("Background checking was not changed: " + safe(error));
                refreshUi();
            }
        });
        root.addView(monitor, fullWidth(dp(8)));
        TextView monitorBoundary = text(
                "Connecting email does not turn monitoring on. A background check is bounded to ten metadata-first travel candidates, may be deferred by Android, and never marks a message read or changes it.",
                13,
                false);
        root.addView(monitorBoundary, fullWidth(dp(14)));

        Button reviewCalendar = button("Review proposals and Sarah's calendar");
        reviewCalendar.setOnClickListener(v -> startActivity(
                new Intent(this, TravelCalendarActivity.class)));
        root.addView(reviewCalendar, fullWidth(dp(14)));

        TextView receiptTitle = text("Recent source receipts", 18, true);
        receiptTitle.setTextColor(Color.rgb(10, 47, 69));
        root.addView(receiptTitle);
        results = text("No Gmail read receipts on this phone.", 14, false);
        root.addView(results, fullWidth(dp(14)));

        disconnect = button("Disconnect and revoke Gmail access");
        disconnect.setOnClickListener(v -> confirmDisconnect());
        root.addView(disconnect, fullWidth(dp(5)));

        Button close = button("Back");
        close.setOnClickListener(v -> finish());
        root.addView(close);

        setContentView(scroll);
        SafeAreaInsets.apply(this, scroll, null, scroll);
    }

    private void beginAuthorization() {
        if (profileId.isEmpty()
                || !ConfirmedOwnerLease.isExactActiveOwner(this, profileId)) {
            status.setText("Confirm the exact active phone owner before connecting Gmail.");
            return;
        }
        connect.setEnabled(false);
        status.setText("Opening Google's account and read-only consent flow…");
        vault.beginAuthorizationAttempt(System.currentTimeMillis());
        AuthorizationRequest request = readOnlyRequest();
        Identity.getAuthorizationClient(this).authorize(request)
                .addOnSuccessListener(result -> {
                    if (result.hasResolution()) {
                        try {
                            startIntentSenderForResult(
                                    result.getPendingIntent().getIntentSender(),
                                    REQUEST_GOOGLE_AUTHORIZATION,
                                    null,
                                    0,
                                    0,
                                    0);
                        } catch (IntentSender.SendIntentException error) {
                            vault.consumeAuthorizationAttempt(System.currentTimeMillis());
                            status.setText("Google authorization did not open. No Gmail access was saved.");
                            connect.setEnabled(true);
                        }
                    } else if (vault.consumeAuthorizationAttempt(System.currentTimeMillis())) {
                        acceptAuthorization(result);
                    } else {
                        status.setText("The authorization attempt expired. Try Connect again.");
                        connect.setEnabled(true);
                    }
                })
                .addOnFailureListener(error -> {
                    vault.consumeAuthorizationAttempt(System.currentTimeMillis());
                    status.setText("Google authorization is not available for this signed build: "
                            + safe(error)
                            + ". No Gmail access was saved. Check the Android OAuth package/signing-certificate setup.");
                    connect.setEnabled(true);
                });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_GOOGLE_AUTHORIZATION) return;
        if (!vault.consumeAuthorizationAttempt(System.currentTimeMillis())) {
            status.setText("The Google authorization response was stale and was ignored.");
            connect.setEnabled(true);
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            status.setText("Gmail connection was cancelled. Nothing was saved.");
            connect.setEnabled(true);
            return;
        }
        try {
            AuthorizationResult result = Identity.getAuthorizationClient(this)
                    .getAuthorizationResultFromIntent(data);
            acceptAuthorization(result);
        } catch (ApiException error) {
            status.setText("Google did not grant the read-only connection: " + safe(error));
            connect.setEnabled(true);
        }
    }

    private void acceptAuthorization(AuthorizationResult authorization) {
        if (authorization == null
                || !GmailReadOnlyPolicy.exactReadOnlyGrant(
                        authorization.getGrantedScopes())
                || authorization.getAccessToken() == null
                || authorization.getAccessToken().trim().isEmpty()) {
            status.setText("The exact gmail.readonly grant was not returned. Nothing was saved.");
            connect.setEnabled(true);
            return;
        }
        String token = authorization.getAccessToken();
        status.setText("Verifying the selected Gmail account with a read-only profile request…");
        new Thread(() -> {
            try {
                if (!ConfirmedOwnerLease.isExactActiveOwner(this, profileId)) {
                    throw new SecurityException("OWNER_CONFIRMATION_CHANGED");
                }
                String email = GmailReadOnlyClient.fetchAccountEmail(token);
                long now = System.currentTimeMillis();
                vault.saveAuthorizedAccess(token, email, profileId, now);
                List<GmailReadOnlyClient.SourceReceipt> receipts =
                        GmailReadOnlyClient.findTravelCandidates(token, email, now);
                if (receipts.isEmpty()) {
                    vault.recordSyncStatus("READ_ONLY_NO_TRAVEL_CANDIDATES", now);
                } else {
                    for (GmailReadOnlyClient.SourceReceipt receipt : receipts) {
                        vault.recordRead(profileId, receipt);
                    }
                }
                runOnUiThread(() -> {
                    refreshUi();
                    status.setText("Connected read-only to " + email
                            + ". Checked bounded metadata/previews for up to ten recent travel candidates. Each is only a proposal until you choose Remember; no message was changed.");
                });
            } catch (Exception error) {
                vault.clearCachedToken(true);
                runOnUiThread(() -> {
                    try { GmailMonitorScheduler.setEnabled(this, profileId, false); }
                    catch (Exception ignored) { }
                    refreshUi();
                    status.setText("Google consent returned, but the supervised Gmail read did not pass: "
                            + safe(error) + ". Reconnect is required; monitoring remains off.");
                });
            }
        }, "Sarah-Gmail-Initial-Readonly-Check").start();
    }

    private void checkNow() {
        String token = vault.usableAccessToken(profileId, System.currentTimeMillis());
        if (token.isEmpty()) {
            status.setText("A fresh Google read-only token is needed. Choose Connect or refresh with Google.");
            beginAuthorization();
            return;
        }
        checkNow.setEnabled(false);
        status.setText("Checking recent travel-message metadata…");
        new Thread(() -> {
            try {
                long now = System.currentTimeMillis();
                String email = GmailReadOnlyClient.fetchAccountEmail(token);
                if (!email.equalsIgnoreCase(vault.accountEmail(profileId))) {
                    throw new SecurityException("GMAIL_ACCOUNT_CHANGED");
                }
                List<GmailReadOnlyClient.SourceReceipt> receipts =
                        GmailReadOnlyClient.findTravelCandidates(token, email, now);
                if (receipts.isEmpty()) {
                    vault.recordSyncStatus("READ_ONLY_NO_TRAVEL_CANDIDATES", now);
                } else {
                    for (GmailReadOnlyClient.SourceReceipt receipt : receipts) {
                        vault.recordRead(profileId, receipt);
                    }
                }
                runOnUiThread(() -> {
                    refreshUi();
                    status.setText("Read-only candidate check completed. Found "
                            + receipts.size() + " proposal(s); none was automatically saved and no Gmail message was changed.");
                });
            } catch (Exception error) {
                vault.clearCachedToken(true);
                runOnUiThread(() -> {
                    status.setText("The read-only check stopped: " + safe(error)
                            + ". Reconnect is required; no message was changed.");
                    checkNow.setEnabled(true);
                });
            }
        }, "Sarah-Gmail-Manual-Readonly-Check").start();
    }

    private void confirmDisconnect() {
        String email = vault.accountEmail(profileId);
        new AlertDialog.Builder(this)
                .setTitle("Disconnect Gmail?")
                .setMessage("Background checking stops immediately. Sarah's local Gmail token and unsaved email proposals are removed. Calendar items you separately chose to remember stay in Sarah's local owner calendar. Google documents that revokeAccess may revoke every Google permission previously granted to this app; this build requests only gmail.readonly.")
                .setPositiveButton("Disconnect and revoke", (dialog, which) -> disconnect(email))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void disconnect(String email) {
        try { GmailMonitorScheduler.setEnabled(this, profileId, false); }
        catch (Exception ignored) { }
        try { vault.disconnectGmailPreservingCalendar(profileId); }
        catch (Exception error) {
            status.setText("Monitoring stopped, but local Gmail state needs review: " + safe(error));
            return;
        }
        refreshUi();
        if (email == null || email.trim().isEmpty()) {
            status.setText("Local Gmail access and monitoring were removed. No Google account was bound for remote revocation.");
            return;
        }
        RevokeAccessRequest request = RevokeAccessRequest.builder()
                .setAccount(new Account(email, "com.google"))
                .setScopes(Collections.singletonList(
                        new Scope(GmailReadOnlyPolicy.SCOPE)))
                .build();
        status.setText("Local Gmail access removed. Asking Google to revoke the grant…");
        Identity.getAuthorizationClient(this).revokeAccess(request)
                .addOnSuccessListener(unused -> status.setText(
                        "Disconnected. Google revoked Sarah's grant; monitoring is off, unsaved email proposals were removed, and owner-saved calendar items were retained."))
                .addOnFailureListener(error -> status.setText(
                        "Sarah's local Gmail access and monitoring are removed. Google revocation could not be confirmed; remove Sarah under your Google Account's third-party connections before reconnecting. Details: "
                                + safe(error)));
    }

    private void refreshUi() {
        boolean connected = !profileId.isEmpty() && vault.hasAuthorizedGrant(profileId);
        boolean ready = connected && !vault.reauthorizationRequired();
        refreshing = true;
        monitor.setEnabled(ready);
        monitor.setChecked(ready && vault.monitoringEnabled(profileId));
        refreshing = false;
        checkNow.setEnabled(ready);
        disconnect.setEnabled(connected);
        connect.setEnabled(!profileId.isEmpty());
        if (!connected) {
            status.setText(profileId.isEmpty()
                    ? "Finish the local owner profile before connecting Gmail."
                    : "Gmail is not connected. Monitoring is off. Google Cloud must have an Android OAuth client bound to package com.kiraworld.sarahtravel and this APK's exact SHA-1 signing certificate.");
        } else {
            long last = vault.lastSyncAt();
            status.setText("Gmail read-only: " + vault.accountEmail(profileId)
                    + "\nMonitoring: " + (vault.monitoringEnabled(profileId) ? "on" : "off")
                    + "\nLast check: " + (last == 0L ? "never" : Instant.ofEpochMilli(last))
                    + " · " + vault.lastSyncStatus()
                    + (vault.reauthorizationRequired() ? " · reconnect required" : ""));
        }
        JSONArray receipts = vault.receipts(profileId);
        if (receipts.length() == 0) {
            results.setText("No Gmail read receipts on this phone.");
            return;
        }
        StringBuilder shown = new StringBuilder();
        int first = Math.max(0, receipts.length() - 5);
        for (int index = receipts.length() - 1; index >= first; index--) {
            JSONObject receipt = receipts.optJSONObject(index);
            if (receipt == null) continue;
            if (shown.length() > 0) shown.append("\n\n");
            shown.append(receipt.optString("subject", "(no subject)"))
                    .append("\nFrom: ").append(receipt.optString("sender", ""))
                    .append("\nMessage date: ").append(receipt.optString("message_date", ""))
                    .append("\nSource: Gmail message ").append(receipt.optString("message_id", ""))
                    .append("\nChecked: ").append(Instant.ofEpochMilli(
                            receipt.optLong("fetched_at_epoch_ms", 0L)))
                    .append("\nCandidate: ").append(receipt.optString(
                            "email_candidate_state", EmailCalendarPolicy.EMAIL_PENDING))
                    .append("\nCalendar: ").append(receipt.optString(
                            "calendar_item_state", EmailCalendarPolicy.CALENDAR_NOT_SAVED))
                    .append("\nReminder: ").append(receipt.optString(
                            "reminder_state", EmailCalendarPolicy.REMINDER_NOT_SCHEDULED))
                    .append("\nBounded metadata/preview · message unchanged");
        }
        results.setText(shown.toString());
    }

    private String ensureOwnerProfile() {
        ConfirmedOwnerLease lease = ConfirmedOwnerLease.capture(this);
        if (lease != null) return lease.personId();
        SarahDatabase database = new SarahDatabase(this);
        PersonProfileStore profiles = new PersonProfileStore(this);
        try {
            if (!database.hasProfile()) return "";
            profiles.ensureOwner(database.getProfile());
            lease = ConfirmedOwnerLease.capture(this);
            return lease == null ? "" : lease.personId();
        } finally {
            profiles.close();
            database.close();
        }
    }

    private static AuthorizationRequest readOnlyRequest() {
        return AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(
                        new Scope(GmailReadOnlyPolicy.SCOPE)))
                .setOptOutIncludingGrantedScopes(true)
                .build();
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(35, 52, 65));
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safe(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error == null ? "unknown" : error.getClass().getSimpleName();
        }
        String cleaned = message.replaceAll("[\\r\\n]+", " ").trim();
        return cleaned.length() <= 160 ? cleaned : cleaned.substring(0, 160);
    }
}
