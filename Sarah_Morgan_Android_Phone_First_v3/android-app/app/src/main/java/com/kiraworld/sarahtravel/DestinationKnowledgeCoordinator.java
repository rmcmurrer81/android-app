package com.kiraworld.sarahtravel;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Builds source-aware destination packs through the configured connected model. */
public final class DestinationKnowledgeCoordinator {
    private static final long PACK_LIFETIME_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private DestinationKnowledgeCoordinator() { }

    /** Returns the number of packs successfully refreshed. */
    public static int refreshPending(
            SarahDatabase db,
            String providerId,
            String apiKey,
            String model,
            int limit) throws Exception {
        return refreshPending(
                db, KnowledgeProfileKey.OWNER,
                providerId, apiKey, model, limit);
    }

    public static int refreshPending(
            SarahDatabase db,
            String personKey,
            String providerId,
            String apiKey,
            String model,
            int limit) throws Exception {
        if (!SarahModelConfig.fullConversationAvailable()
                && (apiKey == null || apiKey.trim().isEmpty())) return 0;
        if (!RUNNING.compareAndSet(false, true)) return 0;
        try {
            int refreshed = 0;
            Map<String, String> profile = db.getProfile();
            List<Map<String, String>> memories = db.listMemories(100);
            for (String destination : db.listPendingKnowledgeRequests(
                    personKey, Math.max(1, limit))) {
                long startedAt = System.currentTimeMillis();
                int sourceCount = 0;
                String sourceReceipt = "";
                db.recordKnowledgeAttempt(
                        personKey, destination, SarahDatabase.KNOWLEDGE_RUNNING,
                        providerId, 0, "", "", startedAt, 0);
                try {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Destination refresh cancelled");
                    }
                long sourceTime = System.currentTimeMillis();
                List<TavilyClient.Result> sources = TavilyClient.search(
                        destination + " official visitor transport accessibility museums local culture",
                        BackgroundResearchPolicy.MAX_DISCOVERIES_PER_QUERY);
                List<String> sourceUrls = new ArrayList<>();
                StringBuilder sourceMaterial = new StringBuilder();
                for (TavilyClient.Result source : sources) {
                    if (source == null || !source.url.startsWith("https://")) continue;
                    sourceUrls.add(source.url);
                    sourceMaterial.append("\nSOURCE URL: ").append(source.url)
                            .append("\nTITLE: ").append(bounded(source.title, 240))
                            .append("\nEXCERPT: ").append(bounded(source.summary, 1600)).append('\n');
                }
                sourceCount = sourceUrls.size();
                if (!DestinationSourcePolicy.canPersistReadyPack(sourceUrls, sourceTime)) {
                    throw new IllegalStateException("No verified HTTPS destination sources were returned");
                }
                sourceReceipt = DestinationSourcePolicy.receipt(sourceUrls, sourceTime);
                String ageGroup = profile.getOrDefault("age_group", "unknown_use_child_safe_mode");
                String interests = interestContext(profile, memories);
                String focus = focusFor(memories, destination);
                String systemPrompt = "You are Sarah's destination research formatter. "
                        + "Use only the exact public-source excerpts attached to this request. "
                        + "Return exactly one JSON object and no markdown. Required string fields: "
                        + "destination, overview, recommendations, transport, accessibility, seasonal, events, source_note. "
                        + "Set events to an empty string; event claims require separate item-level source receipts. "
                        + "Tailor recommendations to the supplied age group, interests, and trip focus without stereotyping. "
                        + "Do not invent or include current events, prices, opening hours, visa rules, live transit status, or forecasts. "
                        + "For a country, describe useful gateway regions and explain that city-level planning may come later. "
                        + "In source_note, name the kinds of current sources used and state the research date. "
                        + "Keep each field concise enough for a phone app.";
                String message = "Build or refresh Sarah's travel knowledge pack.\n"
                        + "Destination: " + destination + "\n"
                        + "Traveler age group: " + ageGroup + "\n"
                        + "Traveler interests: " + (interests.isEmpty() ? "not yet specified" : interests) + "\n"
                        + "Saved trip focus: " + (focus.isEmpty() ? "none" : focus) + "\n"
                        + "Source capture time (Unix ms): " + sourceTime + "\n"
                        + sourceMaterial;
                ConnectedModelResponse structured = ConnectedModelGateway.respondDetailed(
                        providerId,
                        apiKey,
                        model,
                        systemPrompt,
                        Collections.<Map<String, String>>emptyList(),
                        message,
                        false,
                        null);
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Destination refresh cancelled");
                }
                JSONObject json = new JSONObject(stripCodeFence(structured.reply));
                long now = System.currentTimeMillis();
                db.upsertKnowledgePack(
                        personKey,
                        value(json, "destination", destination),
                        value(json, "overview", ""),
                        value(json, "recommendations", ""),
                        value(json, "transport", ""),
                        value(json, "accessibility", ""),
                        value(json, "seasonal", ""),
                        "",
                        sourceReceipt,
                        now,
                        now + PACK_LIFETIME_MS);
                db.recordKnowledgeAttempt(
                        personKey, destination, "SUCCEEDED",
                        structured.provider, sourceCount, sourceReceipt, "",
                        startedAt, now);
                refreshed++;
                } catch (Exception failure) {
                    try {
                        db.recordKnowledgeAttempt(
                                personKey, destination, SarahDatabase.KNOWLEDGE_FAILED,
                                providerId, sourceCount, sourceReceipt,
                                failure.getClass().getName(),
                                startedAt, System.currentTimeMillis());
                    } catch (Exception receiptFailure) {
                        failure.addSuppressed(receiptFailure);
                    }
                    throw failure;
                }
            }
            return refreshed;
        } finally {
            RUNNING.set(false);
        }
    }

    private static String interestContext(
            Map<String, String> profile,
            List<Map<String, String>> memories) {
        String initial = ProfileLearningContext.interests(profile);
        StringBuilder learned = new StringBuilder();
        for (Map<String, String> memory : memories) {
            String category = memory.getOrDefault("category", "");
            if (!"interest".equalsIgnoreCase(category)
                    && !"profile_interest".equalsIgnoreCase(category)) continue;
            String summary = memory.getOrDefault("summary", "").trim();
            if (summary.isEmpty()) continue;
            if (learned.length() > 0) learned.append("; ");
            learned.append(summary);
        }
        if (initial.isEmpty()) return learned.toString();
        return learned.length() == 0 ? initial : initial + "; " + learned;
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

    private static String bounded(String value, int limit) {
        String clean = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return clean.length() <= limit ? clean : clean.substring(0, limit);
    }
}
