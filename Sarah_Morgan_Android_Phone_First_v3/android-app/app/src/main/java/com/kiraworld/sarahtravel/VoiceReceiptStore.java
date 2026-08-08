package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Append-only voice route evidence. Each playback attempt is stored under its
 * own immutable key; the index and latest pointer are conveniences, not the
 * sole copy of an event.
 */
public final class VoiceReceiptStore {
    private static final String PREFS = "sarah_voice_receipts";
    private static final String EVENT_PREFIX = "voice_receipt_event_";
    private static final String INDEX_PREFIX = "voice_receipt_index_";
    private static final String LATEST_PREFIX = "voice_receipt_";

    private VoiceReceiptStore() { }

    public static synchronized boolean append(
            Context context,
            String personId,
            String turnId,
            JSONObject receipt) {
        if (context == null || receipt == null) return false;
        String profile = CurrentLocationPolicy.profileKey(personId);
        long recordedAt = System.currentTimeMillis();
        String exactTurnId = cleanTurnId(turnId, "voice-turn-" + recordedAt);
        String receiptId = exactTurnId + "-" + recordedAt + "-"
                + UUID.randomUUID().toString().replace("-", "");
        try {
            JSONObject event = new JSONObject(receipt.toString());
            event.put("receipt_id", receiptId);
            event.put("turn_id", exactTurnId);
            event.put("person_profile_key", profile);
            event.put("recorded_at", recordedAt);
            String serialized = event.toString();
            SharedPreferences preferences = preferences(context);
            String eventKey = eventKey(profile, receiptId);

            if (!preferences.edit().putString(eventKey, serialized).commit()
                    || !serialized.equals(preferences.getString(eventKey, ""))) {
                return false;
            }

            LinkedHashSet<String> index = readIndex(preferences, profile);
            index.add(receiptId);
            String serializedIndex = toJson(index).toString();
            boolean indexed = preferences.edit()
                    .putString(indexKey(profile), serializedIndex)
                    .putString(latestKey(profile), serialized)
                    .commit();
            // Even if the index write fails, the uniquely keyed event remains
            // recoverable by prefix scan and must not be deleted.
            return indexed && serializedIndex.equals(
                    preferences.getString(indexKey(profile), ""));
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Crash-resumable profile migration that never deletes before verification. */
    public static synchronized boolean moveProfile(
            Context context,
            String oldPersonId,
            String newPersonId) {
        String oldProfile = CurrentLocationPolicy.profileKey(oldPersonId);
        String newProfile = CurrentLocationPolicy.profileKey(newPersonId);
        if (oldProfile.equals(newProfile)) return true;

        SharedPreferences preferences = preferences(context);
        LinkedHashMap<String, String> sourceEvents = scanEvents(preferences, oldProfile);
        String legacy = preferences.getString(latestKey(oldProfile), "");
        if (!legacy.isEmpty() && !sourceEvents.containsValue(legacy)) {
            sourceEvents.put("legacy-" + shortHash(legacy), legacy);
        }
        if (sourceEvents.isEmpty()) return true;

        LinkedHashSet<String> targetIndex = readIndex(preferences, newProfile);
        List<String> copiedSourceKeys = new ArrayList<>();
        String latestCopied = "";
        for (Map.Entry<String, String> source : sourceEvents.entrySet()) {
            String receiptId = cleanTurnId(source.getKey(), "migrated-" + shortHash(source.getValue()));
            String targetKey = eventKey(newProfile, receiptId);
            String existing = preferences.getString(targetKey, "");
            if (!existing.isEmpty() && !existing.equals(source.getValue())) {
                receiptId = receiptId + "-migrated-" + shortHash(source.getValue());
                targetKey = eventKey(newProfile, receiptId);
                existing = preferences.getString(targetKey, "");
            }
            if (existing.isEmpty()) {
                if (!preferences.edit().putString(targetKey, source.getValue()).commit()) return false;
            }
            if (!source.getValue().equals(preferences.getString(targetKey, ""))) return false;
            targetIndex.add(receiptId);
            latestCopied = source.getValue();
            if (!source.getKey().startsWith("legacy-")) {
                copiedSourceKeys.add(eventKey(oldProfile, source.getKey()));
            }
        }

        String serializedIndex = toJson(targetIndex).toString();
        SharedPreferences.Editor destination = preferences.edit()
                .putString(indexKey(newProfile), serializedIndex);
        if (!latestCopied.isEmpty() && preferences.getString(latestKey(newProfile), "").isEmpty()) {
            destination.putString(latestKey(newProfile), latestCopied);
        }
        if (!destination.commit()
                || !serializedIndex.equals(preferences.getString(indexKey(newProfile), ""))) {
            return false;
        }

        SharedPreferences.Editor cleanup = preferences.edit()
                .remove(indexKey(oldProfile))
                .remove(latestKey(oldProfile));
        for (String sourceKey : copiedSourceKeys) cleanup.remove(sourceKey);
        return cleanup.commit();
    }

    private static LinkedHashMap<String, String> scanEvents(
            SharedPreferences preferences,
            String profile) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        String prefix = EVENT_PREFIX + profile + "_";
        Set<String> indexed = readIndex(preferences, profile);
        for (String receiptId : indexed) {
            String value = preferences.getString(eventKey(profile, receiptId), "");
            if (!value.isEmpty()) result.put(receiptId, value);
        }
        for (Map.Entry<String, ?> item : preferences.getAll().entrySet()) {
            if (!item.getKey().startsWith(prefix) || !(item.getValue() instanceof String)) continue;
            String receiptId = item.getKey().substring(prefix.length());
            result.putIfAbsent(receiptId, (String) item.getValue());
        }
        return result;
    }

    private static LinkedHashSet<String> readIndex(
            SharedPreferences preferences,
            String profile) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        try {
            JSONArray array = new JSONArray(preferences.getString(indexKey(profile), "[]"));
            for (int index = 0; index < array.length(); index++) {
                String value = array.optString(index, "");
                if (!value.isEmpty()) result.add(value);
            }
        } catch (Exception ignored) { }
        return result;
    }

    private static JSONArray toJson(Set<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String eventKey(String profile, String receiptId) {
        return EVENT_PREFIX + profile + "_" + receiptId;
    }

    private static String indexKey(String profile) {
        return INDEX_PREFIX + profile;
    }

    private static String latestKey(String profile) {
        return LATEST_PREFIX + profile;
    }

    private static String cleanTurnId(String value, String fallback) {
        String clean = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        return clean.isEmpty() ? fallback : clean;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int index = 0; index < 10; index++) out.append(String.format("%02x", digest[index]));
            return out.toString();
        } catch (Exception ignored) {
            return "unavailable";
        }
    }
}
