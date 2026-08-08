package com.kiraworld.sarahtravel;

import org.json.JSONObject;

/** Normalized result from the team travel backend for any transport method. */
public final class MobilityResult {
    public final boolean configured;
    public final boolean found;
    public final boolean significant;
    public final String recommendedMode;
    public final String summary;
    public final String sourceNote;
    public final String actionUrl;

    private MobilityResult(
            boolean configured,
            boolean found,
            boolean significant,
            String recommendedMode,
            String summary,
            String sourceNote,
            String actionUrl) {
        this.configured = configured;
        this.found = found;
        this.significant = significant;
        this.recommendedMode = value(recommendedMode);
        this.summary = value(summary);
        this.sourceNote = value(sourceNote);
        this.actionUrl = value(actionUrl);
    }

    public static MobilityResult unconfigured() {
        return new MobilityResult(false, false, false, "", "", "Live transport comparison is not connected yet", "");
    }

    public static MobilityResult fromJson(JSONObject json) {
        return new MobilityResult(
                true,
                json.optBoolean("found", false),
                json.optBoolean("significant", false),
                json.optString("recommended_mode", ""),
                json.optString("summary", ""),
                json.optString("source_note", ""),
                json.optString("action_url", ""));
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
