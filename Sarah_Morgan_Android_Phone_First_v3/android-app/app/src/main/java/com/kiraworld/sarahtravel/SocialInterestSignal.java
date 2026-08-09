package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One user-authorized social-media signal used to learn travel-relevant interests.
 *
 * A provider may obtain this from a supported platform API, a user data export,
 * or another explicitly authorized source. Sarah does not store passwords or
 * require a provider to impersonate the Instagram application UI.
 */
public final class SocialInterestSignal {
    public enum Action {
        LIKE, SAVE, FOLLOW, COMMENT, SHARE, VIEW, POST, EXPLICIT
    }

    public final String source;
    public final Action action;
    public final List<String> topics;
    public final String sourceReference;
    public final long observedAtMs;

    public SocialInterestSignal(
            String source,
            Action action,
            List<String> topics,
            String sourceReference,
            long observedAtMs) {
        this.source = clean(source);
        this.action = action == null ? Action.VIEW : action;
        List<String> cleaned = new ArrayList<>();
        if (topics != null) {
            for (String topic : topics) {
                String value = clean(topic);
                if (!value.isEmpty() && !containsIgnoreCase(cleaned, value)) cleaned.add(value);
            }
        }
        this.topics = Collections.unmodifiableList(cleaned);
        this.sourceReference = clean(sourceReference);
        this.observedAtMs = Math.max(0L, observedAtMs);
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        for (String existing : values) {
            if (existing.toLowerCase(Locale.US).equals(value.toLowerCase(Locale.US))) return true;
        }
        return false;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }
}
