package com.kiraworld.sarahtravel;

import java.nio.charset.StandardCharsets;

/** Pure bounds applied before externally supplied booking text is stored or scheduled. */
public final class BookingImportTextPolicy {
    public static final int MAX_CHARS = 16_384;
    public static final int MAX_UTF8_BYTES = 32_768;

    private BookingImportTextPolicy() { }

    public static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public static int utf8Bytes(String value) {
        return clean(value).getBytes(StandardCharsets.UTF_8).length;
    }

    public static boolean accepted(String value) {
        String clean = clean(value);
        return !clean.isEmpty()
                && clean.length() <= MAX_CHARS
                && clean.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }
}
