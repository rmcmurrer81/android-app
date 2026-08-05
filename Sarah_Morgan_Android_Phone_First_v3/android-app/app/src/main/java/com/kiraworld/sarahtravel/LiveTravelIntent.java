package com.kiraworld.sarahtravel;

import java.util.Locale;

/** Determines when connected mode should use current-source research. */
public final class LiveTravelIntent {
    private LiveTravelIntent() { }

    public static boolean needsCurrentSources(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.US);
        return containsAny(lower,
                "current", "today", "this week", "deal", "price", "fare", "discount",
                "open", "hours", "weather", "event", "things to do", "places to visit",
                "map", "route", "directions", "amtrak", "train", "rail", "metro",
                "subway", "transit", "bus", "ferry", "drive", "traffic", "parking",
                "delay", "service change", "comic con", "comic-con", "ces", "nycc");
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }
}
