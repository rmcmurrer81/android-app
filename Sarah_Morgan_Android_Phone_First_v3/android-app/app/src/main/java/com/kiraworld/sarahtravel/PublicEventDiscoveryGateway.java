package com.kiraworld.sarahtravel;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort no-key discovery for an unfamiliar public event.
 *
 * Search results identify candidate public pages. Sarah prefers a page whose
 * title/domain closely matches the event and then reads schema.org Event data
 * or visible official-page text. Uncertain fields remain blank.
 */
public final class PublicEventDiscoveryGateway {
    private static final Pattern RESULT_LINK = Pattern.compile(
            "(?is)<a[^>]*class=\\\"[^\\\"]*result__a[^\\\"]*\\\"[^>]*href=\\\"([^\\\"]+)\\\"[^>]*>(.*?)</a>");
    private static final Pattern RESULT_LINK_REVERSED = Pattern.compile(
            "(?is)<a[^>]*href=\\\"([^\\\"]+)\\\"[^>]*class=\\\"[^\\\"]*result__a[^\\\"]*\\\"[^>]*>(.*?)</a>");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    private PublicEventDiscoveryGateway() { }

    public static PublicSourceResult answerResult(
            Context context,
            String message,
            List<Map<String, String>> history) {
        if (context == null) return null;
        String eventName = GenericEventReference.recentEvent(history, message);
        if (eventName.isEmpty()) return null;
        if (KnownEventCatalog.find(eventName) != null) return null;

        try {
            Candidate candidate = findCandidate(eventName);
            if (candidate == null) return null;
            PageFacts facts = inspect(candidate, eventName);
            if (!facts.readable) return null;

            OfficialEventPageLookup.Result result = new OfficialEventPageLookup.Result(
                    true,
                    facts.eventName.isEmpty() ? eventName : facts.eventName,
                    facts.destination,
                    facts.venue,
                    facts.address,
                    facts.startDate,
                    facts.endDate,
                    facts.hours,
                    candidate.url,
                    "Discovered from public search and read from the likely official public event page. Verify before travel because search ranking and event details can change.");

            if (!result.destination.isEmpty()) {
                KnownEventCatalog.Entry dynamic = new KnownEventCatalog.Entry(
                        stableKey(result.eventName),
                        result.eventName,
                        result.destination,
                        candidate.url,
                        result.venue,
                        result.address,
                        result.eventName);
                EventTripStore store = new EventTripStore(context.getApplicationContext());
                try {
                    OfficialEventPageLookup.apply(store, dynamic, result);
                } finally {
                    store.close();
                }
            }

            StringBuilder reply = new StringBuilder();
            reply.append("I found a likely official page for ").append(result.eventName).append(".");
            if (!result.destination.isEmpty()) reply.append(" It is listed in ").append(result.destination).append(".");
            if (!result.venue.isEmpty()) reply.append(" The venue is ").append(result.venue).append(".");
            if (!result.startDate.isEmpty()) {
                reply.append(" The page lists ").append(humanDates(result.startDate, result.endDate)).append(".");
            } else {
                reply.append(" I could not extract a verified date yet, so I will not invent one.");
            }
            reply.append(" I saved verified fields when available. Use the media panel for the official page, map, public photos, videos, and route. Because this event was discovered rather than pre-cataloged, check the official page before booking.");
            return PublicSourceResult.verified(reply.toString(), candidate.url);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String answer(
            Context context,
            String message,
            List<Map<String, String>> history) {
        PublicSourceResult result = answerResult(context, message, history);
        return result == null ? null : result.reply;
    }

    private static Candidate findCandidate(String eventName) throws Exception {
        String query = eventName + " official event dates location";
        String url = "https://html.duckduckgo.com/html/?q=" + encode(query);
        String html = get(url, "text/html");
        List<Candidate> candidates = new ArrayList<>();
        collectResults(RESULT_LINK.matcher(html), eventName, candidates);
        collectResults(RESULT_LINK_REVERSED.matcher(html), eventName, candidates);
        candidates.sort(Comparator.comparingInt((Candidate c) -> c.score).reversed());
        for (Candidate candidate : candidates) {
            if (candidate.score < 1) continue;
            if (candidate.url.startsWith("https://")) return candidate;
        }
        return null;
    }

    private static void collectResults(
            Matcher matcher,
            String eventName,
            List<Candidate> out) {
        while (matcher.find() && out.size() < 16) {
            String url = unwrap(htmlDecode(matcher.group(1)));
            String title = stripTags(htmlDecode(matcher.group(2)));
            if (url.isEmpty() || blocked(url)) continue;
            int score = score(eventName, title, url);
            boolean duplicate = false;
            for (Candidate prior : out) if (prior.url.equals(url)) duplicate = true;
            if (!duplicate) out.add(new Candidate(url, title, score));
        }
    }

    private static PageFacts inspect(Candidate candidate, String requestedName) throws Exception {
        String html = get(candidate.url, "text/html,application/xhtml+xml");
        if (html.isEmpty()) return new PageFacts();
        PageFacts facts = new PageFacts();
        facts.readable = true;
        facts.eventName = firstNonEmpty(
                jsonValue(html, "name"),
                meta(html, "og:title"),
                title(html),
                requestedName);
        facts.startDate = isoDate(jsonValue(html, "startDate"));
        facts.endDate = isoDate(jsonValue(html, "endDate"));
        facts.venue = firstNonEmpty(
                jsonObjectValue(html, "location", "name"),
                jsonValue(html, "locationName"));
        String locality = firstNonEmpty(jsonValue(html, "addressLocality"), jsonValue(html, "city"));
        String region = firstNonEmpty(jsonValue(html, "addressRegion"), jsonValue(html, "state"));
        facts.destination = joinPlace(locality, region);
        facts.address = address(html);

        if (facts.destination.isEmpty()) {
            String description = firstNonEmpty(meta(html, "description"), meta(html, "og:description"));
            facts.destination = placeFromDescription(description);
        }

        if (facts.startDate.isEmpty() && !facts.destination.isEmpty()) {
            KnownEventCatalog.Entry dynamic = new KnownEventCatalog.Entry(
                    stableKey(requestedName),
                    requestedName,
                    facts.destination,
                    candidate.url,
                    facts.venue,
                    facts.address,
                    requestedName);
            OfficialEventPageLookup.Result parsed = OfficialEventPageLookup.lookup(dynamic);
            if (parsed.found) {
                facts.startDate = parsed.startDate;
                facts.endDate = parsed.endDate;
                facts.hours = parsed.hours;
            }
        }
        return facts;
    }

    private static String jsonValue(String html, String key) {
        Pattern pattern = Pattern.compile(
                "(?is)\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]{1,300})\\\"");
        Matcher matcher = pattern.matcher(html);
        return matcher.find() ? jsonDecode(matcher.group(1)) : "";
    }

    private static String jsonObjectValue(String html, String objectKey, String valueKey) {
        Pattern object = Pattern.compile(
                "(?is)\\\"" + Pattern.quote(objectKey) + "\\\"\\s*:\\s*\\{(.{0,1600}?)\\}");
        Matcher block = object.matcher(html);
        if (!block.find()) return "";
        return jsonValue(block.group(1), valueKey);
    }

    private static String address(String html) {
        String street = jsonValue(html, "streetAddress");
        String locality = jsonValue(html, "addressLocality");
        String region = jsonValue(html, "addressRegion");
        String postal = jsonValue(html, "postalCode");
        StringBuilder out = new StringBuilder();
        appendPart(out, street);
        appendPart(out, locality);
        appendPart(out, region);
        appendPart(out, postal);
        return out.toString();
    }

    private static String meta(String html, String name) {
        Pattern first = Pattern.compile(
                "(?is)<meta[^>]*(?:property|name)=\\\"" + Pattern.quote(name)
                        + "\\\"[^>]*content=\\\"([^\\\"]*)\\\"[^>]*>");
        Matcher a = first.matcher(html);
        if (a.find()) return htmlDecode(a.group(1));
        Pattern reversed = Pattern.compile(
                "(?is)<meta[^>]*content=\\\"([^\\\"]*)\\\"[^>]*(?:property|name)=\\\""
                        + Pattern.quote(name) + "\\\"[^>]*>");
        Matcher b = reversed.matcher(html);
        return b.find() ? htmlDecode(b.group(1)) : "";
    }

    private static String title(String html) {
        Matcher matcher = TITLE.matcher(html);
        return matcher.find() ? stripTags(htmlDecode(matcher.group(1))) : "";
    }

    private static String placeFromDescription(String description) {
        if (description == null) return "";
        Matcher in = Pattern.compile(
                "(?i)\\b(?:in|at)\\s+([A-Z][A-Za-z .'-]{2,45},\\s*[A-Z][A-Za-z .'-]{2,30})\\b")
                .matcher(description);
        return in.find() ? in.group(1).trim() : "";
    }

    private static int score(String eventName, String title, String url) {
        String event = normalize(eventName);
        String haystack = normalize(title + " " + url);
        int score = 0;
        for (String token : event.split(" ")) {
            if (token.length() < 3) continue;
            if (haystack.contains(token)) score += 2;
        }
        if (normalize(title).contains(event)) score += 8;
        if (url.toLowerCase(Locale.US).contains("official")) score += 2;
        if (url.startsWith("https://")) score += 1;
        return score;
    }

    private static boolean blocked(String url) {
        String lower = url.toLowerCase(Locale.US);
        return containsAny(lower,
                "facebook.com", "instagram.com", "tiktok.com", "x.com/", "twitter.com",
                "youtube.com", "reddit.com", "pinterest.com", "wikipedia.org",
                "eventbrite.com", "ticketmaster.com", "stubhub.com", "fandom.com");
    }

    private static String unwrap(String value) {
        String url = value == null ? "" : value.trim();
        try {
            if (url.startsWith("//")) url = "https:" + url;
            URI uri = URI.create(url);
            String query = uri.getRawQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    int split = pair.indexOf('=');
                    if (split < 0) continue;
                    if (pair.substring(0, split).equals("uddg")) {
                        return URLDecoder.decode(pair.substring(split + 1), "UTF-8");
                    }
                }
            }
        } catch (Exception ignored) { }
        return url;
    }

