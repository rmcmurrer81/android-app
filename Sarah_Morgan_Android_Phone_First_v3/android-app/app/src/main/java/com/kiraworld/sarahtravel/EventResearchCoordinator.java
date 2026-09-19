package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Refreshes monitored events and only notifies for newly stored updates. */
public final class EventResearchCoordinator {
    private EventResearchCoordinator() { }

    public static int refreshDue(
            Context context,
            EventTripStore store,
            String providerId,
            String apiKey,
            String model,
            int limit,
            ConfirmedOwnerLease ownerLease) {
        boolean modelAvailable = SarahModelConfig.fullConversationAvailable()
                || apiKey != null && !apiKey.trim().isEmpty();
        int refreshed = 0;
        if (!leaseStillValid(context, store, ownerLease)) return 0;
        for (Map<String, String> event : store.listDueEventTrips(Math.max(1, limit))) {
            if (!leaseStillValid(context, store, ownerLease)) break;
            boolean officialRefreshed = false;
            KnownEventCatalog.Entry known = KnownEventCatalog.findByEventName(
                    event.getOrDefault("event_name", ""));
            if (known != null) {
                try {
                    if (!leaseStillValid(context, store, ownerLease)) break;
                    OfficialEventPageLookup.Result official = OfficialEventPageLookup.lookup(known);
                    if (official.found && leaseStillValid(context, store, ownerLease)) {
                        long applied = OfficialEventPageLookup.apply(store, known, official, true);
                        if (applied <= 0) continue;
                        refreshed++;
                        officialRefreshed = true;
                    }
                } catch (Exception ignored) {
                    // The connected path may still be able to refresh the event below.
                }
            }

            if (!modelAvailable) {
                // Known official sources can refresh without a model key. Unknown events stay due.
                continue;
            }
            if (officialRefreshed) continue;
            try {
                if (refreshOne(
                        context, store, event, providerId, apiKey, model, ownerLease)) {
                    refreshed++;
                }
            } catch (Exception ignored) {
                // Keep the event due so a later connected run can retry.
            }
        }
        return refreshed;
    }

    private static boolean refreshOne(
            Context context,
            EventTripStore store,
            Map<String, String> event,
            String providerId,
            String apiKey,
            String model,
            ConfirmedOwnerLease ownerLease) throws Exception {
        long eventId = longValue(event, "id", 0);
        String eventName = event.getOrDefault("event_name", "");
        String destination = event.getOrDefault("destination", "");
        String knownOfficialUrl = event.getOrDefault("official_url", "");
        String knownStartDate = event.getOrDefault("start_date", "");
        String knownVenue = event.getOrDefault("venue", "");

        KnownEventCatalog.Entry known = KnownEventCatalog.findByEventName(eventName);
        if (known != null) {
            if (knownOfficialUrl.isEmpty()) knownOfficialUrl = known.officialUrl;
            if (knownVenue.isEmpty()) knownVenue = known.defaultVenue;
            if (destination.isEmpty()) destination = known.destination;
        }

        String systemPrompt = "You maintain Sarah's event-centered trip record. Use current reputable public sources. "
                + "Prefer the official event site for event name, dates, venue, badge or registration announcements, schedule changes, and official policies. "
                + "Use official venue and transit sources for transportation and accessibility when available. "
                + "Use reputable current sources for nearby food and nearby places, but do not claim sponsorship or endorsement. "
                + "Return exactly one JSON object and no markdown. Required string fields: event_name, destination, venue, start_date, end_date, official_url, updates_summary, nearby_food, nearby_places, transport_notes, source_note. "
                + "Dates must use YYYY-MM-DD when verified, otherwise empty strings. "
                + "Also return latest_updates as an array of objects with string fields: update_key, category, title, detail, source_url, published_at. "
                + "An update_key must be stable and specific, such as a normalized official announcement identifier. "
                + "Do not invent schedules, restaurants, event dates, prices, or policies. State uncertainty in source_note. "
                + "Do not access private accounts, booking pages, cookies, or credentials.";
        String message = "Refresh the monitored event trip.\n"
                + "Event: " + eventName + "\n"
                + "Destination: " + destination + "\n"
                + "Previously known official URL: " + knownOfficialUrl + "\n"
                + "Previously known start date: " + knownStartDate + "\n"
                + "Previously known venue: " + knownVenue;

        // Re-check the exact active-person/owner/opt-in lease at the last
        // boundary before any connected request is registered.
        if (!leaseStillValid(context, store, ownerLease)) return false;
        ConnectedModelResponse connected = ConnectedModelGateway.respondDetailed(
                providerId,
                apiKey,
                model,
                systemPrompt,
                Collections.<Map<String, String>>emptyList(),
                message,
                true,
                null);
        if (!connected.hasVerifiedWebReceipt()
                || !leaseStillValid(context, store, ownerLease)) return false;
        JSONObject json = new JSONObject(stripCodeFence(connected.reply));
        String refreshedEventName = value(json, "event_name", eventName);
        String refreshedDestination = value(json, "destination", destination);
        String startDate = value(json, "start_date", knownStartDate);
        long now = System.currentTimeMillis();
        long nextCheck = now + cadenceMillis(startDate);

        String candidateOfficialUrl = value(json, "official_url", knownOfficialUrl);
        if (!candidateOfficialUrl.isEmpty() && !connected.hasSourceUrl(candidateOfficialUrl)) {
            candidateOfficialUrl = knownOfficialUrl;
        }
        if (!leaseStillValid(context, store, ownerLease)
                || !store.updateEventResearch(
                eventId,
                refreshedEventName,
                refreshedDestination,
                value(json, "venue", knownVenue),
                startDate,
                value(json, "end_date", event.getOrDefault("end_date", "")),
                candidateOfficialUrl,
                value(json, "updates_summary", ""),
                value(json, "nearby_food", ""),
                value(json, "nearby_places", ""),
                value(json, "transport_notes", ""),
                connected.sourceReceipt(),
                now,
                nextCheck)) return false;

        JSONArray updates = json.optJSONArray("latest_updates");
        if (updates == null) return true;
        for (int i = 0; i < updates.length(); i++) {
            if (!leaseStillValid(context, store, ownerLease)) return false;
            JSONObject update = updates.optJSONObject(i);
            if (update == null) continue;
            String title = update.optString("title", "").trim();
            String sourceUrl = update.optString("source_url", "").trim();
            String publishedAt = update.optString("published_at", "").trim();
            String key = update.optString("update_key", "").trim();
            if (key.isEmpty()) key = stableKey(title, sourceUrl, publishedAt);
            if (title.isEmpty() || key.isEmpty() || !connected.hasSourceUrl(sourceUrl)) continue;
            if (!leaseStillValid(context, store, ownerLease)) return false;
            boolean added = store.addEventUpdate(
                    eventId,
                    key,
                    update.optString("category", "general").trim(),
                    title,
                    update.optString("detail", "").trim(),
                    sourceUrl,
                    publishedAt);
            if (added) {
                if (!leaseStillValid(context, store, ownerLease)) return false;
                EventNotificationManager.post(
                        context,
                        eventId,
                        refreshedEventName,
                        title,
                        update.optString("detail", "").trim(),
                        sourceUrl);
            }
        }
        return true;
    }

