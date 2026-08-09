package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android-Keystore envelope for Sarah's short-lived Gmail access state.
 *
 * <p>No Gmail password, Google client secret, authorization code or refresh
 * token is accepted or stored. Google Play services owns the durable grant;
 * Sarah keeps a conservatively expiring access token only to finish a bounded
 * local read and rotates it by authorizing again.</p>
 */
public final class GmailTokenVault {
    private static final String PREFS = "sarah_gmail_readonly_v1";
    private static final String ALIAS = "SarahGmailReadonlyAccessV1";
    private static final String ENVELOPE_IV = "envelope_iv";
    private static final String ENVELOPE_DATA = "envelope_data";
    private static final int MAX_RECEIPTS = 30;
    /** Serializes whole encrypted-state updates across UI, worker, and scheduler instances. */
    private static final Object STATE_LOCK = new Object();

    private final Context app;

    public GmailTokenVault(Context context) {
        if (context == null) throw new IllegalArgumentException("context required");
        Context application = context.getApplicationContext();
        app = application == null ? context : application;
    }

    public synchronized String beginAuthorizationAttempt(long nowMillis) {
        synchronized (STATE_LOCK) {
        JSONObject state = read();
        String nonce = randomUrlToken(32);
        try {
            state.put("authorization_attempt", nonce);
            state.put("authorization_attempt_at", nowMillis);
            write(state);
            return nonce;
        } catch (Exception error) {
            throw new IllegalStateException("GMAIL_AUTH_ATTEMPT_NOT_SAVED", error);
        }
        }
    }

    public synchronized boolean consumeAuthorizationAttempt(long nowMillis) {
        synchronized (STATE_LOCK) {
        JSONObject state = read();
        String nonce = state.optString("authorization_attempt", "");
        long created = state.optLong("authorization_attempt_at", 0L);
        state.remove("authorization_attempt");
        state.remove("authorization_attempt_at");
        write(state);
        return !nonce.isEmpty()
                && created > 0L
                && nowMillis >= created
                && nowMillis - created <= GmailReadOnlyPolicy.AUTHORIZATION_ATTEMPT_MILLIS;
        }
    }

    public synchronized void saveAuthorizedAccess(
            String accessToken,
            String accountEmail,
            String profileId,
            long nowMillis) {
        if (!GmailReadOnlyPolicy.usableCachedToken(
                accessToken,
                GmailReadOnlyPolicy.SCOPE,
                nowMillis + GmailReadOnlyPolicy.ACCESS_TOKEN_CACHE_MILLIS,
                nowMillis)) {
            throw new IllegalArgumentException("A usable Gmail read-only token is required");
        }
        String email = cleanEmail(accountEmail);
        String profile = EventTripProfilePolicy.profileKey(profileId);
        if (email.isEmpty() || profile.isEmpty()) {
            throw new IllegalArgumentException("Gmail authorization has no exact account/profile binding");
        }
        synchronized (STATE_LOCK) {
        JSONObject state = read();
        try {
            state.put("access_token", accessToken.trim());
            state.put("access_expires_at", nowMillis
                    + GmailReadOnlyPolicy.ACCESS_TOKEN_CACHE_MILLIS);
            state.put("scope", GmailReadOnlyPolicy.SCOPE);
            state.put("account_email", email);
            state.put("profile_key", profile);
            state.put("authorized_at", nowMillis);
            state.put("reauthorization_required", false);
            write(state);
        } catch (Exception error) {
            throw new IllegalStateException("GMAIL_AUTHORIZATION_NOT_SAVED", error);
        }
        }
    }

    public synchronized String usableAccessToken(String profileId, long nowMillis) {
        JSONObject state = read();
        if (!sameProfile(state, profileId)) return "";
        String token = state.optString("access_token", "");
        return GmailReadOnlyPolicy.usableCachedToken(
                token,
                state.optString("scope", ""),
                state.optLong("access_expires_at", 0L),
                nowMillis) ? token : "";
    }

    public synchronized boolean hasAuthorizedGrant(String profileId) {
        JSONObject state = read();
        return sameProfile(state, profileId)
                && GmailReadOnlyPolicy.SCOPE.equals(state.optString("scope", ""))
                && !cleanEmail(state.optString("account_email", "")).isEmpty();
    }

