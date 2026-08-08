package com.kiraworld.sarahtravel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Opens Sarah's map, photo, video, route, official-source, and live-travel tools. */
public final class TravelSearchHelper {
    private TravelSearchHelper() { }

    public static boolean shouldOffer(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.US);
        return GenericEventReference.looksLikeEvent(message) || containsAny(lower,
                "show me a map", "show map", "show photos", "show pictures", "show videos",
                "open live search", "open the live search", "show me the fares", "show fares",
                "search now", "monitor", "watch for", "deals to", "travel options",
                "cross country", "cross-country", "amtrak", "train trip", "rail trip",
                "metro to", "subway to", "transit to", "bus to", "drive to", "ferry to",
                "going to", "planning to go", "thinking about going", "want to visit",
                "always wanted to visit", "comic con", "comic-con", "ces", "nycc",
                "where did they film", "where was it filmed", "filming location", "filming locations");
    }

    public static void show(Activity activity, String message, Map<String, String> profile) {
        EventTripIntentParser.EventIntent event = EventTripIntentParser.parse(message);
        JourneyIntentParser.JourneyIntent journey = JourneyIntentParser.parse(
                message, profile, Collections.emptyList());
        List<String> places = DestinationParser.extractDestinations(message);
        KnownEventCatalog.Entry knownEvent = KnownEventCatalog.find(message);
        Map<String, String> storedEvent = event.recognized()
                ? findStoredEvent(activity, event.eventName) : Collections.emptyMap();

        String destination = event.found() ? event.destination
                : !storedEvent.getOrDefault("destination", "").isEmpty() ? storedEvent.get("destination")
                : journey.found() ? journey.destination
                : places.isEmpty() ? "" : places.get(places.size() - 1);
        String query = event.recognized()
                ? event.eventName + (destination.isEmpty() ? "" : " " + destination)
                : destination.isEmpty() ? message : destination;
        String origin = journey.found() ? journey.origin : profile.getOrDefault("hometown", "");
        String mode = journey.found() && !journey.modes.isEmpty() ? journey.modes.get(0) : "";
        String storedOfficial = storedEvent.getOrDefault("official_url", "");
        String officialUrl = knownEvent != null ? knownEvent.officialUrl : storedOfficial;

        String[] choices = {
                "Map",
                "Photos",
                "Videos",
                "Route and local transit",
                officialUrl.isEmpty() ? "Find the official or public event page" : "Open official event page",
                "Live travel options"
        };

        new AlertDialog.Builder(activity)
                .setTitle("Explore")
                .setMessage("Sarah can show public maps, photo searches, videos, routes, official event pages, and live travel sources. Verify current schedules, prices, closures, and access details before relying on them.")
                .setItems(choices, (dialog, which) -> {
                    if (which == 4) {
                        String url = officialUrl.isEmpty()
                                ? "https://www.google.com/search?q=" + Uri.encode(query + " official event")
                                : officialUrl;
                        open(activity, url);
                        return;
                    }
                    if (which == 5) {
                        showLiveOptions(activity, origin, destination, mode, query);
                        return;
                    }
                    String kind = which == 0 ? "map" : which == 1 ? "photos" : which == 2 ? "videos" : "route";
                    Intent intent = new Intent(activity, TravelExplorerActivity.class);
                    intent.putExtra(TravelExplorerActivity.EXTRA_KIND, kind);
                    intent.putExtra(TravelExplorerActivity.EXTRA_QUERY, query);
                    intent.putExtra(TravelExplorerActivity.EXTRA_ORIGIN, origin);
                    intent.putExtra(TravelExplorerActivity.EXTRA_DESTINATION, destination.isEmpty() ? query : destination);
                    intent.putExtra(TravelExplorerActivity.EXTRA_MODE, mode);
                    activity.startActivity(intent);
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private static Map<String, String> findStoredEvent(Activity activity, String eventName) {
        EventTripStore store = new EventTripStore(activity.getApplicationContext());
        try {
            for (Map<String, String> event : store.listActiveEventTrips(50)) {
                if (eventName.equalsIgnoreCase(event.getOrDefault("event_name", ""))) return event;
            }
        } finally {
            store.close();
        }
        return Collections.emptyMap();
    }

    private static void showLiveOptions(
            Activity activity,
            String origin,
            String destination,
            String mode,
            String query) {
        String usableDestination = destination == null || destination.isEmpty() ? query : destination;
        String route = (origin == null || origin.isEmpty() ? "" : origin + " to ") + usableDestination;
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
                                + "&destination=" + Uri.encode(usableDestination) + "&travelmode=" + travelMode;
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
            Toast.makeText(activity, "No browser could open that source.", Toast.LENGTH_LONG).show();
        }
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }
}
