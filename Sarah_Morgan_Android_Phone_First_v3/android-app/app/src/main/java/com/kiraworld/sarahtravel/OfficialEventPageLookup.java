package com.kiraworld.sarahtravel;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads basic verified details from a known event's official public page without a model key. */
public final class OfficialEventPageLookup {
    public static final class Result {
        public final boolean found;
        public final String eventName;
        public final String destination;
        public final String venue;
        public final String address;
        public final String startDate;
        public final String endDate;
        public final String hours;
        public final String officialUrl;
        public final String sourceNote;

        Result(
                boolean found,
                String eventName,
                String destination,
                String venue,
                String address,
                String startDate,
                String endDate,
                String hours,
                String officialUrl,
                String sourceNote) {
            this.found = found;
            this.eventName = value(eventName);
            this.destination = value(destination);
            this.venue = value(venue);
            this.address = value(address);
            this.startDate = value(startDate);
            this.endDate = value(endDate);
            this.hours = value(hours);
            this.officialUrl = value(officialUrl);
            this.sourceNote = value(sourceNote);
        }

        public boolean datesKnown() {
            return !startDate.isEmpty();
        }
    }

    private static final Pattern TWO_DAY_RANGE = Pattern.compile(
            "(?i)\\b(January|February|March|April|May|June|July|August|September|October|November|December)\\s+(\\d{1,2})\\s*(?:&|and|-)\\s*(\\d{1,2})\\s*,?\\s*(20\\d{2})\\b");
    private static final Pattern SINGLE_DATE = Pattern.compile(
            "(?i)\\b(January|February|March|April|May|June|July|August|September|October|November|December)\\s+(\\d{1,2})\\s*,?\\s*(20\\d{2})\\b");
    private static final Pattern HOURS = Pattern.compile(
            "(?i)\\b(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))\\s*(?:-|to)\\s*(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))\\b");

    private OfficialEventPageLookup() { }

    public static Result lookup(KnownEventCatalog.Entry entry) throws Exception {
        if (entry == null || entry.officialUrl.isEmpty()) return empty(entry);
        HttpURLConnection connection = (HttpURLConnection) new URL(entry.officialUrl).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(16000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "SarahMorganTravel/1.4 (private prototype; official event lookup)");
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 400) {
            connection.disconnect();
            return empty(entry);
        }
        String html;
        try (InputStream in = connection.getInputStream()) {
            html = read(in);
        } finally {
            connection.disconnect();
        }
        String text = htmlToText(html);
        String start = "";
        String end = "";

        Matcher range = TWO_DAY_RANGE.matcher(text);
        if (range.find()) {
            int month = month(range.group(1));
            int firstDay = integer(range.group(2));
            int secondDay = integer(range.group(3));
            int year = integer(range.group(4));
            if (month > 0 && firstDay > 0 && secondDay > 0 && year > 0) {
                start = iso(year, month, firstDay);
                end = iso(year, month, secondDay);
            }
        }
        if (start.isEmpty()) {
            Matcher single = SINGLE_DATE.matcher(text);
            if (single.find()) {
                int month = month(single.group(1));
                int day = integer(single.group(2));
                int year = integer(single.group(3));
                if (month > 0 && day > 0 && year > 0) {
                    start = iso(year, month, day);
                    end = start;
                }
            }
        }

