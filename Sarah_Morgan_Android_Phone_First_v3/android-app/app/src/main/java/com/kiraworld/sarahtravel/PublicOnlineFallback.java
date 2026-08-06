package com.kiraworld.sarahtravel;

import android.content.Context;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Uses narrow public sources when internet exists but a full connected model is unavailable. */
public final class PublicOnlineFallback {
    private PublicOnlineFallback() { }

    public static String answer(
            Context context,
            String message,
            List<Map<String, String>> history) {
        if (context == null) return null;
        if (SettingsActivity.getConversationMode(context) == ConversationModePolicy.MODE_LOCAL_ONLY) return null;

        KnownEventCatalog.Entry knownEvent = KnownEventCatalog.find(message);
        if (knownEvent == null && isEventFollowUp(message)) {
            knownEvent = recentKnownEvent(history, message);
        }
        if (knownEvent != null) {
            try {
                OfficialEventPageLookup.Result result = OfficialEventPageLookup.lookup(knownEvent);
                if (result.found) {
                    EventTripStore store = new EventTripStore(context.getApplicationContext());
                    try {
                        OfficialEventPageLookup.apply(store, knownEvent, result);
                    } finally {
                        store.close();
                    }
                    return OfficialEventPageLookup.conversationalReply(result);
                }
            } catch (Exception ignored) { }
            return "I recognized " + knownEvent.eventName + " in " + knownEvent.destination
                    + ", but I could not read its official page right now. I saved the official source and will retry. Use the media panel for its map, public photos, videos, official page, and route options.";
        }

        String publicKnowledge = PublicKnowledgeGateway.answer(message);
        return publicKnowledge == null || publicKnowledge.trim().isEmpty() ? null : publicKnowledge.trim();
    }

    /** Compatibility overload for older callers. */
    public static String answer(Context context, String message) {
        return answer(context, message, List.of());
    }

    private static KnownEventCatalog.Entry recentKnownEvent(
            List<Map<String, String>> history,
            String currentMessage) {
        if (history == null || history.isEmpty()) return null;
        int inspected = 0;
        for (int i = history.size() - 1; i >= 0 && inspected < 16; i--, inspected++) {
            Map<String, String> row = history.get(i);
            String content = row.getOrDefault("content", "").trim();
            if (content.isEmpty() || content.equals(currentMessage)) continue;
            KnownEventCatalog.Entry entry = KnownEventCatalog.find(content);
            if (entry != null) return entry;
        }
        return null;
    }

    private static boolean isEventFollowUp(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.US).trim();
        return lower.matches(".*\\b(when|what date|which date|dates|where|venue|address|hours|tickets|ticket price|how much|official site|official page|who is appearing|guests)\\b.*")
                || lower.matches("^(when is it|where is it|what about tickets|what are the dates)[?.! ]*$");
    }
}
