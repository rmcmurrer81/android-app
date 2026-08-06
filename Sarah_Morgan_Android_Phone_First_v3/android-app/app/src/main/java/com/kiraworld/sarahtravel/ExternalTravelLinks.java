package com.kiraworld.sarahtravel;

import android.net.Uri;

/**
 * Creates user-visible links to external travel services. A link is not a
 * booking, endorsement, verified price, or proof of availability.
 */
public final class ExternalTravelLinks {
    private ExternalTravelLinks() { }

    public static String googleHotels(TravelContextSnapshot trip) {
        return "https://www.google.com/travel/search?q="
                + enc("hotels in " + place(trip));
    }

    public static String expediaHotels(TravelContextSnapshot trip, int rooms) {
        Uri.Builder builder = Uri.parse("https://www.expedia.com/Hotel-Search").buildUpon()
                .appendQueryParameter("destination", place(trip))
                .appendQueryParameter("adults", String.valueOf(trip.travelers))
                .appendQueryParameter("rooms", String.valueOf(Math.max(1, rooms)));
        addDates(builder, trip);
        return builder.build().toString();
    }

    public static String bookingHotels(TravelContextSnapshot trip, int rooms) {
        Uri.Builder builder = Uri.parse("https://www.booking.com/searchresults.html").buildUpon()
                .appendQueryParameter("ss", place(trip))
                .appendQueryParameter("group_adults", String.valueOf(trip.travelers))
                .appendQueryParameter("no_rooms", String.valueOf(Math.max(1, rooms)));
        if (trip.hasDates()) {
            builder.appendQueryParameter("checkin", trip.startDate)
                    .appendQueryParameter("checkout", trip.endDate);
        }
        return builder.build().toString();
    }

    public static String hotelsDotCom(TravelContextSnapshot trip) {
        return "https://www.hotels.com/Hotel-Search?destination=" + enc(place(trip));
    }

    public static String pricelineHotels(TravelContextSnapshot trip) {
        return "https://www.priceline.com/stay/search?destination=" + enc(place(trip));
    }

    public static String roveHotels(TravelContextSnapshot trip) {
        return "https://www.rove.com/awardfares?query=" + enc(place(trip));
    }

    public static String directHotelSiteSearch(TravelContextSnapshot trip) {
        return googleSearch("hotels in " + place(trip) + " official hotel website direct booking");
    }

    public static String hotelMap(TravelContextSnapshot trip) {
        return mapsSearch("hotels near " + place(trip));
    }

    public static String restaurants(TravelContextSnapshot trip) {
        return mapsSearch("restaurants near " + place(trip));
    }

    public static String affordableFood(TravelContextSnapshot trip) {
        return mapsSearch("affordable restaurants near " + place(trip));
    }

    public static String freeThings(TravelContextSnapshot trip) {
        return googleSearch("free things to do in " + place(trip) + " official tourism");
    }

    public static String localEvents(TravelContextSnapshot trip) {
        String date = trip.hasDates() ? " " + trip.startDate + " " + trip.endDate : "";
        return googleSearch("events in " + place(trip) + date + " official calendar");
    }

    public static String accessiblePlaces(TravelContextSnapshot trip) {
        return googleSearch("accessible attractions in " + place(trip) + " official accessibility information");
    }

    public static String quietPlaces(TravelContextSnapshot trip) {
        return mapsSearch("quiet parks libraries gardens near " + place(trip));
    }

    public static String filmingLocations(TravelContextSnapshot trip) {
        return googleSearch("movie and television filming locations in " + place(trip));
    }

    public static String museumsHistory(TravelContextSnapshot trip) {
        return mapsSearch("history museums landmarks near " + place(trip));
    }

    public static String transitRoute(TravelContextSnapshot trip) {
        return directions(trip.origin, place(trip), "transit");
    }

    public static String driveRoute(TravelContextSnapshot trip) {
        return directions(trip.origin, place(trip), "driving");
    }

