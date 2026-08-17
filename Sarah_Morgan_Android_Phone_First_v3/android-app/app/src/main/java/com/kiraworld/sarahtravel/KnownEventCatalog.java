package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Small official-source registry for events Sarah can identify without a model key. */
public final class KnownEventCatalog {
    public static final class Entry {
        public final String key;
        public final String eventName;
        public final String destination;
        public final String officialUrl;
        public final String defaultVenue;
        public final String defaultAddress;
        public final List<String> aliases;

        Entry(
                String key,
                String eventName,
                String destination,
                String officialUrl,
                String defaultVenue,
                String defaultAddress,
                String... aliases) {
            this.key = key;
            this.eventName = eventName;
            this.destination = destination;
            this.officialUrl = officialUrl;
            this.defaultVenue = defaultVenue;
            this.defaultAddress = defaultAddress;
            List<String> copy = new ArrayList<>();
            Collections.addAll(copy, aliases);
            this.aliases = Collections.unmodifiableList(copy);
        }
    }

    private static final List<Entry> ENTRIES;

    static {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(
                "bell_county_comic_con",
                "Bell County Comic Con",
                "Belton, Texas",
                "https://www.bellcountycomiccon.com/",
                "Cadence Bank Center",
                "300 W Loop 121, Belton, TX 76513",
                "bell county comic con",
                "bell country comic con",
                "bell county comicon",
                "bell country comicon",
                "bccc"));
        entries.add(new Entry(
                "popcon_indy",
                "PopCon Indy",
                "Indianapolis, Indiana",
                "https://popcon.us/popcon-indy/",
                "Indiana Convention Center",
                "100 S Capitol Ave, Indianapolis, IN 46225",
                "popcon indy",
                "indy popcon",
                "indy pop con",
                "indianapolis popcon",
                "indianapolis pop con"));
        entries.add(new Entry(
                "ces",
                "CES",
                "Las Vegas, Nevada",
                "https://www.ces.tech/",
                "",
                "",
                "consumer electronics show",
                "ces"));
        entries.add(new Entry(
                "san_diego_comic_con",
                "San Diego Comic-Con",
                "San Diego, California",
                "https://www.comic-con.org/cc/",
                "",
                "",
                "san diego comic-con",
                "san diego comic con",
                "comic-con international",
                "comic con international",
                "sdcc"));
        entries.add(new Entry(
                "new_york_comic_con",
                "New York Comic Con",
                "New York City",
                "https://www.newyorkcomiccon.com/",
                "Javits Center",
                "429 11th Ave, New York, NY 10001",
                "new york comic con",
                "ny comic con",
                "nycc"));
        ENTRIES = Collections.unmodifiableList(entries);
    }

    private KnownEventCatalog() { }

    public static Entry find(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) return null;
        for (Entry entry : ENTRIES) {
            if (containsNormalizedPhrase(normalized, normalize(entry.eventName))) return entry;
            for (String alias : entry.aliases) {
                if (containsNormalizedPhrase(normalized, normalize(alias))) return entry;
            }
        }
        return null;
    }

    public static Entry findByEventName(String eventName) {
        String normalized = normalize(eventName);
        for (Entry entry : ENTRIES) {
            if (normalized.equals(normalize(entry.eventName)) || normalized.equals(entry.key)) return entry;
            for (String alias : entry.aliases) {
                if (normalized.equals(normalize(alias))) return entry;
            }
        }
        return null;
    }

    public static List<Entry> all() {
        return ENTRIES;
    }

    private static boolean containsNormalizedPhrase(String text, String phrase) {
        if (text == null || phrase == null || text.isEmpty() || phrase.isEmpty()) return false;
        return (" " + text + " ").contains(" " + phrase + " ");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
