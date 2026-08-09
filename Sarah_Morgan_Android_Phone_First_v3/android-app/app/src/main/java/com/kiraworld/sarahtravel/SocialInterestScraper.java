package com.kiraworld.sarahtravel;

import java.util.List;

/**
 * Provider-neutral boundary for social-interest collection.
 *
 * Implementations belong outside Sarah's core and must only return data the
 * traveler has authorized the host application to access. This keeps Sarah
 * usable with an Instagram-approved backend, another social platform, or a
 * user-owned export without coupling her mind to one vendor or UI.
 */
public interface SocialInterestScraper {
    final class Request {
        public final String personScopeId;
        public final String platform;
        public final boolean userAuthorized;
        public final int maxSignals;

        public Request(String personScopeId, String platform, boolean userAuthorized, int maxSignals) {
            this.personScopeId = clean(personScopeId);
            this.platform = clean(platform);
            this.userAuthorized = userAuthorized;
            this.maxSignals = Math.max(1, Math.min(5000, maxSignals));
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }

    final class Result {
        public final String platform;
        public final List<SocialInterestSignal> signals;
        public final String provenance;
        public final long collectedAtMs;

        public Result(String platform, List<SocialInterestSignal> signals, String provenance, long collectedAtMs) {
            this.platform = platform == null ? "" : platform.trim();
            this.signals = signals;
            this.provenance = provenance == null ? "" : provenance.trim();
            this.collectedAtMs = Math.max(0L, collectedAtMs);
        }
    }

    /** Returns authorized social signals; implementations must reject requests without authorization. */
    Result scrape(Request request) throws Exception;
}
