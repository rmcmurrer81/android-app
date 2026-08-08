package com.kiraworld.sarahtravel;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.Map;

public final class SarahSyncExporter {
    private SarahSyncExporter() { }

    public static JSONObject export(Context context) throws Exception {
        SarahDatabase database = new SarahDatabase(context);
        JSONObject output = new JSONObject();
        try {
            Map<String, String> profile = database.getProfile();
            output.put("schema", "sarah-sync-v1");
            output.put("device_id", TrustedDeviceStore.localDeviceId(context));
            output.put("created_at", System.currentTimeMillis());
            output.put("profile", new JSONObject(profile));
            output.put("messages", array(database.recentMessages(200)));
            output.put("memories", memories(database.listMemories(200)));
            output.put("trips", trips(database.listTrips(100)));
            output.put("wishes", wishes(database.listWishes(100)));
            output.put("photos", photos(database.listPhotos(25)));
            MindEventStore mind = new MindEventStore(context);
            try {
                output.put("mind_events", mind.exportEncrypted(500));
            } finally {
                mind.close();
            }
            PersonProfileStore people = new PersonProfileStore(context);
            Map<String, String> ownerPerson;
            try {
                ownerPerson = people.findByName(profile.getOrDefault("name", ""));
                if (ownerPerson.isEmpty()) ownerPerson = people.ensureOwner(profile);
            } finally {
                people.close();
            }
            String personId = ownerPerson.getOrDefault("person_id", "");
            ProactiveDiscoveryStore discoveries = new ProactiveDiscoveryStore(context);
            try {
                discoveries.claimLegacyProfile(
                        personId, profile.getOrDefault("name", "Traveler"));
                output.put("discoveries", array(discoveries.list(personId, 100)));
            } finally {
                discoveries.close();
            }
            return output;
        } finally {
            database.close();
        }
    }

    private static JSONArray array(List<Map<String, String>> rows) {
        JSONArray array = new JSONArray();
        for (Map<String, String> row : rows) array.put(new JSONObject(row));
        return array;
    }

    private static JSONArray memories(List<Map<String, String>> rows) {
        JSONArray array = new JSONArray();
        for (Map<String, String> row : rows) {
            JSONObject value = new JSONObject(row);
            try {
                value.put("memory_id", "android-memory-" + Math.abs((
                        row.getOrDefault("category", "") + "|"
                                + row.getOrDefault("summary", "")).hashCode()));
            } catch (Exception ignored) { }
            array.put(value);
        }
        return array;
    }

    private static JSONArray trips(List<Map<String, String>> rows) {
        JSONArray array = new JSONArray();
        for (Map<String, String> row : rows) {
            JSONObject value = new JSONObject(row);
            try {
                value.put("trip_id", "android-trip-" + Math.abs((
                        row.getOrDefault("title", "") + "|"
                                + row.getOrDefault("destination", "")).hashCode()));
            } catch (Exception ignored) { }
            array.put(value);
        }
        return array;
    }

    private static JSONArray wishes(List<Map<String, String>> rows) {
        JSONArray array = new JSONArray();
        for (Map<String, String> row : rows) {
            JSONObject value = new JSONObject(row);
            try {
                value.put("wish_id", "android-wish-" + Math.abs(
                        row.getOrDefault("destination", "").toLowerCase().hashCode()));
            } catch (Exception ignored) { }
            array.put(value);
        }
        return array;
    }

    private static JSONArray photos(List<Map<String, String>> rows) {
        JSONArray array = new JSONArray();
        long total = 0;
        for (Map<String, String> row : rows) {
            try {
                File file = new File(row.getOrDefault("local_path", ""));
                byte[] derivative = ImageSanitizer.syncDerivative(file);
                if (!SyncPhotoPolicy.accepted(derivative, total)) continue;
                String sha256 = SyncPhotoPolicy.sha256(derivative);
                JSONObject value = new JSONObject();
                value.put("photo_id", "sync-photo-" + sha256.substring(0, 24));
                value.put("sha256", sha256);
                value.put("caption", row.getOrDefault("caption", "Sanitized trip photo"));
                value.put("created_at", row.getOrDefault("created_at", "0"));
                value.put("media_type", "image/jpeg");
                value.put("metadata_policy", "PIXELS_REENCODED_EXIF_AND_LOCATION_STRIPPED");
                value.put("byte_length", derivative.length);
                value.put("jpeg_base64", Base64.encodeToString(derivative, Base64.NO_WRAP));
                array.put(value);
                total += derivative.length;
            } catch (Exception ignored) { }
        }
        return array;
    }
}
