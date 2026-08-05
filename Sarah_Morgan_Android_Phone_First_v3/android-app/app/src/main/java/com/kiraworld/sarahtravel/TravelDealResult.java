package com.kiraworld.sarahtravel;

import org.json.JSONObject;

public final class TravelDealResult {
    public final boolean configured;
    public final boolean found;
    public final boolean isDeal;
    public final double totalPrice;
    public final String currency;
    public final String originAirport;
    public final String destinationAirport;
    public final String departDate;
    public final String returnDate;
    public final String bookingUrl;
    public final String weatherNote;
    public final String weatherBasis;
    public final String providerNote;

    private TravelDealResult(
            boolean configured,
            boolean found,
            boolean isDeal,
            double totalPrice,
            String currency,
            String originAirport,
            String destinationAirport,
            String departDate,
            String returnDate,
            String bookingUrl,
            String weatherNote,
            String weatherBasis,
            String providerNote) {
        this.configured = configured;
        this.found = found;
        this.isDeal = isDeal;
        this.totalPrice = totalPrice;
        this.currency = value(currency, "USD");
        this.originAirport = value(originAirport, "");
        this.destinationAirport = value(destinationAirport, "");
        this.departDate = value(departDate, "");
        this.returnDate = value(returnDate, "");
        this.bookingUrl = value(bookingUrl, "");
        this.weatherNote = value(weatherNote, "");
        this.weatherBasis = value(weatherBasis, "");
        this.providerNote = value(providerNote, "");
    }

    public static TravelDealResult unconfigured() {
        return new TravelDealResult(false, false, false, 0, "USD", "", "", "", "", "", "", "", "No fare backend configured");
    }

    public static TravelDealResult fromJson(JSONObject json) {
        return new TravelDealResult(
                true,
                json.optBoolean("found", false),
                json.optBoolean("is_deal", false),
                json.optDouble("total_price", 0),
                json.optString("currency", "USD"),
                json.optString("origin_airport", ""),
                json.optString("destination_airport", ""),
                json.optString("depart_date", ""),
                json.optString("return_date", ""),
                json.optString("booking_url", ""),
                json.optString("weather_note", ""),
                json.optString("weather_basis", ""),
                json.optString("provider_note", ""));
    }

    public String notificationTitle(String destination) {
        if (totalPrice <= 0) return "Sarah found a possible " + destination + " deal";
        return destination + " fare: " + currency + " " + Math.round(totalPrice);
    }

    public String notificationText() {
        StringBuilder text = new StringBuilder();
        if (!departDate.isEmpty()) text.append("Leave ").append(departDate);
        if (!returnDate.isEmpty()) {
            if (text.length() > 0) text.append(", ");
            text.append("return ").append(returnDate);
        }
        if (!originAirport.isEmpty() || !destinationAirport.isEmpty()) {
            if (text.length() > 0) text.append(". ");
            text.append(originAirport).append(" → ").append(destinationAirport);
        }
        if (!weatherNote.isEmpty()) {
            if (text.length() > 0) text.append(". ");
            if ("forecast".equalsIgnoreCase(weatherBasis)) text.append("Forecast: ");
            else if ("climate".equalsIgnoreCase(weatherBasis)) text.append("Typical conditions: ");
            else text.append("Weather context: ");
            text.append(weatherNote);
        }
        return text.length() == 0 ? "Open Sarah for the fare details." : text.toString();
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
