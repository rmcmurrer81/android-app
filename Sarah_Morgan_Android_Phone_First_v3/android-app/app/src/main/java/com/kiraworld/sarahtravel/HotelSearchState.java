package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Non-secret hotel search criteria, separated by active profile. */
public final class HotelSearchState {
    private static final String[] STRING_SUFFIXES = {
            "destination", "check_in", "check_out"
    };
    private static final String[] INTEGER_SUFFIXES = {"adults", "rooms"};
    public final String destination;
    public final String checkIn;
    public final String checkOut;
    public final int adults;
    public final int rooms;

    private HotelSearchState(
            String destination,
            String checkIn,
            String checkOut,
            int adults,
            int rooms) {
        this.destination = clean(destination);
        this.checkIn = clean(checkIn);
        this.checkOut = clean(checkOut);
        this.adults = Math.max(1, adults);
        this.rooms = Math.max(1, rooms);
    }

    public static HotelSearchState load(Context context, TravelContextSnapshot trip) {
        SharedPreferences p = context.getSharedPreferences("sarah_hotel_search", Context.MODE_PRIVATE);
        String prefix = key(trip.personId);
        return new HotelSearchState(
                p.getString(prefix + "destination", trip.destination),
                p.getString(prefix + "check_in", trip.startDate),
                p.getString(prefix + "check_out", trip.endDate),
                p.getInt(prefix + "adults", trip.travelers),
                p.getInt(prefix + "rooms", 1));
    }

    public void save(Context context, String personId) {
        String prefix = key(personId);
        context.getSharedPreferences("sarah_hotel_search", Context.MODE_PRIVATE)
                .edit()
                .putString(prefix + "destination", destination)
                .putString(prefix + "check_in", checkIn)
                .putString(prefix + "check_out", checkOut)
                .putInt(prefix + "adults", adults)
                .putInt(prefix + "rooms", rooms)
                .apply();
    }

    public TravelContextSnapshot applyTo(TravelContextSnapshot trip) {
        return trip.withSearch(destination, checkIn, checkOut, adults);
    }

    public static HotelSearchState of(
            String destination,
            String checkIn,
            String checkOut,
            int adults,
            int rooms) {
        return new HotelSearchState(destination, checkIn, checkOut, adults, rooms);
    }

    public static boolean moveProfile(Context context, String oldPersonId, String newPersonId) {
        String oldPrefix = key(oldPersonId);
        String newPrefix = key(newPersonId);
        if (oldPrefix.equals(newPrefix)) return true;
        SharedPreferences preferences = context.getSharedPreferences(
                "sarah_hotel_search", Context.MODE_PRIVATE);
        if (!hasAny(preferences, oldPrefix)) return true;
        String sourceSnapshot = snapshot(preferences, oldPrefix);
        String targetSnapshot = snapshot(preferences, newPrefix);
        String expectedMergedSnapshot = mergedSnapshot(
                preferences, oldPrefix, newPrefix);
        boolean targetHadData = hasAny(preferences, newPrefix);
        if (targetHadData && !sourceSnapshot.equals(targetSnapshot)
                && !ProfileMigrationArchiveStore.preserveCollision(
                        context,
                        "hotel_search",
                        oldPersonId,
                        newPersonId,
                        sourceSnapshot,
                        targetSnapshot)) return false;

        SharedPreferences.Editor merge = preferences.edit();
        for (String suffix : STRING_SUFFIXES) {
            if (!preferences.contains(newPrefix + suffix) && preferences.contains(oldPrefix + suffix)) {
                merge.putString(newPrefix + suffix, preferences.getString(oldPrefix + suffix, ""));
            }
        }
        for (String suffix : INTEGER_SUFFIXES) {
            if (!preferences.contains(newPrefix + suffix) && preferences.contains(oldPrefix + suffix)) {
                merge.putInt(newPrefix + suffix, preferences.getInt(oldPrefix + suffix, 1));
            }
        }
        if (!merge.commit()) return false;
        if (!expectedMergedSnapshot.equals(snapshot(preferences, newPrefix))) return false;
        if (targetHadData && !sourceSnapshot.equals(targetSnapshot)
                && !ProfileMigrationArchiveStore.containsExact(
                        context,
                        "hotel_search",
                        oldPersonId,
                        newPersonId,
                        sourceSnapshot,
                        targetSnapshot)) return false;

        SharedPreferences.Editor remove = preferences.edit();
        for (String suffix : STRING_SUFFIXES) remove.remove(oldPrefix + suffix);
        for (String suffix : INTEGER_SUFFIXES) remove.remove(oldPrefix + suffix);
        if (!remove.commit()) return false;
        for (String suffix : STRING_SUFFIXES) {
            if (preferences.contains(oldPrefix + suffix)) return false;
        }
        for (String suffix : INTEGER_SUFFIXES) {
            if (preferences.contains(oldPrefix + suffix)) return false;
        }
        return true;
    }

    private static boolean hasAny(SharedPreferences preferences, String prefix) {
        for (String suffix : STRING_SUFFIXES) {
            if (preferences.contains(prefix + suffix)) return true;
        }
        for (String suffix : INTEGER_SUFFIXES) {
            if (preferences.contains(prefix + suffix)) return true;
        }
        return false;
    }

    private static String snapshot(SharedPreferences preferences, String prefix) {
        JSONObject json = new JSONObject();
        try {
            for (String suffix : STRING_SUFFIXES) {
                if (preferences.contains(prefix + suffix)) {
                    json.put(suffix, preferences.getString(prefix + suffix, ""));
                }
            }
            for (String suffix : INTEGER_SUFFIXES) {
                if (preferences.contains(prefix + suffix)) {
                    json.put(suffix, preferences.getInt(prefix + suffix, 1));
                }
            }
        } catch (Exception ignored) { }
        return json.toString();
    }

    private static String mergedSnapshot(
            SharedPreferences preferences,
            String oldPrefix,
            String newPrefix) {
        JSONObject json = new JSONObject();
        try {
            for (String suffix : STRING_SUFFIXES) {
                if (preferences.contains(newPrefix + suffix)) {
                    json.put(suffix, preferences.getString(newPrefix + suffix, ""));
                } else if (preferences.contains(oldPrefix + suffix)) {
                    json.put(suffix, preferences.getString(oldPrefix + suffix, ""));
                }
            }
            for (String suffix : INTEGER_SUFFIXES) {
                if (preferences.contains(newPrefix + suffix)) {
                    json.put(suffix, preferences.getInt(newPrefix + suffix, 1));
                } else if (preferences.contains(oldPrefix + suffix)) {
                    json.put(suffix, preferences.getInt(oldPrefix + suffix, 1));
                }
            }
        } catch (Exception ignored) { }
        return json.toString();
    }

    private static String key(String personId) {
        return "p" + clean(personId).replaceAll("[^A-Za-z0-9_-]", "_") + "_";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
