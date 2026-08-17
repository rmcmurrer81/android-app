package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Stores only a profile-scoped, human-readable approximate area; never raw coordinates. */
public final class SarahLocationStore {
    private static final String PREFS = "sarah_approximate_locations";
    private static final String PENDING_OWNER_MOVE = "pending_owner_profile_move_ids";
    private final Context context;
    private final SharedPreferences preferences;

    public SarahLocationStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void save(String personId, String area, long capturedAt) {
        save(personId, area, capturedAt, CurrentLocationPolicy.SOURCE_UNKNOWN);
    }

    public void save(String personId, String area, long capturedAt, String source) {
        String key = CurrentLocationPolicy.profileKey(personId);
        preferences.edit()
                .putString(key + "_area", clean(area))
                .putLong(key + "_captured_at", capturedAt)
                .putString(key + "_source", cleanSource(source))
                .apply();
    }

    public String freshArea(String personId, long now) {
        String key = CurrentLocationPolicy.profileKey(personId);
        long capturedAt = preferences.getLong(key + "_captured_at", 0L);
        if (!CurrentLocationPolicy.fresh(capturedAt, now)) return "";
        return clean(preferences.getString(key + "_area", ""));
    }

    public String source(String personId) {
        String key = CurrentLocationPolicy.profileKey(personId);
        return cleanSource(preferences.getString(
                key + "_source", CurrentLocationPolicy.SOURCE_UNKNOWN));
    }

    public void clear(String personId) {
        String key = CurrentLocationPolicy.profileKey(personId);
        preferences.edit()
                .remove(key + "_area")
                .remove(key + "_captured_at")
                .remove(key + "_source")
                .apply();
    }

    public void setNearbyEnabled(String personId, boolean enabled) {
        String key = CurrentLocationPolicy.profileKey(personId);
        preferences.edit().putBoolean(key + "_nearby_enabled", enabled).apply();
    }

    public boolean nearbyEnabled(String personId) {
        String key = CurrentLocationPolicy.profileKey(personId);
        return preferences.getBoolean(key + "_nearby_enabled", false);
    }

    public void setBackgroundResearchEnabled(String personId, boolean enabled) {
        String key = CurrentLocationPolicy.profileKey(personId);
        preferences.edit().putBoolean(key + "_background_research_enabled", enabled).apply();
    }

    public boolean backgroundResearchEnabled(String personId) {
        String key = CurrentLocationPolicy.profileKey(personId);
        return preferences.getBoolean(key + "_background_research_enabled", false);
    }

    public boolean moveProfile(String oldPersonId, String newPersonId) {
        String oldKey = CurrentLocationPolicy.profileKey(oldPersonId);
        String newKey = CurrentLocationPolicy.profileKey(newPersonId);
        if (oldKey.equals(newKey)) return true;
        String sourcePayload = migrationPayload(oldKey);
        if (sourcePayload.isEmpty()) return true;
        String targetPayload = migrationPayload(newKey);
        if (!targetPayload.isEmpty() && !sourcePayload.equals(targetPayload)
                && !ProfileMigrationArchiveStore.preserveCollision(
                        context,
                        "sarah_approximate_locations",
                        oldPersonId,
                        newPersonId,
                        sourcePayload,
                        targetPayload)) return false;
        SharedPreferences.Editor editor = preferences.edit();
        if (!preferences.contains(newKey + "_area") && preferences.contains(oldKey + "_area")) {
            editor.putString(newKey + "_area", preferences.getString(oldKey + "_area", ""));
            editor.putLong(newKey + "_captured_at", preferences.getLong(oldKey + "_captured_at", 0L));
            editor.putString(
                    newKey + "_source",
                    cleanSource(preferences.getString(
                            oldKey + "_source", CurrentLocationPolicy.SOURCE_UNKNOWN)));
        }
        if (!preferences.contains(newKey + "_nearby_enabled")
                && preferences.contains(oldKey + "_nearby_enabled")) {
            editor.putBoolean(
                    newKey + "_nearby_enabled",
                    preferences.getBoolean(oldKey + "_nearby_enabled", false));
        }
        if (!preferences.contains(newKey + "_background_research_enabled")
                && preferences.contains(oldKey + "_background_research_enabled")) {
            editor.putBoolean(
                    newKey + "_background_research_enabled",
                    preferences.getBoolean(oldKey + "_background_research_enabled", false));
        }
        return editor.remove(oldKey + "_area")
                .remove(oldKey + "_captured_at")
                .remove(oldKey + "_source")
                .remove(oldKey + "_nearby_enabled")
                .remove(oldKey + "_background_research_enabled")
                .commit();
    }

    private String migrationPayload(String key) {
        boolean hasArea = preferences.contains(key + "_area");
        boolean hasNearby = preferences.contains(key + "_nearby_enabled");
        boolean hasResearch = preferences.contains(key + "_background_research_enabled");
        if (!hasArea && !hasNearby && !hasResearch) return "";
        return "area=" + preferences.getString(key + "_area", "")
                + "|captured_at=" + preferences.getLong(key + "_captured_at", 0L)
                + "|source=" + cleanSource(preferences.getString(
                        key + "_source", CurrentLocationPolicy.SOURCE_UNKNOWN))
                + "|nearby_enabled=" + preferences.getBoolean(key + "_nearby_enabled", false)
                + "|background_research_enabled="
                + preferences.getBoolean(key + "_background_research_enabled", false);
    }

    /**
     * Records placeholder IDs before the transactional people-table merge. If Android stops
     * between the SQLite merge and this preference migration, the next launch can resume it.
     */
    public void rememberPendingOwnerMove(List<String> personIds) {
        Set<String> exactIds = new LinkedHashSet<>();
        if (personIds != null) {
            for (String personId : personIds) {
                String cleanId = clean(personId);
                if (cleanId.matches("[0-9]+")) exactIds.add(cleanId);
            }
        }
        preferences.edit().putString(PENDING_OWNER_MOVE, String.join(",", exactIds)).commit();
    }

    public List<String> pendingOwnerMoveIds() {
        List<String> result = new ArrayList<>();
        String packed = preferences.getString(PENDING_OWNER_MOVE, "");
        if (packed == null || packed.trim().isEmpty()) return result;
        for (String personId : packed.split(",")) {
            String cleanId = clean(personId);
            if (cleanId.matches("[0-9]+") && !result.contains(cleanId)) result.add(cleanId);
        }
        return result;
    }

    public void clearPendingOwnerMove() {
        preferences.edit().remove(PENDING_OWNER_MOVE).commit();
    }

    public boolean markOwnerMoveComplete(String personId) {
        List<String> remaining = pendingOwnerMoveIds();
        remaining.remove(clean(personId));
        return preferences.edit()
                .putString(PENDING_OWNER_MOVE, String.join(",", remaining))
                .commit();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String cleanSource(String source) {
        if (CurrentLocationPolicy.SOURCE_DEVICE_RESOLVED.equals(source)) {
            return CurrentLocationPolicy.SOURCE_DEVICE_RESOLVED;
        }
        if (CurrentLocationPolicy.SOURCE_MANUAL.equals(source)) {
            return CurrentLocationPolicy.SOURCE_MANUAL;
        }
        return CurrentLocationPolicy.SOURCE_UNKNOWN;
    }
}
