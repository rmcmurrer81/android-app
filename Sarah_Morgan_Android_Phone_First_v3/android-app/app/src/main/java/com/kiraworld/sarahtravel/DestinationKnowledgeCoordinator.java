package com.kiraworld.sarahtravel;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds source-aware destination packs through the configured connected model. */
public final class DestinationKnowledgeCoordinator {
    private static final long PACK_LIFETIME_MS = 7L * 24L * 60L * 60L * 1000L;

    private DestinationKnowledgeCoordinator() { }

    /** Returns the number of packs successfully refreshed. */
    public static int refreshPending(
            SarahDatabase db,
            String providerId,
            String apiKey,
            String model,
            int limit) {
        if (apiKey == null || apiKey.trim().isEmpty()) return 0;
        int refreshed = 0;
        Map<String, String> profile = db.getProfile();
        List<Map<String, String>> memories = db.listMemories(100);
        for (String destination : db.listPendingKnowledgeRequests(Math.max(1, limit))) {
            try {
                String ageGroup = profile.getOrDefault("age_group", "adult");
                String interests = profile.getOrDefault("interests", "").trim();
                String focus = focusFor(memories, destination);
                String systemPrompt = "You are Sarah's destination research formatter. "
                        + "Use current reputable public sources when web research is available. "
                        + "Return exactly one JSON object and no markdown. Required string fields: "
                        + "destination, overview, recommendations, transport, accessibility, seasonal, events, source_note. "
                        + "Separate stable background from current events. Include event dates only when verified. "
                        + "Tailor recommendations to the supplied age group, interests, and trip focus without stereotyping. "
                        + "Do not invent current events, prices, opening hours, visa rules, transit status, or weather forecasts. "
                        + "For a country, describe useful gateway regions and explain that city-level planning may come later. "
                        + "In source_note, name the kinds of current sources used and state the research date. "
                        + "Keep each field concise enough for a phone app.";
                String message = "Build or refresh Sarah's travel knowledge pack.\n"
                        + "Destination: " + destination + "\n"
                        + "Traveler age group: " + ageGroup + "\n"
                        + "Traveler interests: " + (interests.isEmpty() ? "not yet specified" : interests) + "\n"
                        + "Saved trip focus: " + (focus.isEmpty() ? "none" : focus);
                String raw = ConnectedModelGateway.respond(
                        providerId,
                        apiKey,
                        model,
                        systemPrompt,
                        List.<Map<String, String>>of(),
                        message,
                        true,
                        null);
                JSONObject json = new JSONObject(stripCodeFence(raw));
                long now = System.currentTimeMillis();
                db.upsertKnowledgePack(
                        value(json, "destination", destination),
                        value(json, "overview", ""),
                        value(json, "recommendations", ""),
                        value(json, "transport", ""),
                        value(json, "accessibility", ""),
                        value(json, "seasonal", ""),
                        value(json, "events", ""),
                        value(json, "source_note", "Connected research; verify before booking"),
                        now,
                        now + PACK_LIFETIME_MS);
                refreshed++;
            } catch (Exception ignored) {
                // Keep the request pending. Automatic mode can try again later.
            }
        }
        return refreshed;
    }

    private static String focusFor(List<Map<String, String>> memories, String destination) {
        String target = destination == null ? "" : destination.toLowerCase(Locale.US);
        for (Map<String, String> memory : memories) {
            String category = memory.getOrDefault("category", "");
            String summary = memory.getOrDefault("summary", "");
            String lower = summary.toLowerCase(Locale.US);
            if (!"trip_focus".equals(category)) continue;
            if (target.isEmpty() || lower.contains(target) || target.equals("orlando") && lower.contains("universal")) {
                return summary;
            }
        }
        return "";
    }

    private static String value(JSONObject json, String key, String fallback) {
        String value = json.optString(key, fallback);
        return value == null ? fallback : value.trim();
    }

    private static String stripCodeFence(String value) {
        if (value == null) return "{}";
        String text = value.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) return text.substring(firstBrace, lastBrace + 1);
        return text;
    }
}
