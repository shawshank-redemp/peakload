package com.peakload.admission;

/** The order breaker is open: no call was made to the order service. */
final class CircuitOpenException extends RuntimeException {

    private final long retryAfterMillis;

    CircuitOpenException(long retryAfterMillis) {
        super("the order service is unavailable; the circuit is open");
        this.retryAfterMillis = retryAfterMillis;
    }

    long retryAfterMillis() {
        return retryAfterMillis;
    }
}
