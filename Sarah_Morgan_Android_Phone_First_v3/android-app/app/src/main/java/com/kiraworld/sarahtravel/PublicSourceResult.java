package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure, fail-closed result for a bounded public-source lookup. */
public final class PublicSourceResult {
    public final String reply;
    public final boolean verified;
    public final List<String> sourceUrls;

    private PublicSourceResult(String reply, boolean verified, List<String> sourceUrls) {
        this.reply = clean(reply);
        List<String> exact = new ArrayList<>();
        if (sourceUrls != null) {
            for (String raw : sourceUrls) {
                String url = clean(raw);
                if (url.startsWith("https://") && !exact.contains(url)) exact.add(url);
            }
        }
        this.verified = verified && !exact.isEmpty();
        this.sourceUrls = Collections.unmodifiableList(exact);
    }

    public static PublicSourceResult verified(String reply, String sourceUrl) {
        return new PublicSourceResult(reply, true, Collections.singletonList(sourceUrl));
    }

    public static PublicSourceResult unavailable(String reply) {
        return new PublicSourceResult(reply, false, Collections.emptyList());
    }

    public String turnRoute() {
        return verified ? TurnRoute.PUBLIC_SOURCE_TOOL_RESULT : TurnRoute.TOOL_UNAVAILABLE;
    }

    public String ownerSourceDetails() {
        if (verified) {
            return "Verified public source used for this reply:\n" + String.join("\n", sourceUrls);
        }
        return "A public-source lookup was attempted, but no exact verified source receipt was returned. Sarah did not claim a successful lookup.";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