        String hours = "";
        Matcher hoursMatcher = HOURS.matcher(text);
        if (hoursMatcher.find()) {
            hours = normalizeSpace(hoursMatcher.group(1)).toUpperCase(Locale.US)
                    + "–" + normalizeSpace(hoursMatcher.group(2)).toUpperCase(Locale.US);
        }
        boolean found = !text.isEmpty();
        return new Result(
                found,
                entry.eventName,
                entry.destination,
                entry.defaultVenue,
                entry.defaultAddress,
                start,
                end,
                hours,
                entry.officialUrl,
                found
                        ? "Read directly from the event's official public website; verify again before travel because dates and policies can change."
                        : "Official event page could not be read.");
    }

    public static long apply(EventTripStore store, KnownEventCatalog.Entry entry, Result result) {
        if (store == null || entry == null || result == null || !result.found) return -1;
        long id = store.upsertEventTrip(entry.eventName, entry.destination, true);
        if (id <= 0) return id;
        long now = System.currentTimeMillis();
        String dateSummary = dateSummary(result);
        store.updateEventResearch(
                id,
                result.eventName,
                result.destination,
                result.venue,
                result.startDate,
                result.endDate,
                result.officialUrl,
                dateSummary,
                "",
                "",
                result.address.isEmpty() ? "" : "Venue address: " + result.address,
                result.sourceNote,
                now,
                now + 24L * 60L * 60L * 1000L);
        if (!result.startDate.isEmpty()) {
            store.addEventUpdate(
                    id,
                    "official_dates_" + result.startDate + "_" + result.endDate,
                    "dates",
                    "Official dates found",
                    dateSummary,
                    result.officialUrl,
                    result.startDate);
        }
        return id;
    }

    public static String conversationalReply(Result result) {
        if (result == null || !result.found) return null;
        StringBuilder reply = new StringBuilder();
        reply.append("I found the official event page. ")
                .append(result.eventName)
                .append(" is in ")
                .append(result.destination);
        if (!result.venue.isEmpty()) reply.append(" at ").append(result.venue);
        reply.append(".");
        if (!result.address.isEmpty()) reply.append(" The listed address is ").append(result.address).append(".");
        if (!result.startDate.isEmpty()) {
            reply.append(" The official page lists ").append(humanDateRange(result.startDate, result.endDate));
            if (!result.hours.isEmpty()) reply.append(", ").append(result.hours);
            reply.append(".");
            try {
                LocalDate end = LocalDate.parse(result.endDate.isEmpty() ? result.startDate : result.endDate);
                if (end.isBefore(LocalDate.now())) {
                    reply.append(" Those dates have already passed, so I saved the event and will watch the official page for the next announced dates.");
                }
            } catch (Exception ignored) { }
        } else {
            reply.append(" I could not extract a verified date from the page yet, so I saved the official source without inventing one.");
        }
        reply.append(" Use Explore for the map, public photos, videos, and route options.");
        return reply.toString();
    }

    private static String dateSummary(Result result) {
        if (result == null || result.startDate.isEmpty()) return "Official event page found; dates still need verification.";
        String summary = humanDateRange(result.startDate, result.endDate);
        if (!result.hours.isEmpty()) summary += ", " + result.hours;
        return summary;
    }

    private static String humanDateRange(String start, String end) {
        try {
            LocalDate a = LocalDate.parse(start);
            LocalDate b = LocalDate.parse(end == null || end.isEmpty() ? start : end);
            DateTimeFormatter monthDay = DateTimeFormatter.ofPattern("MMMM d", Locale.US);
            DateTimeFormatter full = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US);
            if (a.equals(b)) return a.format(full);
            if (a.getYear() == b.getYear() && a.getMonth() == b.getMonth()) {
                return a.format(monthDay) + "–" + b.getDayOfMonth() + ", " + b.getYear();
            }
            return a.format(full) + "–" + b.format(full);
        } catch (Exception ignored) {
            return start + (end == null || end.isEmpty() || start.equals(end) ? "" : " to " + end);
        }
    }

    private static Result empty(KnownEventCatalog.Entry entry) {
        return new Result(
                false,
                entry == null ? "" : entry.eventName,
                entry == null ? "" : entry.destination,
                entry == null ? "" : entry.defaultVenue,
                entry == null ? "" : entry.defaultAddress,
                "", "", "",
                entry == null ? "" : entry.officialUrl,
                "Official event page could not be read.");
    }

    private static String read(InputStream input) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                out.append(buffer, 0, count);
                if (out.length() > 1_500_000) break;
            }
        }
        return out.toString();
    }

    private static String htmlToText(String html) {
        if (html == null) return "";
        return normalizeSpace(html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&amp;", "&")
                .replace("&nbsp;", " ")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace("&ndash;", "-")
                .replace("&mdash;", "-"));
    }

    private static String normalizeSpace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static int month(String value) {
        try { return Month.valueOf(value.toUpperCase(Locale.US)).getValue(); }
        catch (Exception ignored) { return 0; }
    }

    private static int integer(String value) {
        try { return Integer.parseInt(value); }
        catch (Exception ignored) { return 0; }
    }

    private static String iso(int year, int month, int day) {
        try { return LocalDate.of(year, month, day).toString(); }
        catch (Exception ignored) { return ""; }
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
