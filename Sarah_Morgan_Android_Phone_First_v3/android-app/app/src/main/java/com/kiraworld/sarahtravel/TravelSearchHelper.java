package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TravelSearchHelper {
    private static final Pattern DESTINATION = Pattern.compile(
            "(?i)\\b(?:trip|flight|flights|travel|go|going|visit|visiting|fly|flying)\\s+(?:to\\s+)?([A-Z][A-Za-z .'-]{2,50})");

    private TravelSearchHelper() { }

    public static boolean shouldOffer(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.US);
        return lower.contains("deal")
                || lower.contains("cheap flight")
                || lower.contains("cheap ticket")
                || lower.contains("flight price")
                || lower.contains("airfare")
                || lower.contains("fare")
                || lower.contains("discount flight")
                || lower.contains("track price");
    }

    public static void show(Activity activity, String message, Map<String, String> profile) {
        String origin = profile.getOrDefault("hometown", "").trim();
        String destination = destinationFrom(message);
        String route = destination.isEmpty()
                ? (origin.isEmpty() ? message : "Flights from " + origin + ". " + message)
                : (origin.isEmpty() ? "Flights to " + destination : "Flights from " + origin + " to " + destination);

        String[] choices = {
                "Google Flights",
                "Google Flight Deals",
                "KAYAK",
                "Skyscanner"
        };

        new AlertDialog.Builder(activity)
                .setTitle("Search live fares")
                .setMessage("Sarah can open live travel sites, but prices change quickly. Compare baggage, seats, taxes, cancellation rules, airports, and the airline’s own checkout price before buying.")
                .setItems(choices, (dialog, which) -> {
                    String url;
                    if (which == 0) {
                        url = "https://www.google.com/travel/flights?q=" + Uri.encode(route);
                    } else if (which == 1) {
                        url = "https://www.google.com/travel/flights/deals";
                    } else if (which == 2) {
                        url = "https://www.kayak.com/flights";
                    } else {
                        url = "https://www.skyscanner.com/transport/flights/";
                    }
                    open(activity, url);
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private static void open(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(activity, "No browser could open that travel site.", Toast.LENGTH_LONG).show();
        }
    }

    private static String destinationFrom(String message) {
        if (message == null) return "";
        Matcher matcher = DESTINATION.matcher(message.trim());
        if (!matcher.find()) return "";
        String value = matcher.group(1).trim();
        value = value.replaceAll("(?i)\\b(?:for|from|during|next|this|with|and)\\b.*$", "").trim();
        value = value.replaceAll("[?.!,]+$", "").trim();
        return value;
    }
}
