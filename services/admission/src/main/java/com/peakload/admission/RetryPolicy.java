package com.peakload.admission;

import com.peakload.admission.LoadPolicy.LoadLevel;
import java.net.ConnectException;
import java.net.SocketException;
import java.util.Optional;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.springframework.http.HttpMethod;

/**
 * Which failures may be retried, and when. Pure functions, so the rules can be tested without a server.
 *
 * <p>The rules, in the order they are checked:
 * <ol>
 *   <li>Only GET, and POST /orders that carries an Idempotency-Key (the retry sends the same key). Never PATCH,
 *       never a POST without the key.</li>
 *   <li>Only failures where the upstream gave no answer: refused, reset, connect timeout, read timeout. Not 4xx,
 *       not an upstream 5xx, not a full pool (admission's own limit: nothing was sent).</li>
 *   <li>The load level must be NORMAL.</li>
 *   <li>The first attempt, the jitter, and one more read timeout must fit inside the request deadline.</li>
 *   <li>The retry budget must have room (checked by {@link RetryBudget}).</li>
 * </ol>
 */
final class RetryPolicy {

    private RetryPolicy() {}

    static final String ORDERS_PATH = "/orders";

    /** Rule 1. */
    static boolean eligible(HttpMethod method, String pathAndQuery, boolean hasIdempotencyKey) {
        if (method == HttpMethod.GET) {
            return true;
        }
        if (method == HttpMethod.POST) {
            String path = pathAndQuery.contains("?") ? pathAndQuery.substring(0, pathAndQuery.indexOf('?')) : pathAndQuery;
            return ORDERS_PATH.equals(path) && hasIdempotencyKey;
        }
        return false;
    }

    /**
     * Rule 2. The reason label for a retryable failure, or empty if this failure must not be retried.
     *
     * @param type  the admission's own classification of the failure
     * @param cause the exception at the bottom of the chain, if any
     */
    static Optional<String> retryableReason(UpstreamException.Type type, Throwable cause) {
        return switch (type) {
            case CONNECTION_REFUSED -> Optional.of("connection_refused");
            case CONNECT_TIMEOUT -> Optional.of("connect_timeout");
            case READ_TIMEOUT -> Optional.of("read_timeout");
            case POOL_TIMEOUT -> Optional.empty();
            case OTHER -> isReset(cause) ? Optional.of("connection_reset") : Optional.empty();
        };
    }

    /** A connection reset, or a connection closed before any response (the peer dropped it). */
    static boolean isReset(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof NoHttpResponseException) {
                return true;
            }
            if (t instanceof SocketException && t.getMessage() != null
                    && t.getMessage().toLowerCase().contains("reset")) {
                return true;
            }
            if (t instanceof ConnectException) {
                return false;
            }
        }
        return false;
    }

    /** Rule 3. */
    static boolean loadAllowsRetry(LoadLevel level) {
        return level == LoadLevel.NORMAL;
    }

    /**
     * Rule 4. A retry is started only if, after the elapsed time and the jitter, one more full read timeout still
     * fits before the deadline. So the second attempt cannot stretch the request past its deadline.
     */
    static boolean fitsDeadline(long elapsedNanos, long jitterNanos, long readTimeoutNanos, long deadlineNanos) {
        return elapsedNanos + jitterNanos + readTimeoutNanos <= deadlineNanos;
    }

    /** Whether a failure of this kind counts against the circuit breaker: timeouts and connection errors, not pool. */
    static boolean countsAsBreakerFailure(UpstreamException.Type type) {
        return type != UpstreamException.Type.POOL_TIMEOUT;
    }
}
