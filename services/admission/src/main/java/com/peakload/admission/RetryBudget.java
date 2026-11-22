package com.peakload.admission;

import java.util.function.LongSupplier;

/**
 * Caps retries as a share of recent order calls. Rolling window split into buckets, so old counts drop out
 * without a timer. A retry is allowed only while (retries in the window + 1) is at most share x calls in the window.
 *
 * <p>Thread-safe through synchronized methods. Time comes from an injected clock, so tests can move time.
 */
final class RetryBudget {

    private static final int BUCKETS = 10;

    private final double share;
    private final long bucketNanos;
    private final LongSupplier nanoClock;
    private final long[] calls = new long[BUCKETS];
    private final long[] retries = new long[BUCKETS];
    private long currentBucket;

    RetryBudget(double share, java.time.Duration window, LongSupplier nanoClock) {
        this.share = share;
        this.bucketNanos = Math.max(1, window.toNanos() / BUCKETS);
        this.nanoClock = nanoClock;
        this.currentBucket = nanoClock.getAsLong() / bucketNanos;
    }

    /** Counts one order call (a first attempt, not a retry). */
    synchronized void recordCall() {
        advance();
        calls[index()]++;
    }

    /** Takes a retry from the budget if one is available. Returns false if the budget is used up. */
    synchronized boolean tryRetry() {
        advance();
        long callsInWindow = sum(calls);
        long retriesInWindow = sum(retries);
        if (retriesInWindow + 1 > share * callsInWindow) {
            return false;
        }
        retries[index()]++;
        return true;
    }

    private void advance() {
        long now = nanoClock.getAsLong() / bucketNanos;
        if (now == currentBucket) {
            return;
        }
        long steps = Math.min(BUCKETS, now - currentBucket);
        for (long i = 1; i <= steps; i++) {
            int slot = (int) ((currentBucket + i) % BUCKETS);
            calls[slot] = 0;
            retries[slot] = 0;
        }
        currentBucket = now;
    }

    private int index() {
        return (int) (currentBucket % BUCKETS);
    }

    private static long sum(long[] values) {
        long total = 0;
        for (long v : values) {
            total += v;
        }
        return total;
    }
}