    /** Re-check the person, owner status, opt-in, and thread at every commit boundary. */
    private static boolean leaseStillValid(
            Context context,
            EventTripStore store,
            ConfirmedOwnerLease ownerLease) {
        if (context == null || store == null || ownerLease == null
                || !store.isActiveProfile()) return false;
        try {
            ownerLease.requireActive();
        } catch (IllegalStateException failure) {
            return false;
        }
        SharedPreferences preferences = context.getSharedPreferences(
                SettingsActivity.PREFS,
                Context.MODE_PRIVATE);
        return EventTripMonitoringPolicy.leaseStillValidForProfileKey(
                store.profileKey(),
                ownerLease.personId(),
                true,
                preferences.getBoolean(
                        "deal_alerts_enabled",
                        BackgroundResearchPolicy.DEFAULT_BACKGROUND_MONITORING_ENABLED),
                Thread.currentThread().isInterrupted());
    }

    private static long cadenceMillis(String startDate) {
        long day = 24L * 60L * 60L * 1000L;
        try {
            LocalDate eventDate = LocalDate.parse(startDate);
            long days = ChronoUnit.DAYS.between(LocalDate.now(), eventDate);
            if (days > 120) return 7L * day;
            if (days > 60) return 3L * day;
            if (days > 14) return day;
            if (days >= 0) return 6L * 60L * 60L * 1000L;
            return 7L * day;
        } catch (Exception ignored) {
            return 7L * day;
        }
    }

    private static String value(JSONObject json, String key, String fallback) {
        String value = json.optString(key, fallback);
        return value == null ? fallback : value.trim();
    }

    private static String stableKey(String title, String sourceUrl, String publishedAt) {
        String combined = (title + "|" + sourceUrl + "|" + publishedAt)
                .toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
        return combined.length() > 160 ? combined.substring(0, 160) : combined;
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
        return firstBrace >= 0 && lastBrace > firstBrace
                ? text.substring(firstBrace, lastBrace + 1)
                : text;
    }

    private static long longValue(Map<String, String> row, String key, long fallback) {
        try { return Long.parseLong(row.getOrDefault(key, String.valueOf(fallback))); }
        catch (Exception ignored) { return fallback; }
    }
}