    public synchronized String accountEmail(String profileId) {
        JSONObject state = read();
        return sameProfile(state, profileId)
                ? cleanEmail(state.optString("account_email", "")) : "";
    }

    public synchronized void clearCachedToken(boolean reauthorizationRequired) {
        synchronized (STATE_LOCK) {
        JSONObject state = read();
        state.remove("access_token");
        state.remove("access_expires_at");
        try {
            state.put("reauthorization_required", reauthorizationRequired);
            write(state);
        } catch (Exception error) {
            throw new IllegalStateException("GMAIL_TOKEN_CLEAR_FAILED", error);
        }
        }
    }

    public synchronized boolean reauthorizationRequired() {
        return read().optBoolean("reauthorization_required", false);
    }

    public synchronized boolean monitoringEnabled(String profileId) {
        JSONObject state = read();
        return sameProfile(state, profileId)
                && state.optBoolean("monitoring_enabled", false);
    }

    public synchronized void setMonitoringEnabled(String profileId, boolean enabled) {
        synchronized (STATE_LOCK) {
        JSONObject state = read();
        if (enabled && (!sameProfile(state, profileId)
                || !GmailReadOnlyPolicy.SCOPE.equals(state.optString("scope", "")))) {
            throw new IllegalStateException("GMAIL_MONITORING_REQUIRES_READONLY_GRANT");
        }
        try {
            state.put("monitoring_enabled", enabled);
            write(state);
        } catch (Exception error) {
            throw new IllegalStateException("GMAIL_MONITORING_STATE_NOT_SAVED", error);
        }
        }
    }

    public synchronized boolean recordRead(
            String profileId,
            GmailReadOnlyClient.SourceReceipt receipt) {
        synchronized (STATE_LOCK) {
        JSONObject state = read();
        if (!sameProfile(state, profileId)) {
            throw new IllegalStateException("GMAIL_RECEIPT_PROFILE_MISMATCH");
        }
        try {
            JSONArray receipts = state.optJSONArray("receipts");
            if (receipts == null) receipts = new JSONArray();
            JSONObject latest = receipt.toJson();
            JSONArray merged = new JSONArray();
            boolean previouslySeen = false;
            for (int i = 0; i < receipts.length(); i++) {
                JSONObject existing = receipts.optJSONObject(i);
                if (existing == null) continue;
                if (receipt.messageId.equals(existing.optString("message_id", ""))) {
                    previouslySeen = true;
                    preserveOwnerTruth(existing, latest);
                } else {
                    merged.put(existing);
                }
            }
            JSONArray bounded = new JSONArray();
            int first = Math.max(0, merged.length() - (MAX_RECEIPTS - 1));
            for (int i = first; i < merged.length(); i++) bounded.put(merged.get(i));
            bounded.put(latest);
            state.put("receipts", bounded);
            state.put("last_sync_at", receipt.fetchedAtEpochMillis);
            state.put("last_sync_status", "READ_ONLY_CANDIDATE_PROPOSAL_OK");
            write(state);
            return !previouslySeen;
        } catch (Exception error) {
            throw new IllegalStateException("GMAIL_RECEIPT_NOT_SAVED", error);
        }
        }
    }

    public synchronized void recordSyncStatus(String status, long nowMillis) {
        synchronized (STATE_LOCK) {
        JSONObject state = read();
        try {
            state.put("last_sync_at", nowMillis);
            state.put("last_sync_status", cleanStatus(status));
            write(state);
        } catch (Exception error) {
            throw new IllegalStateException("GMAIL_SYNC_STATUS_NOT_SAVED", error);
        }
        }
    }

    public synchronized long lastSyncAt() {
        return read().optLong("last_sync_at", 0L);
    }

    public synchronized String lastSyncStatus() {
        return cleanStatus(read().optString("last_sync_status", "never"));
    }

