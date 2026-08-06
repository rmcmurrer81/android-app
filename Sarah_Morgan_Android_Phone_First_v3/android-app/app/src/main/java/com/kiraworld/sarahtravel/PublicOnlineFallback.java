package com.kiraworld.sarahtravel;

import android.content.Context;

/** Uses narrow public sources when internet exists but a full connected model is unavailable. */
public final class PublicOnlineFallback {
    private PublicOnlineFallback() { }

    public static String answer(Context context, String message) {
        KnownEventCatalog.Entry knownEvent = KnownEventCatalog.find(message);
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
                    + ", but I could not read its official page right now. I saved the official source and will retry. Use Explore to open its map, public photos, videos, or official web search.";
        }

        String publicKnowledge = PublicKnowledgeGateway.answer(message);
        return publicKnowledge == null || publicKnowledge.trim().isEmpty() ? null : publicKnowledge.trim();
    }
}
