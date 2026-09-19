package com.kiraworld.sarahtravel;

import java.util.Locale;

/** Pure fail-closed policy for claims about Sarah's protected online services. */
public final class ProtectedBackendCapabilityPolicy {
    public static final String CONTRACT_VERSION =
            "sarah-model-proxy-v2-workers-ai-voice";
    public static final String SERVICE = "sarah-model-proxy";

    public static final class Health {
        public final boolean httpOk;
        public final boolean ok;
        public final String service;
        public final String contractVersion;
        public final boolean deploymentReady;
        public final String deploymentId;
        public final String sourceSha256;
        public final String configSha256;
        public final String provider;
        public final String model;
        public final boolean routeRateLimitsReady;
        public final boolean currentSourceReady;
        public final boolean voiceReady;

        public Health(
                boolean httpOk,
                boolean ok,
                String service,
                String contractVersion,
                boolean deploymentReady,
                String deploymentId,
                String sourceSha256,
                String configSha256,
                String provider,
                String model,
                boolean routeRateLimitsReady,
                boolean currentSourceReady,
                boolean voiceReady) {
            this.httpOk = httpOk;
            this.ok = ok;
            this.service = clean(service);
            this.contractVersion = clean(contractVersion);
            this.deploymentReady = deploymentReady;
            this.deploymentId = clean(deploymentId);
            this.sourceSha256 = clean(sourceSha256).toLowerCase(Locale.US);
            this.configSha256 = clean(configSha256).toLowerCase(Locale.US);
            this.provider = clean(provider).toLowerCase(Locale.US);
            this.model = clean(model);
            this.routeRateLimitsReady = routeRateLimitsReady;
            this.currentSourceReady = currentSourceReady;
            this.voiceReady = voiceReady;
        }
    }

    public static final class Decision {
        public final boolean routeConfigured;
        public final boolean identityBound;
        public final boolean contractVerified;
        public final boolean currentSourceReady;
        public final boolean voiceReady;
        public final String reason;

        Decision(
                boolean routeConfigured,
                boolean identityBound,
                boolean contractVerified,
                boolean currentSourceReady,
                boolean voiceReady,
                String reason) {
            this.routeConfigured = routeConfigured;
            this.identityBound = identityBound;
            this.contractVerified = contractVerified;
            this.currentSourceReady = currentSourceReady;
            this.voiceReady = voiceReady;
            this.reason = reason;
        }
    }

    private ProtectedBackendCapabilityPolicy() { }

    public static Decision evaluate(
            String backendUrl,
            String backendToken,
            String buildCommit,
            String expectedDeploymentId,
            String expectedSourceSha256,
            String expectedConfigSha256,
            String expectedProvider,
            String expectedModel,
            Health health) {
        boolean route = clean(backendUrl).startsWith("https://")
                && !clean(backendToken).isEmpty();
        if (!route) return decision(false, false, false, false, false,
                "PROTECTED_ROUTE_NOT_CONFIGURED");

        String deployment = clean(expectedDeploymentId);
        String source = clean(expectedSourceSha256).toLowerCase(Locale.US);
        String config = clean(expectedConfigSha256).toLowerCase(Locale.US);
        String provider = clean(expectedProvider).toLowerCase(Locale.US);
        String model = clean(expectedModel);
        String commit = clean(buildCommit);
        boolean identity = !deployment.isEmpty()
                && sha256(source)
                && sha256(config)
                && !provider.isEmpty()
                && !model.isEmpty()
                && !commit.isEmpty()
                && deployment.equalsIgnoreCase(commit);
        if (!identity) return decision(true, false, false, false, false,
                "EXPECTED_DEPLOYMENT_IDENTITY_NOT_BOUND");
        if (health == null || !health.httpOk) {
            return decision(true, true, false, false, false,
                    "HEALTH_RESPONSE_NOT_AVAILABLE");
        }
        boolean verified = health.ok
                && SERVICE.equals(health.service)
                && CONTRACT_VERSION.equals(health.contractVersion)
                && health.deploymentReady
                && deployment.equalsIgnoreCase(health.deploymentId)
                && source.equals(health.sourceSha256)
                && config.equals(health.configSha256)
                && provider.equals(health.provider)
                && model.equals(health.model)
                && health.routeRateLimitsReady;
        if (!verified) return decision(true, true, false, false, false,
                "HEALTH_CONTRACT_OR_DEPLOYMENT_MISMATCH");
        return decision(
                true,
                true,
                true,
                health.currentSourceReady,
                health.voiceReady,
                health.currentSourceReady || health.voiceReady
                        ? "VERIFIED_CAPABILITIES"
                        : "VERIFIED_BACKEND_WITH_OPTIONAL_CAPABILITIES_UNAVAILABLE");
    }

    private static Decision decision(
            boolean route,
            boolean identity,
            boolean verified,
            boolean source,
            boolean voice,
            String reason) {
        return new Decision(route, identity, verified, source, voice, reason);
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