    public synchronized JSONArray receipts(String profileId) {
        JSONObject state = read();
        if (!sameProfile(state, profileId)) return new JSONArray();
        JSONArray source = state.optJSONArray("receipts");
        if (source == null) return new JSONArray();
        try { return new JSONArray(source.toString()); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    /**
     * Bind at most one exact pending Gmail item to Sarah's foreground chat.
     *
     * <p>This changes only encrypted proposal/audit state. It does not approve
     * a calendar item, schedule a reminder, speak, notify, or touch Gmail.</p>
     */
    public synchronized JSONObject claimPendingConversationPrompt(
            String profileId,
            long nowMillis) {
        synchronized (STATE_LOCK) {
        if (!ConfirmedOwnerLease.isExactActiveOwner(app, profileId)) return null;
        JSONObject state = read();
        if (!sameProfile(state, profileId)) return null;
        JSONArray receipts = state.optJSONArray("receipts");
        if (receipts == null) return null;
        JSONObject unclaimed = null;
        for (int index = 0; index < receipts.length(); index++) {
            JSONObject item = receipts.optJSONObject(index);
            if (item == null || !EmailCalendarPolicy.EMAIL_PENDING.equals(
                    item.optString("email_candidate_state", ""))) continue;
            if ("awaiting_owner_reply".equals(
                    item.optString("conversation_prompt_state", ""))) {
                return conversationPromptCopy(item, false);
            }
            if (!item.has("conversation_prompt_state") && unclaimed == null) {
                unclaimed = item;
            }
        }
        if (unclaimed == null) return null;
        try {
            unclaimed.put("conversation_prompt_state", "awaiting_owner_reply");
            unclaimed.put("conversation_prompt_shown_at_epoch_ms", nowMillis);
            write(state);
            return conversationPromptCopy(unclaimed, true);
        } catch (Exception error) {
            throw new IllegalStateException("GMAIL_CONVERSATION_PROMPT_NOT_BOUND", error);
        }
        }
    }

    private static JSONObject conversationPromptCopy(JSONObject source, boolean newlyClaimed) {
        try {
            JSONObject copy = new JSONObject(source.toString());
            copy.put("conversation_prompt_newly_claimed", newlyClaimed);
            return copy;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Release a foreground prompt without accepting or dismissing its item.
     * The proposal stays available for explicit review in Sarah's calendar.
     */
    public synchronized boolean deferConversationPrompt(
            String profileId,
            String messageId,
            long nowMillis) {
        synchronized (STATE_LOCK) {
            if (!ConfirmedOwnerLease.isExactActiveOwner(app, profileId)) return false;
            JSONObject state = read();
            if (!sameProfile(state, profileId)) return false;
            JSONObject candidate = findReceipt(state.optJSONArray("receipts"), messageId);
            if (candidate == null
                    || !EmailCalendarPolicy.EMAIL_PENDING.equals(
                            candidate.optString("email_candidate_state", ""))
                    || !"awaiting_owner_reply".equals(
                            candidate.optString("conversation_prompt_state", ""))) return false;
            try {
                candidate.put("conversation_prompt_state", "deferred_to_calendar_review");
                candidate.put("conversation_prompt_deferred_at_epoch_ms", nowMillis);
                write(state);
                return true;
            } catch (Exception error) {
                throw new IllegalStateException("GMAIL_CONVERSATION_PROMPT_NOT_DEFERRED", error);
            }
        }
    }

    /** Save or dismiss only after a distinct owner action; reading is never approval. */
    public synchronized boolean decideCalendarCandidate(
            String profileId,
            String messageId,
            boolean remember,
            long nowMillis) {
        synchronized (STATE_LOCK) {
        if (!ConfirmedOwnerLease.isExactActiveOwner(app, profileId)) return false;
        JSONObject state = read();
        if (!sameProfile(state, profileId)) return false;
        JSONArray receipts = state.optJSONArray("receipts");
        JSONObject candidate = findReceipt(receipts, messageId);
        if (candidate == null || !EmailCalendarPolicy.EMAIL_PENDING.equals(
                candidate.optString("email_candidate_state", ""))) return false;
        try {
            candidate.put("email_candidate_state", remember
                    ? EmailCalendarPolicy.EMAIL_ACCEPTED
                    : EmailCalendarPolicy.EMAIL_DISMISSED);
            candidate.put("calendar_item_state", remember
                    ? EmailCalendarPolicy.CALENDAR_SAVED
                    : EmailCalendarPolicy.CALENDAR_NOT_SAVED);
            candidate.put("owner_decided_at_epoch_ms", nowMillis);
            candidate.put("owner_decision", remember ? "remember" : "dismiss");
            candidate.put("conversation_prompt_state",
                    remember ? "answered_remember" : "answered_reject");
            candidate.put("conversation_prompt_answered_at_epoch_ms", nowMillis);
            if (!remember) {
                candidate.put("reminder_state", EmailCalendarPolicy.REMINDER_NOT_SCHEDULED);
                candidate.remove("reminder_trigger_instant");
                candidate.remove("reminder_lead_millis");
            }
            write(state);
            return true;
        } catch (Exception error) {
            throw new IllegalStateException("GMAIL_CALENDAR_DECISION_NOT_SAVED", error);
        }
        }
    }

    /** Owner-entered times are explicit corrections, never re-labelled as email extraction. */
    public synchronized boolean setOwnerCalendarTimes(
            String profileId,
            String messageId,
            String startInstant,
            String endInstant,
            long nowMillis) {
        synchronized (STATE_LOCK) {
        if (!ConfirmedOwnerLease.isExactActiveOwner(app, profileId)) return false;
        JSONObject state = read();
        if (!sameProfile(state, profileId)) return false;
        JSONObject candidate = findReceipt(state.optJSONArray("receipts"), messageId);
        if (candidate == null || !EmailCalendarPolicy.CALENDAR_SAVED.equals(
                candidate.optString("calendar_item_state", ""))) return false;
        String start = EmailCalendarPolicy.normalizedInstant(startInstant);
        String end = EmailCalendarPolicy.normalizedInstant(endInstant);
        if (start.isEmpty()) return false;
        try {
            if (!end.isEmpty() && java.time.Instant.parse(end).isBefore(
                    java.time.Instant.parse(start))) return false;
            candidate.put("calendar_start_instant", start);
            candidate.put("calendar_end_instant", end);
            candidate.put("calendar_time_source", EmailCalendarPolicy.TIME_SOURCE_OWNER);
            candidate.put("source_supported_exact_times", false);
            candidate.put("owner_time_updated_at_epoch_ms", nowMillis);
            candidate.put("reminder_state", EmailCalendarPolicy.REMINDER_NOT_SCHEDULED);
            candidate.remove("reminder_trigger_instant");
            candidate.remove("reminder_lead_millis");
            write(state);
            return true;
        } catch (Exception error) {
            throw new IllegalStateException("GMAIL_CALENDAR_TIME_NOT_SAVED", error);
        }
        }
    }

    public synchronized boolean setReminder(
            String profileId,
            String messageId,
            String triggerInstant,
            long leadMillis,
            boolean explicitOwnerRequest,
            long nowMillis) {
        synchronized (STATE_LOCK) {
        if (!ConfirmedOwnerLease.isExactActiveOwner(app, profileId)) return false;
        JSONObject state = read();
        if (!sameProfile(state, profileId)) return false;
        JSONObject candidate = findReceipt(state.optJSONArray("receipts"), messageId);
        if (candidate == null || !EmailCalendarPolicy.mayScheduleReminder(
                candidate.optString("calendar_item_state", ""),
                explicitOwnerRequest,
                triggerInstant,
                nowMillis)) return false;
        try {
            candidate.put("reminder_state", EmailCalendarPolicy.REMINDER_SCHEDULED);
            candidate.put("reminder_trigger_instant",
                    EmailCalendarPolicy.normalizedInstant(triggerInstant));
            candidate.put("reminder_lead_millis", Math.max(0L, leadMillis));
            candidate.put("reminder_owner_requested_at_epoch_ms", nowMillis);
            write(state);
            return true;
        } catch (Exception error) {
            throw new IllegalStateException("GMAIL_CALENDAR_REMINDER_NOT_SAVED", error);
        }
        }
    }

    public synchronized boolean cancelReminder(String profileId, String messageId) {
        synchronized (STATE_LOCK) {
        if (!ConfirmedOwnerLease.isExactActiveOwner(app, profileId)) return false;
        JSONObject state = read();
        if (!sameProfile(state, profileId)) return false;
        JSONObject candidate = findReceipt(state.optJSONArray("receipts"), messageId);
        if (candidate == null) return false;
        try {
            candidate.put("reminder_state", EmailCalendarPolicy.REMINDER_NOT_SCHEDULED);
            candidate.remove("reminder_trigger_instant");
            candidate.remove("reminder_lead_millis");
            write(state);
            return true;
        } catch (Exception error) {
            throw new IllegalStateException("GMAIL_CALENDAR_REMINDER_NOT_CANCELLED", error);
        }
        }
    }

    public synchronized boolean removeCalendarItem(
            String profileId,
            String messageId,
            long nowMillis) {
        synchronized (STATE_LOCK) {
        if (!ConfirmedOwnerLease.isExactActiveOwner(app, profileId)) return false;
        JSONObject state = read();
        if (!sameProfile(state, profileId)) return false;
        JSONObject candidate = findReceipt(state.optJSONArray("receipts"), messageId);
        if (candidate == null || !EmailCalendarPolicy.CALENDAR_SAVED.equals(
                candidate.optString("calendar_item_state", ""))) return false;
        try {
            candidate.put("calendar_item_state", EmailCalendarPolicy.CALENDAR_REMOVED);
            candidate.put("calendar_removed_at_epoch_ms", nowMillis);
            candidate.put("reminder_state", EmailCalendarPolicy.REMINDER_NOT_SCHEDULED);
            candidate.remove("reminder_trigger_instant");
            candidate.remove("reminder_lead_millis");
            write(state);
            return true;
        } catch (Exception error) {
            throw new IllegalStateException("GMAIL_CALENDAR_ITEM_NOT_REMOVED", error);
        }
        }
    }

    synchronized JSONObject receipt(String profileId, String messageId) {
        JSONObject state = read();
        if (!sameProfile(state, profileId)) return null;
        JSONObject found = findReceipt(state.optJSONArray("receipts"), messageId);
        if (found == null) return null;
        try { return new JSONObject(found.toString()); }
        catch (Exception ignored) { return null; }
    }

    synchronized boolean markReminderDelivered(
            String profileId,
            String messageId,
            String exactTriggerInstant,
            long deliveredAtMillis) {
        synchronized (STATE_LOCK) {
        JSONObject state = read();
        if (!sameProfile(state, profileId)) return false;
        JSONObject candidate = findReceipt(state.optJSONArray("receipts"), messageId);
        if (candidate == null
                || !EmailCalendarPolicy.REMINDER_SCHEDULED.equals(
                        candidate.optString("reminder_state", ""))
                || !EmailCalendarPolicy.normalizedInstant(exactTriggerInstant).equals(
                        candidate.optString("reminder_trigger_instant", ""))) return false;
        try {
            candidate.put("reminder_state", EmailCalendarPolicy.REMINDER_DELIVERED);
            candidate.put("reminder_delivered_at_epoch_ms", deliveredAtMillis);
            write(state);
            return true;
        } catch (Exception error) {
            return false;
        }
        }
    }

    synchronized boolean markReminderBlocked(
            String profileId,
            String messageId,
            String exactTriggerInstant,
            long checkedAtMillis) {
        synchronized (STATE_LOCK) {
        JSONObject state = read();
        if (!sameProfile(state, profileId)) return false;
        JSONObject candidate = findReceipt(state.optJSONArray("receipts"), messageId);
        if (candidate == null
                || !EmailCalendarPolicy.REMINDER_SCHEDULED.equals(
                        candidate.optString("reminder_state", ""))
                || !EmailCalendarPolicy.normalizedInstant(exactTriggerInstant).equals(
                        candidate.optString("reminder_trigger_instant", ""))) return false;
        try {
            candidate.put("reminder_state", EmailCalendarPolicy.REMINDER_BLOCKED);
            candidate.put("reminder_blocked_at_epoch_ms", checkedAtMillis);
            write(state);
            return true;
        } catch (Exception error) {
            return false;
        }
        }
    }

    /**
     * Remove Gmail authority and unsaved observations while retaining only
     * calendar items the owner separately chose to remember.
     */
    public synchronized void disconnectGmailPreservingCalendar(String profileId) {
        synchronized (STATE_LOCK) {
        JSONObject oldState = read();
        JSONObject kept = new JSONObject();
        JSONArray saved = new JSONArray();
        if (sameProfile(oldState, profileId)) {
            JSONArray receipts = oldState.optJSONArray("receipts");
            if (receipts != null) {
                for (int index = 0; index < receipts.length(); index++) {
                    JSONObject item = receipts.optJSONObject(index);
                    if (item == null || !EmailCalendarPolicy.CALENDAR_SAVED.equals(
                            item.optString("calendar_item_state", ""))) continue;
                    // The exact source binding/title/time is part of the saved
                    // item; transient preview/sender/account details are not.
                    JSONObject redacted;
                    try { redacted = new JSONObject(item.toString()); }
                    catch (Exception ignored) { continue; }
                    redacted.remove("bounded_snippet");
                    redacted.remove("sender");
                    redacted.remove("account_email");
                    saved.put(redacted);
                }
            }
        }
        try {
            kept.put("profile_key", EventTripProfilePolicy.profileKey(profileId));
            kept.put("receipts", saved);
            kept.put("monitoring_enabled", false);
            kept.put("last_sync_status", "GMAIL_DISCONNECTED_SAVED_CALENDAR_RETAINED");
            kept.put("last_sync_at", System.currentTimeMillis());
            kept.put("reauthorization_required", false);
            write(kept);
        } catch (Exception error) {
            throw new IllegalStateException("GMAIL_DISCONNECT_NOT_SAVED", error);
        }
        }
    }

    /** Explicit full local erasure, including owner-approved calendar items. */
    public synchronized void clearAll() {
        synchronized (STATE_LOCK) {
        SharedPreferences preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!preferences.edit().clear().commit()) {
            throw new IllegalStateException("GMAIL_LOCAL_STATE_NOT_CLEARED");
        }
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
        } catch (Exception error) {
            throw new IllegalStateException("GMAIL_KEY_NOT_CLEARED", error);
        }
        }
    }

    private boolean sameProfile(JSONObject state, String personId) {
        String expected = EventTripProfilePolicy.profileKey(personId);
        return !expected.isEmpty() && expected.equals(state.optString("profile_key", ""));
    }

    private static JSONObject findReceipt(JSONArray receipts, String messageId) {
        String exact = messageId == null ? "" : messageId.trim();
        if (receipts == null || exact.isEmpty()) return null;
        for (int index = 0; index < receipts.length(); index++) {
            JSONObject receipt = receipts.optJSONObject(index);
            if (receipt != null && exact.equals(receipt.optString("message_id", ""))) {
                return receipt;
            }
        }
        return null;
    }

    private static void preserveOwnerTruth(JSONObject oldValue, JSONObject newValue)
            throws Exception {
        String[] keys = new String[]{
                "email_candidate_state", "calendar_item_state", "reminder_state",
                "owner_decided_at_epoch_ms", "owner_decision",
                "calendar_start_instant", "calendar_end_instant", "calendar_time_source",
                "source_supported_exact_times", "owner_time_updated_at_epoch_ms",
                "reminder_trigger_instant", "reminder_lead_millis",
                "reminder_owner_requested_at_epoch_ms", "reminder_delivered_at_epoch_ms",
                "reminder_blocked_at_epoch_ms", "calendar_removed_at_epoch_ms",
                "conversation_prompt_state", "conversation_prompt_shown_at_epoch_ms",
                "conversation_prompt_answered_at_epoch_ms",
                "conversation_prompt_deferred_at_epoch_ms"
        };
        for (String key : keys) {
            if (oldValue.has(key)) newValue.put(key, oldValue.get(key));
        }
    }

    private JSONObject read() {
        synchronized (STATE_LOCK) {
        try {
            SharedPreferences preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String iv = preferences.getString(ENVELOPE_IV, "");
            String data = preferences.getString(ENVELOPE_DATA, "");
            if (iv.isEmpty() || data.isEmpty()) return new JSONObject();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            byte[] plain = cipher.doFinal(Base64.decode(data, Base64.NO_WRAP));
            return new JSONObject(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Corrupt, restored-to-another-device, or invalidated Keystore data
            // must never become a usable authorization state.
            return new JSONObject();
        }
        }
    }

    private void write(JSONObject value) {
        synchronized (STATE_LOCK) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
            boolean committed = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(ENVELOPE_IV, Base64.encodeToString(
                            cipher.getIV(), Base64.NO_WRAP))
                    .putString(ENVELOPE_DATA, Base64.encodeToString(
                            encrypted, Base64.NO_WRAP))
                    .commit();
            if (!committed) throw new IllegalStateException("preferences commit failed");
        } catch (Exception error) {
            throw new IllegalStateException("GMAIL_VAULT_WRITE_FAILED", error);
        }
        }
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private static String cleanEmail(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (text.length() > 254 || !text.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) return "";
        return text;
    }

    private static String cleanStatus(String value) {
        String text = value == null ? "" : value.replaceAll("[^A-Za-z0-9_:-]", "");
        return text.length() <= 80 ? text : text.substring(0, 80);
    }

    private static String randomUrlToken(int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        return Base64.encodeToString(random, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
}
