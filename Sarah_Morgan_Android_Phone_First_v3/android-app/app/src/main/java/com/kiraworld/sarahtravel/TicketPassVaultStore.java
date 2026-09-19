package com.kiraworld.sarahtravel;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Profile-isolated Android-Keystore-encrypted ticket/pass records.
 *
 * Only an owner-selected sanitized image and descriptive travel metadata are
 * accepted. Passwords, payment-card data and proof-of-purchase claims are not
 * fields in this store.
 */
public final class TicketPassVaultStore {
    public static final class Entry {
        public final String id;
        public final String title;
        public final String eventDate;
        public final String officialUrl;
        public final String sourceStatus;
        public final long addedAt;
        private final String imageBase64;

        Entry(
                String id,
                String title,
                String eventDate,
                String officialUrl,
                String sourceStatus,
                long addedAt,
                String imageBase64) {
            this.id = clean(id);
            this.title = TicketPassPolicy.bounded(title, TicketPassPolicy.MAX_TITLE_CHARS);
            this.eventDate = TicketPassPolicy.bounded(eventDate, TicketPassPolicy.MAX_DATE_CHARS);
            this.officialUrl = TicketPassPolicy.exactHttpsUrl(officialUrl);
            this.sourceStatus = TicketPassPolicy.isVerifiedEventSource(sourceStatus)
                    ? TicketPassPolicy.VERIFIED_EVENT_SOURCE
                    : TicketPassPolicy.OWNER_PROVIDED_SOURCE;
            this.addedAt = Math.max(0L, addedAt);
            this.imageBase64 = clean(imageBase64);
        }

        public byte[] imageBytes() {
            try {
                byte[] decoded = Base64.decode(imageBase64, Base64.NO_WRAP);
                return decoded.length > 0
                        && decoded.length <= TicketPassPolicy.MAX_ENCRYPTED_IMAGE_BYTES
                        ? decoded : new byte[0];
            } catch (Exception ignored) {
                return new byte[0];
            }
        }
    }

    private static final String NAMESPACE = "ticket_pass_wallet";

    private TicketPassVaultStore() { }

    public static List<Entry> list(Context context, String personId) {
        List<Entry> result = new ArrayList<>();
        String raw = SecureProfileVault.getOrThrow(context, NAMESPACE, personId);
        if (raw.isEmpty()) return result;
        try {
            JSONArray array = new JSONArray(raw);
            if (array.length() > TicketPassPolicy.MAX_PASSES_PER_PROFILE) {
                throw new IllegalStateException("Encrypted ticket/pass wallet exceeds its bound.");
            }
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.getJSONObject(index);
                Entry entry = fromJson(item);
                byte[] image = entry.imageBytes();
                if (entry.id.isEmpty() || entry.title.isEmpty() || image.length < 1) {
                    throw new IllegalStateException("Encrypted ticket/pass record is incomplete.");
                }
                result.add(entry);
            }
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Encrypted ticket/pass wallet is unreadable; no record was changed.",
                    error);
        }
        return result;
    }

    public static boolean add(
            Context context,
            String personId,
            String title,
            String eventDate,
            String officialUrl,
            boolean verifiedEventSource,
            byte[] sanitizedJpeg) {
        List<Entry> entries = list(context, personId);
        int byteCount = sanitizedJpeg == null ? 0 : sanitizedJpeg.length;
        if (!TicketPassPolicy.canStore(entries.size(), title, byteCount)) return false;
        String exactUrl = TicketPassPolicy.exactHttpsUrl(officialUrl);
        if (!clean(officialUrl).isEmpty() && exactUrl.isEmpty()) return false;
        entries.add(new Entry(
                UUID.randomUUID().toString(),
                title,
                eventDate,
                exactUrl,
                TicketPassPolicy.sourceStatus(verifiedEventSource),
                System.currentTimeMillis(),
                Base64.encodeToString(sanitizedJpeg, Base64.NO_WRAP)));
        return saveVerified(context, personId, entries);
    }

    public static boolean remove(Context context, String personId, String id) {
        List<Entry> kept = new ArrayList<>();
        for (Entry entry : list(context, personId)) {
            if (!entry.id.equals(clean(id))) kept.add(entry);
        }
        return saveVerified(context, personId, kept);
    }

    public static boolean moveProfile(Context context, String oldPersonId, String newPersonId) {
        String oldRaw = SecureProfileVault.getOrThrow(context, NAMESPACE, oldPersonId);
        if (oldRaw.isEmpty()) return true;
        String newRaw = SecureProfileVault.getOrThrow(context, NAMESPACE, newPersonId);
        if (newRaw.isEmpty()) {
            return SecureProfileVault.moveIfTargetEmpty(
                    context, NAMESPACE, oldPersonId, newPersonId);
        }
        if (oldRaw.equals(newRaw)) {
            return SecureProfileVault.removeVerified(context, NAMESPACE, oldPersonId);
        }

        List<Entry> merged = new ArrayList<>(list(context, newPersonId));
        Set<String> identities = new LinkedHashSet<>();
        for (Entry entry : merged) identities.add(identity(entry));
        for (Entry entry : list(context, oldPersonId)) {
            if (merged.size() >= TicketPassPolicy.MAX_PASSES_PER_PROFILE) return false;
            if (identities.add(identity(entry))) merged.add(entry);
        }
        if (!saveVerified(context, newPersonId, merged)) return false;
        return SecureProfileVault.removeVerified(context, NAMESPACE, oldPersonId);
    }

    private static boolean saveVerified(
            Context context,
            String personId,
            List<Entry> entries) {
        String raw = serialize(entries);
        return SecureProfileVault.putVerified(context, NAMESPACE, personId, raw);
    }

    private static String serialize(List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            try {
                JSONObject item = new JSONObject();
                item.put("id", entry.id);
                item.put("title", entry.title);
                item.put("event_date", entry.eventDate);
                item.put("official_url", entry.officialUrl);
                item.put("source_status", entry.sourceStatus);
                item.put("added_at", entry.addedAt);
                item.put("image_jpeg_base64", entry.imageBase64);
                array.put(item);
            } catch (Exception error) {
                throw new IllegalStateException("Ticket/pass wallet serialization failed.", error);
            }
        }
        return array.toString();
    }

    private static Entry fromJson(JSONObject item) throws Exception {
        String sourceStatus = item.getString("source_status");
        if (!TicketPassPolicy.VERIFIED_EVENT_SOURCE.equals(sourceStatus)
                && !TicketPassPolicy.OWNER_PROVIDED_SOURCE.equals(sourceStatus)) {
            throw new IllegalStateException("Ticket/pass source provenance is invalid.");
        }
        return new Entry(
                item.getString("id"),
                item.getString("title"),
                item.getString("event_date"),
                item.getString("official_url"),
                sourceStatus,
                item.getLong("added_at"),
                item.getString("image_jpeg_base64"));
    }

    private static String identity(Entry entry) {
        return (entry.title + "|" + entry.eventDate + "|" + entry.officialUrl)
                .toLowerCase(java.util.Locale.US);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
