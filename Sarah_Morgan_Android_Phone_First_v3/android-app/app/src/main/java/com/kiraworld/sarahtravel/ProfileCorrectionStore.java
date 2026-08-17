package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Hides accidental unfinished profiles such as a person named "Stressing". */
public final class ProfileCorrectionStore {
    private static final String PREFS = "sarah_profile_corrections";
    private ProfileCorrectionStore() { }
    public static void ignore(Context context, String name) {
        if (context == null || name == null || name.trim().isEmpty()) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("ignored_" + key(name), true).apply();
    }
    public static boolean ignored(Context context, String name) {
        return context != null && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("ignored_" + key(name), false);
    }
    public static List<Map<String,String>> visible(Context context, List<Map<String,String>> profiles) {
        List<Map<String,String>> result = new ArrayList<>();
        for (Map<String,String> profile : profiles) {
            String name = profile.getOrDefault("name", "");
            if (!ignored(context, name)) result.add(profile);
        }
        return result;
    }
    private static String key(String value) { return value.toLowerCase().replaceAll("[^a-z0-9]", "_"); }
}
