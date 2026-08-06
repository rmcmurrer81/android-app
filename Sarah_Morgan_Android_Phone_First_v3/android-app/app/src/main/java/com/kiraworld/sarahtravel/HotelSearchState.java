package com.kiraworld.sarahtravel;

import android.content.Context;
import android.content.SharedPreferences;

/** Non-secret hotel search criteria, separated by active profile. */
public final class HotelSearchState {
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

    private static String key(String personId) {
        return "p" + clean(personId).replaceAll("[^A-Za-z0-9_-]", "_") + "_";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
