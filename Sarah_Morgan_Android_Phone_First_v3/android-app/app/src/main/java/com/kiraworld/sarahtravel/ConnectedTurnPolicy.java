package com.kiraworld.sarahtravel;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.SSLHandshakeException;

/** One useful connected attempt plus one fast-failure retry share a strict 15-second budget. */
public final class ConnectedTurnPolicy {
    public static final int CONNECT_TIMEOUT_MS = 3_000;
    public static final int READ_TIMEOUT_MS = 11_500;
    public static final int ATTEMPTS_PER_TURN = 2;
    public static final int RETRY_BACKOFF_MS = 250;
    public static final int MIN_SECOND_ATTEMPT_BUDGET_MS = 4_000;
    public static final int MAX_NETWORK_WAIT_MS = 15_000;

    private ConnectedTurnPolicy() { }

    public static boolean mayRetry(int completedAttempts, long remainingBudgetMs) {
        return completedAttempts < ATTEMPTS_PER_TURN
                && remainingBudgetMs >= RETRY_BACKOFF_MS + MIN_SECOND_ATTEMPT_BUDGET_MS;
    }

    /** Only transient transport/status failures may consume the one bounded retry. */
    public static boolean mayRetry(
            int completedAttempts,
            long remainingBudgetMs,
            Throwable failure) {
        return mayRetry(completedAttempts, remainingBudgetMs)
                && isRetryableFailure(failure);
    }

    public static boolean isRetryableFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof HttpStatusException) {
                int status = ((HttpStatusException) current).statusCode;
                return status == 404 || status == 408 || status == 429 || status >= 500;
            }
            if (current instanceof SSLHandshakeException) return false;
            if (current instanceof IOException) return true;
            current = current.getCause();
        }
        return false;
    }

    /** Socket connect timeout for this attempt; connect plus read never exceeds its budget. */
    public static int connectTimeoutMs(long remainingBudgetMs) {
        long bounded = Math.max(2L, Math.min((long) MAX_NETWORK_WAIT_MS, remainingBudgetMs));
        return (int) Math.min((long) CONNECT_TIMEOUT_MS, bounded - 1L);
    }

    /** A normal Gemma reply gets a useful read window; a retry receives only what remains. */
    public static int readTimeoutMs(long remainingBudgetMs) {
        long bounded = Math.max(2L, Math.min((long) MAX_NETWORK_WAIT_MS, remainingBudgetMs));
        long afterConnect = Math.max(1L, bounded - connectTimeoutMs(bounded));
        return (int) Math.min((long) READ_TIMEOUT_MS, afterConnect);
    }

    /** Preserve owner route query/fragment while replacing reserved per-attempt fields. */
    static String endpointForAttempt(String endpoint, int attemptNumber) {
        return endpointForAttempt(endpoint, attemptNumber, UUID.randomUUID().toString());
    }

    /** Deterministic seam used by the pure-Java release test. */
    static String endpointForAttempt(String endpoint, int attemptNumber, String nonce) {
        String exact = endpoint == null ? "" : endpoint.trim();
        String exactNonce = nonce == null ? "" : nonce.trim();
        if (exact.isEmpty() || exactNonce.isEmpty()) {
            throw new IllegalArgumentException("Connected endpoint and attempt nonce are required");
        }
        int fragmentIndex = exact.indexOf('#');
        String fragment = fragmentIndex >= 0 ? exact.substring(fragmentIndex) : "";
        String beforeFragment = fragmentIndex >= 0 ? exact.substring(0, fragmentIndex) : exact;
        int queryIndex = beforeFragment.indexOf('?');
        String base = queryIndex >= 0 ? beforeFragment.substring(0, queryIndex) : beforeFragment;
        String existingQuery = queryIndex >= 0 ? beforeFragment.substring(queryIndex + 1) : "";
        List<String> queryParts = new ArrayList<>();
        if (!existingQuery.isEmpty()) {
            for (String part : existingQuery.split("&", -1)) {
                if (part.isEmpty()) continue;
                String rawKey = part.contains("=")
                        ? part.substring(0, part.indexOf('=')) : part;
                String decodedKey = decodeQueryPart(rawKey);
                if ("sarah_attempt".equals(decodedKey) || "sarah_nonce".equals(decodedKey)) {
                    continue;
                }
                queryParts.add(part);
            }
        }
        queryParts.add("sarah_attempt=" + Math.max(1, attemptNumber));
        queryParts.add("sarah_nonce=" + encodeQueryPart(exactNonce));
        return base + "?" + String.join("&", queryParts) + fragment;
    }

    private static String decodeQueryPart(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 is unavailable", impossible);
        }
    }

    private static String encodeQueryPart(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 is unavailable", impossible);
        }
    }

    /** Monotonic remaining owner-visible wait; zero means fall back immediately. */
    public static long remainingBudgetMs(long startedAtNanos, long nowNanos) {
        return remainingUntilDeadlineMs(deadlineNanos(startedAtNanos), nowNanos);
    }

    /** Absolute monotonic deadline survives executor and GC delays between call sites. */
    public static long deadlineNanos(long startedAtNanos) {
        long budgetNanos = MAX_NETWORK_WAIT_MS * 1_000_000L;
        return startedAtNanos > Long.MAX_VALUE - budgetNanos
                ? Long.MAX_VALUE : startedAtNanos + budgetNanos;
    }

    /** Fresh remaining budget for socket configuration or Future.get at its exact boundary. */
    public static long remainingUntilDeadlineMs(long deadlineNanos, long nowNanos) {
        if (nowNanos >= deadlineNanos) return 0L;
        return Math.max(0L, (deadlineNanos - nowNanos) / 1_000_000L);
    }

    /** Typed HTTP failure keeps retry classification out of user-facing message parsing. */
    public static final class HttpStatusException extends IllegalStateException {
        public final int statusCode;

        public HttpStatusException(String service, int statusCode, String detail) {
            super(service + " returned HTTP " + statusCode
                    + (detail == null || detail.isEmpty() ? "" : ": " + detail));
            this.statusCode = statusCode;
        }
    }
}
