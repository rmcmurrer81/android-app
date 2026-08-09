package com.kiraworld.sarahtravel;

import java.util.Arrays;
import java.util.List;

public final class SocialInterestAnalyzerTest {
    public static void main(String[] args) {
        List<SocialInterestSignal> signals = Arrays.asList(
                new SocialInterestSignal("instagram", SocialInterestSignal.Action.LIKE,
                        Arrays.asList("Power Rangers", "filming locations"), "post-1", 1L),
                new SocialInterestSignal("instagram", SocialInterestSignal.Action.SAVE,
                        Arrays.asList("Power Rangers", "Auckland"), "post-2", 2L),
                new SocialInterestSignal("youtube", SocialInterestSignal.Action.FOLLOW,
                        Arrays.asList("Power Rangers"), "channel-1", 3L),
                new SocialInterestSignal("instagram", SocialInterestSignal.Action.LIKE,
                        Arrays.asList("baseball"), "post-3", 4L));

        List<SocialInterestAnalyzer.Interest> interests = SocialInterestAnalyzer.analyze(signals, 10);
        require(!interests.isEmpty(), "interests required");
        require("Power Rangers".equals(interests.get(0).topic),
                "repeated cross-source interest should rank first");
        require(interests.get(0).confidence > interests.get(interests.size() - 1).confidence,
                "repetition and stronger actions must increase confidence");

        String packed = SocialInterestAnalyzer.packLearnedInterests(interests, 0.58);
        require(packed.contains("Power Rangers"), "high-confidence interest should be retained");
        require(!packed.contains("baseball"), "one weak like should not become durable interest");

        SocialInterestScraper.Request blocked = new SocialInterestScraper.Request(
                "person-1", "instagram", false, 100);
        require(!blocked.userAuthorized, "authorization flag must remain explicit");
        require(blocked.maxSignals == 100, "signal bound should be preserved");

        System.out.println("SocialInterestAnalyzerTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
