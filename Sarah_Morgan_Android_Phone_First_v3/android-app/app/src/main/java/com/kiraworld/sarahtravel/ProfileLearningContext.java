package com.kiraworld.sarahtravel;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds exact-profile learning context without borrowing another person's memories. */
public final class ProfileLearningContext {
    private ProfileLearningContext() { }

    public static String interests(Map<String, String> profile) {
        if (profile == null || !"yes".equals(profile.getOrDefault("memory_consent", "no"))) {
            return "";
        }
        Set<String> parts = new LinkedHashSet<>();
        add(parts, profile.getOrDefault("interests", ""));
        add(parts, profile.getOrDefault("learned_interests", ""));
        return String.join("; ", parts);
    }

    private static void add(Set<String> parts, String packed) {
        if (packed == null) return;
        for (String value : packed.split("[;\\n]+")) {
            String clean = value.trim().replaceAll("\\s+", " ");
            if (clean.isEmpty()) continue;
            boolean duplicate = false;
            for (String existing : parts) {
                if (existing.toLowerCase(Locale.US).equals(clean.toLowerCase(Locale.US))) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) parts.add(clean);
        }
    }
}
