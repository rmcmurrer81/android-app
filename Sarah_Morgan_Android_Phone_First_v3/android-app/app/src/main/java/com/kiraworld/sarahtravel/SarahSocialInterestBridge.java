package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Connects an authorized social-interest provider to Sarah's existing profile memory. */
public final class SarahSocialInterestBridge {
    public static final double DEFAULT_MEMORY_THRESHOLD = 0.58;

    private final PersonProfileStore profiles;

    public SarahSocialInterestBridge(PersonProfileStore profiles) {
        if (profiles == null) throw new IllegalArgumentException("profiles required");
        this.profiles = profiles;
    }

    /**
     * Learns interests for exactly one profile. Nothing is persisted when that
     * person's memory consent is absent or disabled.
     */
    public List<SocialInterestAnalyzer.Interest> ingest(
            String personName,
            SocialInterestScraper.Result result,
            int maxInterests) {
        Map<String, String> person = profiles.findByName(personName);
        if (person.isEmpty() || !"yes".equals(person.getOrDefault("memory_consent", "no"))) {
            return new ArrayList<>();
        }
        List<SocialInterestSignal> signals = result == null ? null : result.signals;
        List<SocialInterestAnalyzer.Interest> interests =
                SocialInterestAnalyzer.analyze(signals, Math.max(1, maxInterests));
        for (SocialInterestAnalyzer.Interest interest : interests) {
            if (interest.confidence < DEFAULT_MEMORY_THRESHOLD) continue;
            String source = result == null || result.platform.isEmpty() ? "social" : result.platform;
            String evidence = "Learned from authorized " + source
                    + " activity; confidence=" + interest.confidence
                    + "; evidence_count=" + interest.evidenceCount;
            profiles.addMemory(
                    personName,
                    "profile_interest",
                    "Enjoys " + interest.topic,
                    evidence);
        }
        return interests;
    }

    /** Produces a UI-neutral compact summary for travel-ranking or another interface. */
    public static String travelInterestSummary(List<SocialInterestAnalyzer.Interest> interests) {
        return SocialInterestAnalyzer.packLearnedInterests(interests, DEFAULT_MEMORY_THRESHOLD);
    }
}
