package com.kiraworld.sarahtravel;

import android.content.Context;

import org.json.JSONObject;
import org.json.JSONArray;

import java.security.MessageDigest;

/** Small encrypted vehicle profile used for estimates and stop pacing. */
public final class RoadTripProfileStore {
    public static final class Vehicle {
        public final String type;
        public final double mpg;
        public final double tankGallons;
        public final double evRangeMiles;
        public final int stopEveryMiles;
        public final String notes;

        public Vehicle(
                String type,
                double mpg,
                double tankGallons,
                double evRangeMiles,
                int stopEveryMiles,
                String notes) {
            this.type = clean(type);
            this.mpg = Math.max(0, mpg);
            this.tankGallons = Math.max(0, tankGallons);
            this.evRangeMiles = Math.max(0, evRangeMiles);
            this.stopEveryMiles = Math.max(50, stopEveryMiles);
            this.notes = clean(notes);
        }

        public String summary() {
            StringBuilder out = new StringBuilder(type.isEmpty() ? "Vehicle" : type);
            if (mpg > 0) out.append(" • ").append(trim(mpg)).append(" MPG");
            if (tankGallons > 0) out.append(" • ").append(trim(tankGallons)).append(" gal tank");
            if (evRangeMiles > 0) out.append(" • ").append(trim(evRangeMiles)).append(" mi EV range");
            out.append(" • break about every ").append(stopEveryMiles).append(" mi");
            if (!notes.isEmpty()) out.append(" • ").append(notes);
            return out.toString();
        }
    }

    private static final String NAMESPACE = "road_trip_vehicle";

    private RoadTripProfileStore() { }

    public static Vehicle load(Context context, String personId) {
        String raw = SecureProfileVault.get(context, NAMESPACE, personId);
        if (raw.isEmpty()) return new Vehicle("gas", 28, 12, 0, 180, "");
        try {
            JSONObject json = new JSONObject(raw);
            return new Vehicle(
                    json.optString("type", "gas"),
                    json.optDouble("mpg", 28),
                    json.optDouble("tank_gallons", 12),
                    json.optDouble("ev_range_miles", 0),
                    json.optInt("stop_every_miles", 180),
                    json.optString("notes", ""));
        } catch (Exception ignored) {
            return new Vehicle("gas", 28, 12, 0, 180, "");
        }
    }

    public static void save(Context context, String personId, Vehicle vehicle) {
        JSONObject json = new JSONObject();
        try {
            json.put("type", vehicle.type);
            json.put("mpg", vehicle.mpg);
            json.put("tank_gallons", vehicle.tankGallons);
            json.put("ev_range_miles", vehicle.evRangeMiles);
            json.put("stop_every_miles", vehicle.stopEveryMiles);
            json.put("notes", vehicle.notes);
        } catch (Exception ignored) { }
        SecureProfileVault.put(context, NAMESPACE, personId, json.toString());
    }

    public static boolean moveProfile(Context context, String oldPersonId, String newPersonId) {
        String prior = SecureProfileVault.get(context, NAMESPACE, oldPersonId);
        if (prior.isEmpty() || oldPersonId.equals(newPersonId)) return true;
        String confirmed = SecureProfileVault.get(context, NAMESPACE, newPersonId);
        if (confirmed.isEmpty()) {
            return SecureProfileVault.moveIfTargetEmpty(
                    context, NAMESPACE, oldPersonId, newPersonId);
        }
        if (confirmed.equals(prior)) {
            SecureProfileVault.remove(context, NAMESPACE, oldPersonId);
            return SecureProfileVault.get(context, NAMESPACE, oldPersonId).isEmpty();
        }
        try {
            JSONObject merged = new JSONObject(confirmed);
            JSONArray conflicts = merged.optJSONArray("migrated_profile_conflicts");
            if (conflicts == null) conflicts = new JSONArray();
            String fingerprint = sha256(prior);
            boolean exists = false;
            for (int index = 0; index < conflicts.length(); index++) {
                if (fingerprint.equals(conflicts.optJSONObject(index) == null ? ""
                        : conflicts.optJSONObject(index).optString("sha256", ""))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                JSONObject conflict = new JSONObject();
                conflict.put("sha256", fingerprint);
                conflict.put("source_person_id", oldPersonId);
                try { conflict.put("record", new JSONObject(prior)); }
                catch (Exception malformed) { conflict.put("raw_record", prior); }
                conflicts.put(conflict);
            }
            merged.put("migrated_profile_conflicts", conflicts);
            String output = merged.toString();
            if (SecureProfileVault.putVerified(context, NAMESPACE, newPersonId, output)) {
                SecureProfileVault.remove(context, NAMESPACE, oldPersonId);
                return SecureProfileVault.get(context, NAMESPACE, oldPersonId).isEmpty();
            }
        } catch (Exception ignored) {
            // Preserve both records; a failed merge must never erase the placeholder record.
        }
        return false;
    }

    private static String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte item : bytes) out.append(String.format("%02x", item));
        return out.toString();
    }

    private static String trim(double value) {
        long rounded = Math.round(value);
        return Math.abs(value - rounded) < 0.05 ? String.valueOf(rounded) : String.format("%.1f", value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
