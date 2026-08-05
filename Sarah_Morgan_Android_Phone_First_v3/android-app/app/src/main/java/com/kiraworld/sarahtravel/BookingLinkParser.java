package com.kiraworld.sarahtravel;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds booking links without opening private accounts or scraping credentials. */
public final class BookingLinkParser {
    public static final class BookingLink {
        public final String url;
        public final String provider;
        public final String bookingType;

        BookingLink(String url, String provider, String bookingType) {
            this.url = url == null ? "" : url;
            this.provider = provider == null ? "Other" : provider;
            this.bookingType = bookingType == null ? "travel" : bookingType;
        }

        public boolean found() { return !url.isEmpty(); }
    }

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"]+", Pattern.CASE_INSENSITIVE);

    private BookingLinkParser() { }

    public static BookingLink parse(String text) {
        Matcher matcher = URL.matcher(text == null ? "" : text);
        if (!matcher.find()) return new BookingLink("", "Other", "travel");
        String url = trimPunctuation(matcher.group());
        String lower = url.toLowerCase(Locale.US);
        String provider = provider(lower);
        String type = type((text == null ? "" : text).toLowerCase(Locale.US), lower);
        return new BookingLink(url, provider, type);
    }

    private static String provider(String lower) {
        if (lower.contains("expedia.")) return "Expedia";
        if (lower.contains("booking.com")) return "Booking.com";
        if (lower.contains("hotels.com")) return "Hotels.com";
        if (lower.contains("airbnb.")) return "Airbnb";
        if (lower.contains("vrbo.")) return "Vrbo";
        if (lower.contains("tripadvisor.")) return "Tripadvisor";
        if (lower.contains("united.")) return "United Airlines";
        if (lower.contains("delta.")) return "Delta Air Lines";
        if (lower.contains("aa.com")) return "American Airlines";
        if (lower.contains("jetblue.")) return "JetBlue";
        if (lower.contains("southwest.")) return "Southwest";
        if (lower.contains("amtrak.")) return "Amtrak";
        return "Other";
    }

    private static String type(String text, String url) {
        String all = text + " " + url;
        if (containsAny(all, "hotel", "lodging", "room", "resort", "airbnb", "hotels.com")) return "hotel";
        if (containsAny(all, "flight", "airline", "ticket", "airfare")) return "flight";
        if (containsAny(all, "train", "amtrak")) return "rail";
        if (containsAny(all, "car rental", "rental car")) return "car";
        if (containsAny(all, "event ticket", "admission", "badge", "registration")) return "event";
        return "travel";
    }

    private static String trimPunctuation(String value) {
        return value == null ? "" : value.replaceAll("[),.;!?]+$", "");
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) if (text.contains(phrase)) return true;
        return false;
    }
}
