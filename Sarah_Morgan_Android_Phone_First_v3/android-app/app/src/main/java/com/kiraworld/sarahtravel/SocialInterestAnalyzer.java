package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Converts repeated social signals into explainable, confidence-scored interests. */
public final class SocialInterestAnalyzer {
    public static final class Interest {
        public final String topic;
        public final double confidence;
        public final int evidenceCount;
        public final List<String> sources;

        Interest(String topic, double confidence, int evidenceCount, List<String> sources) {
            this.topic = topic;
            this.confidence = confidence;
            this.evidenceCount = evidenceCount;
            this.sources = Collections.unmodifiableList(new ArrayList<>(sources));
        }
    }

    private static final class Accumulator {
        String display;
        double score;
        int count;
        final List<String> sources = new ArrayList<>();
    }

    private SocialInterestAnalyzer() { }

    public static List<Interest> analyze(List<SocialInterestSignal> signals, int limit) {
        Map<String, Accumulator> byTopic = new LinkedHashMap<>();
        if (signals != null) {
            for (SocialInterestSignal signal : signals) {
                if (signal == null) continue;
                double weight = actionWeight(signal.action);
                for (String topic : signal.topics) {
                    String key = topic.toLowerCase(Locale.US);
                    Accumulator a = byTopic.get(key);
                    if (a == null) {
                        a = new Accumulator();
                        a.display = topic;
                        byTopic.put(key, a);
                    }
                    a.score += weight;
                    a.count++;
                    if (!signal.source.isEmpty() && !containsIgnoreCase(a.sources, signal.source)) {
                        a.sources.add(signal.source);
                    }
                }
            }
        }

        List<Interest> out = new ArrayList<>();
        for (Accumulator a : byTopic.values()) {
            // Repetition matters more than one accidental like. Confidence rises smoothly
            // and does not reach 1.0 from inferred social activity alone.
            double repetition = 1.0 - Math.exp(-a.score / 4.0);
            double evidenceBoost = Math.min(0.12, Math.max(0, a.count - 1) * 0.02);
            double confidence = Math.min(0.95, 0.20 + 0.70 * repetition + evidenceBoost);
            out.add(new Interest(a.display, round(confidence), a.count, a.sources));
        }
        out.sort(Comparator.comparingDouble((Interest i) -> i.confidence).reversed()
                .thenComparingInt(i -> -i.evidenceCount)
                .thenComparing(i -> i.topic.toLowerCase(Locale.US)));
        int max = Math.max(0, limit);
        return out.size() <= max ? out : new ArrayList<>(out.subList(0, max));
    }

    public static String packLearnedInterests(List<Interest> interests, double minimumConfidence) {
        List<String> accepted = new ArrayList<>();
        if (interests != null) {
            for (Interest interest : interests) {
                if (interest != null && interest.confidence >= minimumConfidence) accepted.add(interest.topic);
            }
        }
        return String.join("; ", accepted);
    }

    private static double actionWeight(SocialInterestSignal.Action action) {
        if (action == null) return 0.35;
        switch (action) {
            case EXPLICIT: return 3.0;
            case FOLLOW: return 2.1;
            case SAVE: return 1.8;
            case SHARE: return 1.6;
            case COMMENT: return 1.4;
            case POST: return 1.3;
            case LIKE: return 1.0;
            case VIEW:
            default: return 0.35;
        }
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        for (String existing : values) {
            if (existing.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