    private static String get(String url, String accept) throws Exception {
        if (url == null || !url.startsWith("https://")) {
            throw new SecurityException("Public event sources must use HTTPS.");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "SarahMorganTravel/1.6 (public event discovery)");
        connection.setRequestProperty("Accept", accept);
        int status = connection.getResponseCode();
        if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
            connection.disconnect();
            throw new SecurityException("Public event source redirected outside HTTPS.");
        }
        if (status < 200 || status >= 400) {
            connection.disconnect();
            throw new IllegalStateException("Public event source returned " + status);
        }
        try (InputStream in = connection.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                out.append(buffer, 0, count);
                if (out.length() > 2_500_000) break;
            }
            return out.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static String humanDates(String start, String end) {
        try {
            java.time.LocalDate a = java.time.LocalDate.parse(start);
            java.time.LocalDate b = java.time.LocalDate.parse(end == null || end.isEmpty() ? start : end);
            java.time.format.DateTimeFormatter full = java.time.format.DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US);
            if (a.equals(b)) return a.format(full);
            return a.format(full) + "–" + b.format(full);
        } catch (Exception ignored) {
            return start + (end == null || end.isEmpty() ? "" : " to " + end);
        }
    }

    private static String isoDate(String value) {
        String clean = value == null ? "" : value.trim();
        Matcher matcher = Pattern.compile("(20\\d{2}-\\d{2}-\\d{2})").matcher(clean);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String joinPlace(String locality, String region) {
        String a = locality == null ? "" : locality.trim();
        String b = region == null ? "" : region.trim();
        if (a.isEmpty()) return b;
        if (b.isEmpty() || a.equalsIgnoreCase(b)) return a;
        return a + ", " + b;
    }

    private static void appendPart(StringBuilder out, String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) return;
        if (out.length() > 0) out.append(", ");
        out.append(clean);
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "";
    }

    private static String stableKey(String value) {
        return normalize(value).replace(' ', '_');
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static String stripTags(String value) {
        return value == null ? "" : value.replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static String htmlDecode(String value) {
        return value == null ? "" : value
                .replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }

    private static String jsonDecode(String value) {
        return value == null ? "" : value.replace("\\/", "/")
                .replace("\\\"", "\"").replace("\\n", " ").replace("\\t", " ").trim();
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    private static final class Candidate {
        final String url;
        final String title;
        final int score;

        Candidate(String url, String title, int score) {
            this.url = url;
            this.title = title;
            this.score = score;
        }
    }

    private static final class PageFacts {
        boolean readable;
        String eventName = "";
        String destination = "";
        String venue = "";
        String address = "";
        String startDate = "";
        String endDate = "";
        String hours = "";
    }
}
