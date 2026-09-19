package com.kiraworld.sarahtravel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/** Stable, opaque scope for destination knowledge belonging to one exact profile. */
public final class KnowledgeProfileKey {
    public static final String OWNER = "owner";

    private KnowledgeProfileKey() { }

    public static String forProfile(Map<String, String> profile) {
        if (profile == null) return "unresolved";
        boolean owner = "yes".equalsIgnoreCase(profile.getOrDefault(
                "active_speaker_is_owner", "no"))
                || "yes".equalsIgnoreCase(profile.getOrDefault("is_owner", "no"));
        if (owner) return OWNER;
        String personId = clean(profile.getOrDefault("person_id", ""));
        String name = clean(profile.getOrDefault(
                "name", profile.getOrDefault("active_speaker", "unresolved")));
        return "person-" + digest(personId.isEmpty() ? name : personId + "|" + name).substring(0, 24);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US).replaceAll("\\s+", " ");
    }

    private static String digest(String value) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] bytes = hash.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b));
            return out.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
