package com.kiraworld.sarahtravel;

public final class ProtectedBackendCapabilityPolicyTest {
    private static final String COMMIT = "7483a25612de66bc569c60e06b492f28fa99d413";
    private static final String SOURCE =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CONFIG =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String PROVIDER = "workers-ai";
    private static final String MODEL = "@cf/google/gemma-4-26b-a4b-it";

    public static void main(String[] args) {
        ProtectedBackendCapabilityPolicy.Health exact = health(
                COMMIT, SOURCE, CONFIG, true, true);

        ProtectedBackendCapabilityPolicy.Decision absentRoute = decide(
                "", "", COMMIT, SOURCE, CONFIG, exact);
        require(!absentRoute.routeConfigured && !absentRoute.contractVerified,
                "absent route must fail closed");

        ProtectedBackendCapabilityPolicy.Decision absentIdentity = decide(
                "https://sarah.example", "token", "", "", "", exact);
        require(absentIdentity.routeConfigured && !absentIdentity.identityBound,
                "configured route is not verified deployment identity");
        require(!absentIdentity.currentSourceReady && !absentIdentity.voiceReady,
                "unbound build cannot advertise protected capabilities");

        ProtectedBackendCapabilityPolicy.Decision mismatch = decide(
                "https://sarah.example", "token", COMMIT, SOURCE, CONFIG,
                health("different", SOURCE, CONFIG, true, true));
        require(mismatch.identityBound && !mismatch.contractVerified,
                "deployment mismatch must fail closed");

        ProtectedBackendCapabilityPolicy.Decision exactReady = decide(
                "https://sarah.example", "token", COMMIT, SOURCE, CONFIG, exact);
        require(exactReady.contractVerified, "exact contract and deployment verify");
        require(exactReady.currentSourceReady && exactReady.voiceReady,
                "verified health exposes exact optional capability truth");

        ProtectedBackendCapabilityPolicy.Health unauthorizedResponse =
                new ProtectedBackendCapabilityPolicy.Health(
                        false, true, exact.service, exact.contractVersion,
                        exact.deploymentReady, exact.deploymentId,
                        exact.sourceSha256, exact.configSha256,
                        exact.provider, exact.model, exact.routeRateLimitsReady,
                        exact.currentSourceReady, exact.voiceReady);
        ProtectedBackendCapabilityPolicy.Decision unauthorized = decide(
                "https://sarah.example", "wrong-token", COMMIT, SOURCE, CONFIG,
                unauthorizedResponse);
        require(!unauthorized.contractVerified
                        && !unauthorized.currentSourceReady
                        && !unauthorized.voiceReady,
                "HTTP 401/wrong-token response cannot create ready capability truth");

        ProtectedBackendCapabilityPolicy.Decision sourceUnavailable = decide(
                "https://sarah.example", "token", COMMIT, SOURCE, CONFIG,
                health(COMMIT, SOURCE, CONFIG, false, true));
        require(sourceUnavailable.contractVerified,
                "optional source absence does not falsify exact backend identity");
        require(!sourceUnavailable.currentSourceReady && sourceUnavailable.voiceReady,
                "source and voice readiness stay separate");

        ProtectedBackendCapabilityPolicy.Decision wrongModel = decide(
                "https://sarah.example", "token", COMMIT, SOURCE, CONFIG,
                health(COMMIT, SOURCE, CONFIG, PROVIDER, "other-model", true,
                        true, true));
        require(!wrongModel.contractVerified,
                "a health response for another model must fail closed");

        ProtectedBackendCapabilityPolicy.Decision wrongProvider = decide(
                "https://sarah.example", "token", COMMIT, SOURCE, CONFIG,
                health(COMMIT, SOURCE, CONFIG, "openai", MODEL, true,
                        true, true));
        require(!wrongProvider.contractVerified,
                "a health response for another provider must fail closed");

        ProtectedBackendCapabilityPolicy.Decision noRateLimits = decide(
                "https://sarah.example", "token", COMMIT, SOURCE, CONFIG,
                health(COMMIT, SOURCE, CONFIG, PROVIDER, MODEL, false,
                        true, true));
        require(!noRateLimits.contractVerified,
                "missing route rate limits must fail closed");

        ProtectedBackendCapabilityPolicy.Decision wrongCommit =
                ProtectedBackendCapabilityPolicy.evaluate(
                        "https://sarah.example", "token", "other-commit", COMMIT,
                        SOURCE, CONFIG, PROVIDER, MODEL, exact);
        require(!wrongCommit.identityBound && !wrongCommit.contractVerified,
                "APK commit must bind the expected unique deployment");

        System.out.println("ProtectedBackendCapabilityPolicyTest passed");
    }

    private static ProtectedBackendCapabilityPolicy.Decision decide(
            String url,
            String token,
            String deployment,
            String source,
            String config,
            ProtectedBackendCapabilityPolicy.Health health) {
        return ProtectedBackendCapabilityPolicy.evaluate(
                url, token, COMMIT, deployment, source, config, PROVIDER, MODEL, health);
    }

    private static ProtectedBackendCapabilityPolicy.Health health(
            String deployment,
            String source,
            String config,
            boolean currentSource,
            boolean voice) {
        return health(deployment, source, config, PROVIDER, MODEL, true,
                currentSource, voice);
    }

    private static ProtectedBackendCapabilityPolicy.Health health(
            String deployment,
            String source,
            String config,
            String provider,
            String model,
            boolean rateLimits,
            boolean currentSource,
            boolean voice) {
        return new ProtectedBackendCapabilityPolicy.Health(
                true, true, "sarah-model-proxy",
                "sarah-model-proxy-v2-workers-ai-voice", true,
                deployment, source, config, provider, model, rateLimits,
                currentSource, voice);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
