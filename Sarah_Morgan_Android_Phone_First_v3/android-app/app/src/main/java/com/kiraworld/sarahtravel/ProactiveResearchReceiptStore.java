package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Profile-scoped truth record for each bounded automatic research attempt. */
public final class ProactiveResearchReceiptStore {
    private static final String PREFS = "sarah_proactive_research_receipts";

    private ProactiveResearchReceiptStore() { }

    public static void started(
            Context context,
            String personId,
            String trigger,
            int queryCount,
            long startedAt) {
        write(context, personId, receipt(
                "RUNNING", trigger, queryCount, 0, 0,
                startedAt, 0L, "", "Tavily"));
    }

    public static void succeeded(
            Context context,
            String personId,
            String trigger,
            int queryCount,
            int sourceCount,
            int savedCount,
            long startedAt,
            long completedAt) {
        write(context, personId, receipt(
                "SUCCEEDED", trigger, queryCount, sourceCount, savedCount,
                startedAt, completedAt, "", "Tavily"));
    }

    public static void failed(
            Context context,
            String personId,
            String trigger,
            int queryCount,
            int sourceCount,
            int savedCount,
            long startedAt,
            long completedAt,
            Throwable failure) {
        String failureClass = failure == null ? "unknown_failure"
                : failure.getClass().getSimpleName();
        write(context, personId, receipt(
                "FAILED", trigger, queryCount, sourceCount, savedCount,
                startedAt, completedAt, failureClass, "Tavily"));
    }

    public static String latest(Context context, String personId) {
        return preferences(context).getString(key(personId), "");
    }

    public static boolean moveProfile(Context context, String oldPersonId, String newPersonId) {
        if (clean(oldPersonId).equals(clean(newPersonId))) return true;
        SharedPreferences preferences = preferences(context);
        String oldKey = key(oldPersonId);
        String newKey = key(newPersonId);
        String source = preferences.getString(oldKey, "");
        if (source.isEmpty()) return true;
        String target = preferences.getString(newKey, "");
        if (!target.isEmpty() && !source.equals(target)
                && !ProfileMigrationArchiveStore.preserveCollision(
                        context,
                        "sarah_proactive_research_receipts",
                        oldPersonId,
                        newPersonId,
                        source,
                        target)) return false;
        SharedPreferences.Editor editor = preferences.edit();
        if (target.isEmpty()) editor.putString(newKey, source);
        if (!editor.remove(oldKey).commit()) return false;
        return !preferences.contains(oldKey)
                && (!target.isEmpty() || source.equals(preferences.getString(newKey, "")));
    }

    private static JSONObject receipt(
            String status,
            String trigger,
            int queryCount,
            int sourceCount,
            int savedCount,
            long startedAt,
            long completedAt,
            String failureClass,
            String provider) {
        JSONObject value = new JSONObject();
        try {
            value.put("status", status);
            value.put("trigger", clean(trigger));
            value.put("provider", provider);
            value.put("query_count", Math.max(0, queryCount));
            value.put("source_result_count", Math.max(0, sourceCount));
            value.put("saved_count", Math.max(0, savedCount));
            value.put("started_at", Math.max(0L, startedAt));
            value.put("completed_at", Math.max(0L, completedAt));
            value.put("failure_class", clean(failureClass));
        } catch (Exception impossible) {
            throw new IllegalStateException("Could not create research receipt", impossible);
        }
        return value;
    }

    private static void write(Context context, String personId, JSONObject receipt) {
        SharedPreferences preferences = preferences(context);
        String serialized = receipt.toString();
        if (!preferences.edit().putString(key(personId), serialized).commit()
                || !serialized.equals(preferences.getString(key(personId), ""))) {
            throw new IllegalStateException("Could not verify the research receipt");
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String personId) {
        return "latest_" + CurrentLocationPolicy.profileKey(personId);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
