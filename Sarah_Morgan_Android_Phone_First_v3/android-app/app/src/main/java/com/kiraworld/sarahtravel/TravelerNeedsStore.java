package com.kiraworld.sarahtravel;

import android.content.Context;

import org.json.JSONObject;

/** Encrypted local accessibility, sensory, dietary, pace, and green preferences. */
public final class TravelerNeedsStore {
    public static final class Needs {
        public final String mobility;
        public final String walking;
        public final String stairs;
        public final String sensory;
        public final String visionHearing;
        public final String dietary;
        public final String pace;
        public final String sustainability;
        public final String notes;

        public Needs(
                String mobility,
                String walking,
                String stairs,
                String sensory,
                String visionHearing,
                String dietary,
                String pace,
                String sustainability,
                String notes) {
            this.mobility = clean(mobility);
            this.walking = clean(walking);
            this.stairs = clean(stairs);
            this.sensory = clean(sensory);
            this.visionHearing = clean(visionHearing);
            this.dietary = clean(dietary);
            this.pace = clean(pace);
            this.sustainability = clean(sustainability);
            this.notes = clean(notes);
        }

        public boolean isEmpty() {
            return summary().isEmpty();
        }

        public String summary() {
            StringBuilder out = new StringBuilder();
            append(out, "Mobility", mobility);
            append(out, "Walking", walking);
            append(out, "Stairs", stairs);
            append(out, "Sensory", sensory);
            append(out, "Vision/hearing", visionHearing);
            append(out, "Food", dietary);
            append(out, "Pace", pace);
            append(out, "Greener travel", sustainability);
            append(out, "Other", notes);
            return out.toString();
        }
    }

    private static final String NAMESPACE = "traveler_needs";

    private TravelerNeedsStore() { }

    public static Needs load(Context context, String personId) {
        String raw = SecureProfileVault.get(context, NAMESPACE, personId);
        if (raw.isEmpty()) return empty();
        try {
            JSONObject json = new JSONObject(raw);
            return new Needs(
                    json.optString("mobility", ""),
                    json.optString("walking", ""),
                    json.optString("stairs", ""),
                    json.optString("sensory", ""),
                    json.optString("vision_hearing", ""),
                    json.optString("dietary", ""),
                    json.optString("pace", ""),
                    json.optString("sustainability", ""),
                    json.optString("notes", ""));
        } catch (Exception ignored) {
            return empty();
        }
    }

    public static void save(Context context, String personId, Needs needs) {
        JSONObject json = new JSONObject();
        try {
            json.put("mobility", needs.mobility);
            json.put("walking", needs.walking);
            json.put("stairs", needs.stairs);
            json.put("sensory", needs.sensory);
            json.put("vision_hearing", needs.visionHearing);
            json.put("dietary", needs.dietary);
            json.put("pace", needs.pace);
            json.put("sustainability", needs.sustainability);
            json.put("notes", needs.notes);
        } catch (Exception ignored) { }
        SecureProfileVault.put(context, NAMESPACE, personId, json.toString());
    }

    public static String summary(Context context, String personId) {
        return load(context, personId).summary();
    }

    private static Needs empty() {
        return new Needs("", "", "", "", "", "", "", "", "");
    }

    private static void append(StringBuilder out, String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (out.length() > 0) out.append("; ");
        out.append(label).append(": ").append(value.trim());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
