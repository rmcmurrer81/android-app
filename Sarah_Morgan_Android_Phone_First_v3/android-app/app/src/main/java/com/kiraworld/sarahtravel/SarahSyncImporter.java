package com.kiraworld.sarahtravel;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SarahSyncImporter {
    private SarahSyncImporter() { }

    public static int importPayload(Context context, JSONObject payload) throws Exception {
        if (!"sarah-sync-v1".equals(payload.optString("schema"))) {
            throw new IllegalArgumentException("Unsupported Sarah sync schema");
        }
        SarahDatabase db = new SarahDatabase(context);
        SyncSeenStore seen = new SyncSeenStore(context);
        int count = 0;
        String discoveryPersonId = "";
        try {
            JSONObject profileObject = payload.optJSONObject("profile");
            if (profileObject == null) {
                throw new SecurityException("Trusted sync requires an exact confirmed owner profile.");
            }
            Map<String, String> incomingProfile = profile(profileObject);
            String currentName = db.getProfile().getOrDefault("name", "Traveler");
            String incomingName = incomingProfile.getOrDefault("name", "");
            if (!ProfileMigrationPolicy.isConfirmedDisplayName(incomingName)) {
                throw new SecurityException("Trusted sync requires an exact confirmed owner name.");
            }
            boolean pendingOwnerConfirmation = db.isPlaceholderOwner();
            boolean sameOwner = currentName.equalsIgnoreCase(incomingName);
            if (!pendingOwnerConfirmation && !sameOwner) {
                throw new SecurityException(
                        "The paired device uses a different active person. Confirm or switch profiles before importing owner-bound data.");
            }
            PersonProfileStore people = new PersonProfileStore(context);
            try {
                if (pendingOwnerConfirmation) {
                    people.stageOwnerCandidate(incomingProfile);
                    throw new SecurityException(
                            "Confirm the restored owner name on this phone, then run sync again. No owner-bound rows were imported.");
                }
                db.mergeSyncedOwnerProfile(incomingProfile);
                discoveryPersonId = people.ensureOwner(db.getProfile())
                        .getOrDefault("person_id", "");
            } finally {
                people.close();
            }
            String defaultSpeaker = incomingName;

            JSONArray messages = payload.optJSONArray("messages");
            if (messages != null) for (int i = 0; i < messages.length(); i++) {
                JSONObject row = messages.optJSONObject(i);
                if (row == null || !seen.first(row.optString("event_id", row.optString("id")))) continue;
                db.addMessage(
                        row.optString("role", "user"),
                        row.optString("content", ""),
                        importSpeaker(
                                row.optString("speaker_name", defaultSpeaker),
                                defaultSpeaker,
                                incomingName,
                                pendingOwnerConfirmation),
                        row.optString("route", TurnRoute.UNKNOWN_LEGACY));
                count++;
            }

            JSONArray memories = payload.optJSONArray("memories");
            if (memories != null) for (int i = 0; i < memories.length(); i++) {
                JSONObject row = memories.optJSONObject(i);
                if (row == null) continue;
                String id = row.optString("memory_id", row.optString("category") + "|" + row.optString("summary"));
                if (!seen.first(id)) continue;
                db.addMemory(
                        row.optString("category", "memory"),
                        row.optString("summary", ""),
                        row.optString("source", row.optString("source_text", "trusted sync")));
                count++;
            }

            JSONArray trips = payload.optJSONArray("trips");
            if (trips != null) for (int i = 0; i < trips.length(); i++) {
                JSONObject row = trips.optJSONObject(i);
                if (row == null) continue;
                String id = row.optString("trip_id", row.optString("title") + "|" + row.optString("destination"));
                if (!seen.first(id)) continue;
                db.addTrip(
                        row.optString("title", "Trip"),
                        row.optString("destination", ""),
                        row.optString("status", "planned"),
                        row.optString("notes", ""));
                count++;
            }

            JSONArray wishes = payload.optJSONArray("wishes");
            if (wishes != null) for (int i = 0; i < wishes.length(); i++) {
                JSONObject row = wishes.optJSONObject(i);
                if (row == null) continue;
                String id = row.optString("wish_id", row.optString("destination"));
                if (!seen.first(id)) continue;
                db.addWish(row.optString("destination", ""), row.optString("notes", ""));
                count++;
            }

            JSONArray mind = payload.optJSONArray("mind_events");
            if (mind != null) for (int i = 0; i < mind.length(); i++) {
                JSONObject row = mind.optJSONObject(i);
                if (row == null || !seen.first(row.optString("event_id"))) continue;
                MindEventStore.recordLocal(
                        context,
                        importSpeaker(
                                row.optString("speaker", defaultSpeaker),
                                defaultSpeaker,
                                incomingName,
                                pendingOwnerConfirmation),
                        row.optString("spoken", ""),
                        row.optString("private_mind", ""),
                        row.optString("factual_truth", ""),
                        row.optString("classification", "UNCERTAIN_BELIEF"));
                count++;
            }

            JSONArray discoveries = payload.optJSONArray("discoveries");
            if (discoveries != null) {
                ProactiveDiscoveryStore store = new ProactiveDiscoveryStore(context);
                try {
                    for (int i = 0; i < discoveries.length(); i++) {
                        JSONObject row = discoveries.optJSONObject(i);
                        if (row == null) continue;
                        String id = row.optString("discovery_id", row.optString("url"));
                        if (!seen.first(id)) continue;
                        TavilyClient.Result result = new TavilyClient.Result(
                                row.optString("title", "Possible travel match"),
                                row.optString("url", ""),
                                row.optString("summary", ""));
                        String discoverySpeaker = importSpeaker(
                                        row.optString("speaker", defaultSpeaker),
                                        defaultSpeaker,
                                        incomingName,
                                        pendingOwnerConfirmation);
                        if (store.addSynced(
                                discoveryPersonId,
                                discoverySpeaker,
                                result,
                                row.optString("query_text", row.optString("query", "trusted sync")),
                                row.optString("category", "synced"),
                                row.optString("source", ""),
                                row.optLong("source_time", 0L))) count++;
                    }
                } finally {
                    store.close();
                }
            }

            JSONArray photos = payload.optJSONArray("photos");
            if (photos != null) for (int i = 0; i < photos.length(); i++) {
                JSONObject row = photos.optJSONObject(i);
                if (row == null || !seen.first(row.optString("photo_id", row.optString("sha256")))) continue;
                byte[] bytes = Base64.decode(row.optString("jpeg_base64", ""), Base64.DEFAULT);
                if (bytes.length == 0 || bytes.length > 4_000_000) continue;
                File dir = new File(context.getFilesDir(), "photos");
                dir.mkdirs();
                File file = new File(dir, row.optString("sha256", String.valueOf(System.nanoTime())) + ".jpg");
                try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
                db.addPhoto(file.getAbsolutePath(), row.optString("caption", "Synced trip photo"));
                count++;
            }
            return count;
        } finally {
            seen.close();
            db.close();
        }
    }

    private static Map<String, String> profile(JSONObject row) {
        Map<String, String> result = new LinkedHashMap<>();
        if (row == null) return result;
        for (String key : new String[]{
                "name", "age", "age_known", "hometown", "interests", "memory_consent"
        }) {
            if (!row.has(key) || row.isNull(key)) continue;
            Object value = row.opt(key);
            result.put(key, value == null ? "" : String.valueOf(value));
        }
        return result;
    }

    private static String importSpeaker(
            String rawSpeaker,
            String defaultSpeaker,
            String incomingOwner,
            boolean pendingOwnerConfirmation) {
        String speaker = rawSpeaker == null ? "" : rawSpeaker.trim();
        if (speaker.isEmpty()) return defaultSpeaker;
        if (pendingOwnerConfirmation
                && !incomingOwner.isEmpty()
                && speaker.equalsIgnoreCase(incomingOwner)) return defaultSpeaker;
        return speaker;
    }
}
