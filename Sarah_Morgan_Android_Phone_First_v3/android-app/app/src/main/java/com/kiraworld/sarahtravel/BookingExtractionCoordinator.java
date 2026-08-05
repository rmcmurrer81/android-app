package com.kiraworld.sarahtravel;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Extracts visible booking details without treating model output as confirmed truth. */
public final class BookingExtractionCoordinator {
    private BookingExtractionCoordinator() { }

    public static int refreshPending(
            EventTripStore store,
            String providerId,
            String apiKey,
            String model,
            int limit) {
        int processed = 0;
        for (Map<String, String> booking : store.listPendingBookings(Math.max(1, limit))) {
            try {
                String sourceKind = booking.getOrDefault("source_kind", "");
                long id = longValue(booking, "id", 0);
                if ("link".equals(sourceKind)) {
                    store.updateBookingExtraction(
                            id,
                            booking.getOrDefault("booking_type", "travel"),
                            booking.getOrDefault("provider", "Other"),
                            "Booking link saved. Private itinerary details may be hidden behind a login; share a screenshot or visible confirmation text for extraction.",
                            "", "", "", "", 0, "USD",
                            "link_saved_needs_screenshot");
                    processed++;
                    continue;
                }
                if (!"screenshot".equals(sourceKind) || apiKey == null || apiKey.trim().isEmpty()) continue;
                File file = new File(booking.getOrDefault("local_path", ""));
                if (!file.isFile()) continue;
                byte[] image = Files.readAllBytes(file.toPath());
                String systemPrompt = "You extract visible travel-booking details from a user-selected screenshot. "
                        + "Return exactly one JSON object and no markdown with string fields booking_type, provider, summary, confirmation_code, start_date, end_date, address, currency, and numeric total. "
                        + "Use YYYY-MM-DD only when the date is clearly visible. Do not infer hidden fields. Do not access accounts, links, cookies, or credentials. "
                        + "The output is a candidate for user review, not a verified booking. If a field is unclear, use an empty string or zero.";
                String raw = ConnectedModelGateway.respond(
                        providerId,
                        apiKey,
                        model,
                        systemPrompt,
                        List.<Map<String, String>>of(),
                        "Extract the visible booking details from this screenshot.",
                        false,
                        image);
                JSONObject json = new JSONObject(stripCodeFence(raw));
                store.updateBookingExtraction(
                        id,
                        json.optString("booking_type", booking.getOrDefault("booking_type", "travel")),
                        json.optString("provider", booking.getOrDefault("provider", "Unknown")),
                        json.optString("summary", "Visible booking details extracted for review"),
                        json.optString("confirmation_code", ""),
                        json.optString("start_date", ""),
                        json.optString("end_date", ""),
                        json.optString("address", ""),
                        json.optDouble("total", 0),
                        json.optString("currency", "USD"),
                        "needs_confirmation");
                processed++;
            } catch (Exception ignored) {
                // Leave the import pending so it can retry later.
            }
        }
        return processed;
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
