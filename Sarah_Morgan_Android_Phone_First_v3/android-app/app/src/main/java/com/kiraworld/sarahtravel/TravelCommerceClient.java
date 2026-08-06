package com.kiraworld.sarahtravel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Provider-neutral client for approved hotel, transport, experience and road
 * trip inventory. Provider credentials remain on the team backend.
 */
public final class TravelCommerceClient {
    public static final class Offer {
        public final String provider;
        public final String title;
        public final double totalPrice;
        public final String currency;
        public final String bookingUrl;
        public final String cancellation;
        public final String details;
        public final String sourceTime;

        Offer(
                String provider,
                String title,
                double totalPrice,
                String currency,
                String bookingUrl,
                String cancellation,
                String details,
                String sourceTime) {
            this.provider = clean(provider);
            this.title = clean(title);
            this.totalPrice = Math.max(0, totalPrice);
            this.currency = clean(currency).isEmpty() ? "USD" : clean(currency);
            this.bookingUrl = clean(bookingUrl);
            this.cancellation = clean(cancellation);
            this.details = clean(details);
            this.sourceTime = clean(sourceTime);
        }
    }

    private TravelCommerceClient() { }

    public static List<Offer> searchHotels(
            TravelContextSnapshot trip,
            HotelSearchState search,
            String loyaltySummary,
            String needsSummary) throws Exception {
        JSONObject request = new JSONObject();
        request.put("request_type", "hotel_search");
        request.put("person_id", trip.personId);
        request.put("destination", search.destination);
        request.put("check_in", search.checkIn);
        request.put("check_out", search.checkOut);
        request.put("adults", search.adults);
        request.put("rooms", search.rooms);
        request.put("currency", "USD");
        request.put("loyalty_summary", clean(loyaltySummary));
        request.put("traveler_needs", clean(needsSummary));
        return execute(request);
    }

    private static List<Offer> execute(JSONObject request) throws Exception {
        if (!TravelCommerceConfig.isConfigured()) {
            throw new IllegalStateException("Travel commerce backend is not configured.");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(
                TravelCommerceConfig.endpoint()).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(90000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "SarahTravelOS/2.0");
        String token = TravelCommerceConfig.token();
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);

        try (OutputStream out = connection.getOutputStream()) {
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String response = read(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Travel backend returned " + status);
        }

        JSONObject root = new JSONObject(response);
        JSONArray offers = root.optJSONArray("offers");
        List<Offer> result = new ArrayList<>();
        if (offers == null) return result;
        for (int i = 0; i < offers.length(); i++) {
            JSONObject item = offers.optJSONObject(i);
            if (item == null) continue;
            String title = item.optString("title", "").trim();
            String bookingUrl = item.optString("booking_url", "").trim();
            if (title.isEmpty()) continue;
            result.add(new Offer(
                    item.optString("provider", "Unknown"),
                    title,
                    item.optDouble("total_price", 0),
                    item.optString("currency", "USD"),
                    bookingUrl,
                    item.optString("cancellation", ""),
                    item.optString("details", ""),
                    item.optString("source_time", "")));
            if (result.size() >= 30) break;
        }
        return result;
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                out.append(buffer, 0, count);
                if (out.length() > 5_000_000) break;
            }
        }
        return out.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
