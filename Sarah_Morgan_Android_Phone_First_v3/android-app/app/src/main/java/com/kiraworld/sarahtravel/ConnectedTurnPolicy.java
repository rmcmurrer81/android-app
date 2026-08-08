package com.kiraworld.sarahtravel;

/** Two short attempts share one strict owner-wait budget before local fallback. */
public final class ConnectedTurnPolicy {
    public static final int CONNECT_TIMEOUT_MS = 3_000;
    public static final int READ_TIMEOUT_MS = 4_000;
    public static final int ATTEMPTS_PER_TURN = 2;
    public static final int RETRY_BACKOFF_MS = 250;
    public static final int MAX_NETWORK_WAIT_MS =
            ATTEMPTS_PER_TURN * (CONNECT_TIMEOUT_MS + READ_TIMEOUT_MS)
                    + RETRY_BACKOFF_MS;

    private ConnectedTurnPolicy() { }

    public static boolean mayRetry(int completedAttempts, long remainingBudgetMs) {
        return completedAttempts < ATTEMPTS_PER_TURN
                && remainingBudgetMs > RETRY_BACKOFF_MS;
    }

    /** Monotonic remaining owner-visible wait; zero means fall back immediately. */
    public static long remainingBudgetMs(long startedAtNanos, long nowNanos) {
        long elapsedNanos = Math.max(0L, nowNanos - startedAtNanos);
        long elapsedMs = elapsedNanos / 1_000_000L;
        return Math.max(0L, MAX_NETWORK_WAIT_MS - elapsedMs);
    }
}