    public static String walkRoute(TravelContextSnapshot trip) {
        return directions(trip.origin, place(trip), "walking");
    }

    public static String bikeRoute(TravelContextSnapshot trip) {
        return directions(trip.origin, place(trip), "bicycling");
    }

    public static String amtrak(TravelContextSnapshot trip) {
        return "https://www.amtrak.com/home.html";
    }

    public static String busRailSearch(TravelContextSnapshot trip) {
        return googleSearch(trip.origin + " to " + place(trip) + " train bus schedule fare");
    }

    public static String flights(TravelContextSnapshot trip) {
        String route = (trip.origin.isEmpty() ? "" : trip.origin + " to ") + place(trip);
        if (trip.hasDates()) route += " " + trip.startDate + " " + trip.endDate;
        return "https://www.google.com/travel/flights?q=" + enc(route);
    }

    public static String uber(TravelContextSnapshot trip) {
        Uri.Builder builder = Uri.parse("https://m.uber.com/ul/").buildUpon()
                .appendQueryParameter("action", "setPickup")
                .appendQueryParameter("pickup", "my_location")
                .appendQueryParameter("dropoff[formatted_address]", place(trip));
        return builder.build().toString();
    }

    public static String lyft(TravelContextSnapshot trip) {
        return "https://ride.lyft.com/?destination=" + enc(place(trip));
    }

    public static String localTaxi(TravelContextSnapshot trip) {
        return mapsSearch("taxi service near " + place(trip));
    }

    public static String carRental(TravelContextSnapshot trip) {
        return googleSearch("car rental near " + place(trip) + " compare total price");
    }

    public static String gasStations(String locationOrRoute) {
        return mapsSearch("gas stations " + safe(locationOrRoute));
    }

    public static String evCharging(String locationOrRoute) {
        return mapsSearch("EV charging stations " + safe(locationOrRoute));
    }

    public static String restStops(String route) {
        return mapsSearch("rest areas along " + safe(route));
    }

    public static String roadsideAttractions(String route) {
        return googleSearch("interesting roadside attractions along " + safe(route));
    }

    public static String scenicStops(String route) {
        return googleSearch("scenic stops viewpoints along " + safe(route));
    }

    public static String roadFood(String route) {
        return mapsSearch("food along " + safe(route));
    }

    public static String roadsideHotels(String route) {
        return mapsSearch("hotels along " + safe(route));
    }

    public static String gasPriceSearch(String route) {
        return googleSearch("current gas prices along " + safe(route));
    }

    public static String plugShare(String destination) {
        return "https://www.plugshare.com/" + (safe(destination).isEmpty() ? "" : "?q=" + enc(destination));
    }

    public static String mapsSearch(String query) {
        return "https://www.google.com/maps/search/?api=1&query=" + enc(query);
    }

    public static String directions(String origin, String destination, String mode) {
        Uri.Builder builder = Uri.parse("https://www.google.com/maps/dir/").buildUpon()
                .appendQueryParameter("api", "1")
                .appendQueryParameter("destination", safe(destination));
        if (!safe(origin).isEmpty()) builder.appendQueryParameter("origin", safe(origin));
        if (!safe(mode).isEmpty()) builder.appendQueryParameter("travelmode", safe(mode));
        return builder.build().toString();
    }

    public static String googleSearch(String query) {
        return "https://www.google.com/search?q=" + enc(query);
    }

    private static void addDates(Uri.Builder builder, TravelContextSnapshot trip) {
        if (!trip.hasDates()) return;
        builder.appendQueryParameter("startDate", trip.startDate)
                .appendQueryParameter("endDate", trip.endDate);
    }

    private static String place(TravelContextSnapshot trip) {
        return trip == null || trip.destination.isEmpty() ? "destination" : trip.destination;
    }

    private static String enc(String value) {
        return Uri.encode(safe(value));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
