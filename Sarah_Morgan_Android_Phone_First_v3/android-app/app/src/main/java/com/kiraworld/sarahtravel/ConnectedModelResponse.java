package com.kiraworld.sarahtravel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Auditable connected-model result; current-source truth is never inferred from prose. */
public final class ConnectedModelResponse {
    public final String reply;
    public final String provider;
    public final String model;
    public final boolean online;
    public final boolean webSearchRequested;
    public final boolean webSearchApplied;
    public final List<String> sourceUrls;
    public final long receivedAt;
    public final long requestStartedAt;
    public final long responseCompletedAt;

    public ConnectedModelResponse(
            String reply,
            String provider,
            String model,
            boolean online,
            boolean webSearchRequested,
            boolean webSearchApplied,
            List<String> sourceUrls,
            long receivedAt) {
        this(reply, provider, model, online, webSearchRequested, webSearchApplied,
                sourceUrls, receivedAt, receivedAt);
    }

    public ConnectedModelResponse(
            String reply,
            String provider,
            String model,
            boolean online,
            boolean webSearchRequested,
            boolean webSearchApplied,
            List<String> sourceUrls,
            long requestStartedAt,
            long responseCompletedAt) {
        this.reply = clean(reply);
        this.provider = clean(provider);
        this.model = clean(model);
        this.online = online;
        this.webSearchRequested = webSearchRequested;
        this.webSearchApplied = webSearchApplied;
        List<String> verified = new ArrayList<>();
        if (sourceUrls != null) {
            for (String sourceUrl : sourceUrls) {
                String url = clean(sourceUrl);
                if (url.startsWith("https://") && !verified.contains(url)) verified.add(url);
            }
        }
        this.sourceUrls = Collections.unmodifiableList(verified);
        this.requestStartedAt = requestStartedAt;
        this.responseCompletedAt = responseCompletedAt;
        this.receivedAt = responseCompletedAt;
    }

    public boolean hasVerifiedWebReceipt() {
        return online && webSearchRequested && webSearchApplied && !sourceUrls.isEmpty();
    }

    public boolean hasSourceUrl(String url) {
        String expected = clean(url);
        for (String sourceUrl : sourceUrls) {
            if (sourceUrl.equals(expected)) return true;
        }
        return false;
    }

    public String sourceReceipt() {
        if (!hasVerifiedWebReceipt()) return "NO_VERIFIED_LIVE_WEB_RECEIPT";
        return "Verified connected web receipt at " + receivedAt + " · "
                + String.join(" | ", sourceUrls);
    }

    public String ownerSourceDetails() {
        if (hasVerifiedWebReceipt()) {
            return "Verified current sources used for this reply:\n"
                    + String.join("\n", sourceUrls);
        }
        if (webSearchRequested) {
            return "A current-source lookup was requested, but no verified source receipt was returned. Sarah withheld unsupported current claims.";
        }
        return online
                ? "This was a connected conversation reply. No current-source lookup was requested for this turn."
                : "This response did not use the connected conversation route.";
    }

    public String turnRoute() {
        return online ? TurnRoute.connectedRoute(provider) : TurnRoute.UNKNOWN_LEGACY;
    }

    public String auditFact() {
        return auditFact(requestStartedAt);
    }

    public String auditFact(long turnSubmittedAt) {
        StringBuilder fact = new StringBuilder("Connected model receipt: provider=")
                .append(provider.isEmpty() ? "unknown" : provider)
                .append("; model=").append(model.isEmpty() ? "unknown" : model)
                .append("; online=").append(online)
                .append("; web_search_requested=").append(webSearchRequested)
                .append("; web_search_applied=").append(webSearchApplied)
                .append("; turn_submitted_at=").append(turnSubmittedAt)
                .append("; request_started_at=").append(requestStartedAt)
                .append("; first_token_at=UNAVAILABLE_NON_STREAMING")
                .append("; text_completed_at=").append(responseCompletedAt)
                .append("; text_latency_ms=").append(Math.max(0L, responseCompletedAt - turnSubmittedAt))
                .append("; request_duration_ms=").append(Math.max(0L, responseCompletedAt - requestStartedAt))
                .append('.');
        if (hasVerifiedWebReceipt()) fact.append(' ').append(sourceReceipt()).append('.');
        else if (webSearchRequested) fact.append(" No verified live-web source receipt was returned.");
        return fact.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
