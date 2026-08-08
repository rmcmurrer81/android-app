package com.kiraworld.sarahtravel;

import android.content.Context;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Uses narrow public sources when internet exists but a full connected model is unavailable. */
public final class PublicOnlineFallback {
    private static final String UNVERIFIED_MARKER = "I could not verify a likely official page yet";

    private PublicOnlineFallback() { }

    public static PublicSourceResult answerResult(
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
                    return PublicSourceResult.verified(
                            OfficialEventPageLookup.conversationalReply(result),
                            result.officialUrl);
                }
            } catch (Exception ignored) { }
            return PublicSourceResult.unavailable(
                    "I recognized " + knownEvent.eventName + " in " + knownEvent.destination
                            + ", but I could not read its official page right now. I will not invent current dates or details. Use the media panel to open its official page, map, public photos, videos, and route options.");
        }

        if (!GenericEventReference.recentEvent(history, message).isEmpty()) {
            PublicSourceResult discovered = PublicEventDiscoveryGateway.answerResult(context, message, history);
            if (discovered != null && !discovered.reply.isEmpty()) return discovered;
            if (SarahModelConfig.fullConversationAvailable()) {
                // Let the connected model use its current-source tools rather than
                // replacing a potentially useful answer with a scripted failure.
                return null;
            }
            String eventName = GenericEventReference.recentEvent(history, message);
            return PublicSourceResult.unavailable(
                    "I recognize “" + eventName
                            + "” as an event rather than a city, but " + UNVERIFIED_MARKER
                            + ". I will not invent its location or dates. Use Explore to open a public event search.");
        }

        String publicKnowledge = PublicKnowledgeGateway.answer(message);
        return publicKnowledge == null || publicKnowledge.trim().isEmpty()
                ? null : PublicSourceResult.unavailable(publicKnowledge.trim());
    }

    public static String answer(
            Context context,
            String message,
            List<Map<String, String>> history) {
        PublicSourceResult result = answerResult(context, message, history);
        return result == null ? null : result.reply;
    }

    public static boolean isUnverifiedEventReply(String reply) {
        return reply != null && reply.contains(UNVERIFIED_MARKER);
    }

    /** Compatibility overload for older callers. */
    public static String answer(Context context, String message) {
        return answer(context, message, Collections.emptyList());
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
        return GenericEventReference.isFollowUp(message)
                || lower.matches(".*\\b(official site|official page|who is appearing)\\b.*");
    }
}
