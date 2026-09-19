package com.kiraworld.sarahtravel;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Encrypted append-only evidence for exact profile-data collisions.
 *
 * The active confirmed profile remains usable, while both pre-merge payloads
 * remain recoverable for owner review. Records are deterministic and
 * idempotent, so a crash-resumed migration does not manufacture duplicates.
 */
public final class ProfileMigrationArchiveStore {
    private static final String NAMESPACE = "profile_migration_archive_v1";

    private ProfileMigrationArchiveStore() { }

    public static synchronized boolean preserveCollision(
            Context context,
            String store,
            String oldPersonId,
            String newPersonId,
            String sourcePayload,
            String targetPayload) {
        String source = raw(sourcePayload);
        String target = raw(targetPayload);
        if (source.isEmpty() || target.isEmpty() || source.equals(target)) return true;

        String id = ProfileMigrationPolicy.collisionRecordId(
                store, oldPersonId, newPersonId, source, target);
        JSONArray records = read(context, newPersonId);
        if (records == null) return false;
        for (int index = 0; index < records.length(); index++) {
            JSONObject existing = records.optJSONObject(index);
            if (exact(existing, id, store, oldPersonId, newPersonId, source, target)) {
                return true;
            }
        }

        JSONObject record = new JSONObject();
        try {
            record.put("schema", "sarah.profile_migration_collision.v1");
            record.put("record_id", id);
            record.put("store", clean(store));
            record.put("old_person_id", clean(oldPersonId));
            record.put("new_person_id", clean(newPersonId));
            record.put("source_sha256", ProfileMigrationPolicy.sha256(source));
            record.put("target_sha256", ProfileMigrationPolicy.sha256(target));
            record.put("source_payload", source);
            record.put("target_payload", target);
            records.put(record);
        } catch (Exception ignored) {
            return false;
        }

        if (!SecureProfileVault.putVerified(
                context, NAMESPACE, newPersonId, records.toString())) return false;
        return containsExact(
                context, store, oldPersonId, newPersonId, source, target);
    }

    public static synchronized boolean containsExact(
            Context context,
            String store,
            String oldPersonId,
            String newPersonId,
            String sourcePayload,
            String targetPayload) {
        String source = raw(sourcePayload);
        String target = raw(targetPayload);
        if (source.isEmpty() || target.isEmpty() || source.equals(target)) return true;
        String id = ProfileMigrationPolicy.collisionRecordId(
                store, oldPersonId, newPersonId, source, target);
        JSONArray records = read(context, newPersonId);
        if (records == null) return false;
        for (int index = 0; index < records.length(); index++) {
            if (exact(records.optJSONObject(index), id, store, oldPersonId,
                    newPersonId, source, target)) return true;
        }
        return false;
    }

    private static JSONArray read(Context context, String personId) {
        String raw = SecureProfileVault.get(context, NAMESPACE, personId);
        if (raw.isEmpty()) return new JSONArray();
        try { return new JSONArray(raw); }
        catch (Exception ignored) { return null; }
    }

    private static boolean exact(
            JSONObject item,
            String id,
            String store,
            String oldPersonId,
            String newPersonId,
            String source,
            String target) {
        return item != null
                && id.equals(item.optString("record_id", ""))
                && clean(store).equals(item.optString("store", ""))
                && clean(oldPersonId).equals(item.optString("old_person_id", ""))
                && clean(newPersonId).equals(item.optString("new_person_id", ""))
                && ProfileMigrationPolicy.sha256(source).equals(
                        item.optString("source_sha256", ""))
                && ProfileMigrationPolicy.sha256(target).equals(
                        item.optString("target_sha256", ""))
                && source.equals(item.optString("source_payload", ""))
                && target.equals(item.optString("target_payload", ""));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String raw(String value) {
        return value == null ? "" : value;
    }
}
