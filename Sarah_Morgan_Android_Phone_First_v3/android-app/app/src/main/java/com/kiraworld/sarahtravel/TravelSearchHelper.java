package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Opens Sarah's map, photo, video, route, and live-travel tools. */
public final class TravelSearchHelper {
    private TravelSearchHelper() { }

    public static boolean shouldOffer(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.US);
        return containsAny(lower,
                "show me a map", "show map", "show photos", "show pictures", "show videos",
                "open live search", "open the live search", "show me the fares", "show fares",
                "search now", "cross country", "cross-country", "amtrak", "train trip",
                "metro to", "subway to", "going to", "planning to go", "thinking about going",
                "want to visit", "always wanted to visit", "comic con", "comic-con", "ces", "nycc");
    }

    public static void show(Activity activity, String message, Map<String, String> profile) {
        EventTripIntentParser.EventIntent event = EventTripIntentParser.parse(message);
        JourneyIntentParser.JourneyIntent journey = JourneyIntentParser.parse(message, profile, List.of());
        List<String> places = DestinationParser.extractDestinations(message);

        String destination = event.found() ? event.destination
                : journey.found() ? journey.destination
                : places.isEmpty() ? "" : places.get(places.size() - 1);
        String query = event.found() ? event.eventName + " " + event.destination
                : destination.isEmpty() ? message : destination;
        String origin = journey.found() ? journey.origin : profile.getOrDefault("hometown", "");
        String mode = journey.found() && !journey.modes.isEmpty() ? journey.modes.get(0) : "";

        String[] choices = {
                "Map",
                "Photos",
                "Videos",
                "Route and local transit",
                "Live travel options"
        };

        new AlertDialog.Builder(activity)
                .setTitle("Explore this trip")
                .setMessage("Sarah can show public maps, public photo searches, travel videos, and a route view. Current schedules, fares, closures, and service alerts still require live official sources.")
                .setItems(choices, (dialog, which) -> {
                    if (which == 4) {
                        showLiveOptions(activity, origin, destination, mode);
                        return;
                    }
                    String kind = which == 0 ? "map" : which == 1 ? "photos" : which == 2 ? "videos" : "route";
                    Intent intent = new Intent(activity, TravelExplorerActivity.class);
                    intent.putExtra(TravelExplorerActivity.EXTRA_KIND, kind);
                    intent.putExtra(TravelExplorerActivity.EXTRA_QUERY, query);
                    intent.putExtra(TravelExplorerActivity.EXTRA_ORIGIN, origin);
                    intent.putExtra(TravelExplorerActivity.EXTRA_DESTINATION, destination);
                    intent.putExtra(TravelExplorerActivity.EXTRA_MODE, mode);
                    activity.startActivity(intent);
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    private static void showLiveOptions(Activity activity, String origin, String destination, String mode) {
        String route = (origin == null || origin.isEmpty() ? "" : origin + " to ")
                + (destination == null || destination.isEmpty() ? "destination" : destination);
        String[] choices = {
                "Current route in Google Maps",
                "Amtrak official site",
                "Google Flights",
                "Search current bus and rail options"
        };
        new AlertDialog.Builder(activity)
                .setTitle("Current travel sources")
                .setMessage("These services are external sources. Verify the final schedule, price, baggage, accessibility, and checkout details before relying on them.")
                .setItems(choices, (dialog, which) -> {
                    String url;
                    if (which == 0) {
                        String travelMode = JourneyIntentParser.DRIVE.equals(mode) ? "driving" : "transit";
                        url = "https://www.google.com/maps/dir/?api=1&origin=" + Uri.encode(origin)
                                + "&destination=" + Uri.encode(destination) + "&travelmode=" + travelMode;
                    } else if (which == 1) {
                        url = "https://www.amtrak.com/home.html";
                    } else if (which == 2) {
                        url = "https://www.google.com/travel/flights?q=" + Uri.encode(route);
                    } else {
                        url = "https://www.google.com/search?q=" + Uri.encode(route + " current train bus options");
                    }
                    open(activity, url);
                })
                .setNegativeButton("Back", null)
                .show();
    }

    private static void open(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(activity, "No browser could open that travel source.", Toast.LENGTH_LONG).show();
        }
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }
}
