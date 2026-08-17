package com.kiraworld.sarahtravel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Bounded, user-initiated Stay22 Direct Travel API demo client.
 *
 * <p>No API key is embedded or transmitted. Results live only in the current
 * activity memory. The client requests one small page and never automatically
 * retries, paginates, stores listings, or treats a link as a booking.</p>
 */
public final class Stay22DirectClient {
    public static final class StayOffer {
        public final String title;
        public final String type;
        public final String provider;
        public final boolean hasQuotedTotal;
        public final double quotedTotal;
        public final String currency;
        public final String reviewUrl;
        public final boolean datedSearch;
        public final String checkedAtUtc;

        private StayOffer(
                String title,
                String type,
                String provider,
                boolean hasQuotedTotal,
                double quotedTotal,
                String currency,
                String reviewUrl,
                boolean datedSearch,
                String checkedAtUtc) {
            this.title = clean(title);
            this.type = clean(type);
            this.provider = clean(provider);
            this.hasQuotedTotal = hasQuotedTotal;
            this.quotedTotal = hasQuotedTotal ? quotedTotal : 0;
            this.currency = clean(currency).isEmpty() ? "USD" : clean(currency);
            this.reviewUrl = clean(reviewUrl);
            this.datedSearch = datedSearch;
            this.checkedAtUtc = clean(checkedAtUtc);
        }
    }

    public static final class SearchResult {
        public final List<StayOffer> offers;
        public final boolean datedSearch;
        public final String checkedAtUtc;
        public final int rateLimitRemaining;

        private SearchResult(
                List<StayOffer> offers,
                boolean datedSearch,
                String checkedAtUtc,
                int rateLimitRemaining) {
            this.offers = Collections.unmodifiableList(new ArrayList<>(offers));
            this.datedSearch = datedSearch;
            this.checkedAtUtc = checkedAtUtc;
            this.rateLimitRemaining = rateLimitRemaining;
        }
    }

    public static final class RateLimitException extends Exception {
        public final long retryAfterSeconds;

        private RateLimitException(long retryAfterSeconds) {
            super("Stay22 keyless demo rate limit reached");
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private Stay22DirectClient() { }

    public static SearchResult search(
            String destination,
            String checkIn,
            String checkOut,
            int adults,
            int rooms) throws Exception {
        Stay22SearchPolicy.RequestSpec request = Stay22SearchPolicy.prepare(
                destination, checkIn, checkOut, adults, rooms);
        HttpURLConnection connection = (HttpURLConnection) new URL(request.url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "SarahTravelOS/2.5 Stay22KeylessDemo");
        // Intentionally no X-API-KEY: this is Stay22's documented keyless demo.

        try {
            int status = connection.getResponseCode();
            if (status == 429) {
                long wait = Stay22SearchPolicy.retryDelaySeconds(
                        connection.getHeaderField("Retry-After"),
                        connection.getHeaderField("X-RateLimit-Reset"),
                        System.currentTimeMillis());
                throw new RateLimitException(wait);
            }
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Stay22 demo returned HTTP " + status);
            }

            String checkedAt = Instant.now().toString();
            JSONObject root = new JSONObject(read(connection.getInputStream()));
            JSONObject meta = root.optJSONObject("meta");
            String currency = meta == null ? "USD" : meta.optString("currency", "USD");
            List<StayOffer> offers = parseOffers(
                    root.optJSONArray("results"), currency, request.dated, checkedAt);
            return new SearchResult(
                    offers,
                    request.dated,
                    checkedAt,
                    integerHeader(connection.getHeaderField("X-RateLimit-Remaining"), -1));
        } finally {
            connection.disconnect();
        }
    }

    private static List<StayOffer> parseOffers(
            JSONArray rows,
            String currency,
            boolean dated,
            String checkedAt) {
        List<StayOffer> result = new ArrayList<>();
        if (rows == null) return result;
        for (int i = 0; i < rows.length() && result.size() < Stay22SearchPolicy.PAGE_SIZE; i++) {
            JSONObject stay = rows.optJSONObject(i);
            if (stay == null) continue;
            String title = stay.optString("name", "").trim();
            if (title.isEmpty()) continue;

            SupplierChoice choice = chooseSupplier(stay.optJSONObject("suppliers"), dated);
            String reviewUrl = choice.reviewUrl;
            if (reviewUrl.isEmpty()) reviewUrl = safeStay22Url(stay.optString("url", ""));
            result.add(new StayOffer(
                    title,
                    stay.optString("type", ""),
                    choice.provider.isEmpty() ? "Stay22" : choice.provider,
                    choice.hasQuotedTotal,
                    choice.quotedTotal,
                    currency,
                    reviewUrl,
                    dated,
                    checkedAt));
        }
        return result;
    }

    private static SupplierChoice chooseSupplier(JSONObject suppliers, boolean dated) {
        SupplierChoice best = new SupplierChoice();
        if (suppliers == null) return best;
        List<String> names = new ArrayList<>();
        Iterator<String> keys = suppliers.keys();
        while (keys.hasNext()) names.add(keys.next());
        Collections.sort(names);

        for (String name : names) {
            JSONObject supplier = suppliers.optJSONObject(name);
            if (supplier == null) continue;
            String link = safeStay22Url(supplier.optString("link", ""));
            JSONObject price = supplier.optJSONObject("price");
            double total = price == null ? Double.NaN : price.optDouble("total", Double.NaN);
            boolean quoted = dated && Double.isFinite(total) && total > 0;

            if (quoted && (!best.hasQuotedTotal || total < best.quotedTotal)) {
                best = new SupplierChoice(name, link, true, total);
            } else if (!best.hasQuotedTotal && best.provider.isEmpty() && !link.isEmpty()) {
                best = new SupplierChoice(name, link, false, 0);
            }
        }
        return best;
    }

    private static String safeStay22Url(String value) {
        String candidate = clean(value);
        if (candidate.isEmpty()) return "";
        try {
            URI uri = URI.create(candidate);
            String host = clean(uri.getHost()).toLowerCase();
            if (!"https".equalsIgnoreCase(uri.getScheme())) return "";
            if (!host.equals("stay22.com") && !host.endsWith(".stay22.com")) return "";
            return candidate;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                out.append(buffer, 0, count);
                if (out.length() > 2_000_000) {
                    throw new IllegalStateException("Stay22 demo response exceeded the safe limit");
                }
            }
        }
        return out.toString();
    }

    private static int integerHeader(String value, int fallback) {
        try { return Integer.parseInt(clean(value)); }
        catch (Exception ignored) { return fallback; }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class SupplierChoice {
        final String provider;
        final String reviewUrl;
        final boolean hasQuotedTotal;
        final double quotedTotal;

        SupplierChoice() {
            this("", "", false, 0);
        }

        SupplierChoice(String provider, String reviewUrl, boolean hasQuotedTotal, double quotedTotal) {
            this.provider = clean(provider);
            this.reviewUrl = clean(reviewUrl);
            this.hasQuotedTotal = hasQuotedTotal;
            this.quotedTotal = hasQuotedTotal ? quotedTotal : 0;
        }
    }
}
