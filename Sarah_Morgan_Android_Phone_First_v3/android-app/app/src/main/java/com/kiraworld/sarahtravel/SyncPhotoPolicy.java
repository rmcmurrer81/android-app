package com.kiraworld.sarahtravel;

import java.security.MessageDigest;
import java.util.Locale;

/** Bounds sanitized sync derivatives; original media is never serialized. */
public final class SyncPhotoPolicy {
    public static final int MAX_DIMENSION = 960;
    public static final int MAX_DERIVATIVE_BYTES = 1_500_000;
    public static final int MAX_TOTAL_BYTES = 6_000_000;

    private SyncPhotoPolicy() { }

    public static boolean accepted(byte[] derivative, long totalBefore) {
        return derivative != null
                && derivative.length > 0
                && derivative.length <= MAX_DERIVATIVE_BYTES
                && totalBefore >= 0
                && totalBefore + derivative.length <= MAX_TOTAL_BYTES;
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest(bytes == null ? new byte[0] : bytes);
            StringBuilder out = new StringBuilder();
            for (byte b : result) out.append(String.format(Locale.US, "%02x", b));
            return out.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
