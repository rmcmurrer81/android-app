package com.kiraworld.sarahtravel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;

/** Pure rules used by the Android profile migration and its JVM tests. */
public final class ProfileMigrationPolicy {
    private ProfileMigrationPolicy() { }

    public static boolean isPlaceholderName(String value) {
        String normalized = clean(value).toLowerCase(Locale.US);
        return normalized.isEmpty()
                || normalized.equals("phone owner")
                || normalized.equals("phoneowner")
                || normalized.equals("the phone owner")
                || normalized.equals("traveler");
    }

    public static boolean shouldMergePlaceholder(String oldName, String confirmedName) {
        String confirmed = clean(confirmedName);
        return isPlaceholderName(oldName)
                && !isPlaceholderName(confirmed)
                && !clean(oldName).equalsIgnoreCase(confirmed);
    }

    public static boolean isConfirmedDisplayName(String value) {
        String clean = clean(value);
        return !isPlaceholderName(clean)
                && clean.matches("[\\p{L}][\\p{L}\\p{M}'’ -]{0,79}");
    }

    public static boolean ownerAgeKnown(Map<String, String> ownerProfile) {
        if (ownerProfile == null) return false;
        String explicit = clean(ownerProfile.get("age_known"));
        int age = parseInt(ownerProfile.get("age"));
        if (!explicit.isEmpty()) {
            boolean markedKnown = explicit.equalsIgnoreCase("yes")
                    || explicit.equals("1")
                    || explicit.equalsIgnoreCase("true");
            return markedKnown && age >= 1 && age <= 120;
        }
        String name = ownerProfile.get("name");
        return age >= 1 && age <= 120 && !(age == 18 && isPlaceholderName(name));
    }

    public static int ownerAge(Map<String, String> ownerProfile) {
        if (!ownerAgeKnown(ownerProfile)) return 0;
        return parseInt(ownerProfile.get("age"));
    }

    /** Stable ID for one exact source/target collision; contains no raw profile data. */
    public static String collisionRecordId(
            String store,
            String oldPersonId,
            String newPersonId,
            String sourcePayload,
            String targetPayload) {
        return sha256(
                clean(store) + "\n"
                        + clean(oldPersonId) + "\n"
                        + clean(newPersonId) + "\n"
                        + sha256(sourcePayload) + "\n"
                        + sha256(targetPayload));
    }

    /** Deterministic replacement when two migrated list records reuse one ID. */
    public static String migratedRecordId(String store, String payload) {
        return "migrated-" + sha256(clean(store) + "\n" + raw(payload)).substring(0, 20);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) out.append(String.format(Locale.US, "%02x", item & 0xff));
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(clean(value)); }
        catch (Exception ignored) { return 0; }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String raw(String value) {
        return value == null ? "" : value;
    }
}
