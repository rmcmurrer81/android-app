package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.List;

/** Pure fail-closed rules for persisting a source-backed destination pack. */
public final class DestinationSourcePolicy {
    private DestinationSourcePolicy() { }

    public static boolean canPersistReadyPack(List<String> urls, long sourceTime) {
        return sourceTime > 0 && !verifiedUrls(urls).isEmpty();
    }

    public static String receipt(List<String> urls, long sourceTime) {
        List<String> verified = verifiedUrls(urls);
        if (sourceTime <= 0 || verified.isEmpty()) return "NO_VERIFIED_PUBLIC_SOURCE_RECEIPT";
        return "Tavily public-source receipt at " + sourceTime + " · "
                + String.join(" | ", verified);
    }

    private static List<String> verifiedUrls(List<String> urls) {
        List<String> verified = new ArrayList<>();
        if (urls == null) return verified;
        for (String raw : urls) {
            String url = raw == null ? "" : raw.trim();
            if (url.startsWith("https://") && !verified.contains(url)) verified.add(url);
        }
        return verified;
    }
}
