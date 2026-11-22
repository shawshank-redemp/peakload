package com.peakload.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.peakload.admission.LoadPolicy.LoadLevel;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import org.apache.hc.core5.http.NoHttpResponseException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class RetryPolicyTest {

    @Test
    void getIsEligible() {
        assertTrue(RetryPolicy.eligible(HttpMethod.GET, "/orders/o-1", false));
        assertTrue(RetryPolicy.eligible(HttpMethod.GET, "/orders/o-1", true));
    }

    @Test
    void postToOrdersIsEligibleOnlyWithAnIdempotencyKey() {
        assertTrue(RetryPolicy.eligible(HttpMethod.POST, "/orders", true));
        assertFalse(RetryPolicy.eligible(HttpMethod.POST, "/orders", false), "keyless POST is never retried");
        assertFalse(RetryPolicy.eligible(HttpMethod.POST, "/orders/o-1/cancel", true),
                "other POST routes are not on the retry list");
    }

    @Test
    void patchIsNeverEligible() {
        assertFalse(RetryPolicy.eligible(HttpMethod.PATCH, "/orders/o-1", true));
        assertFalse(RetryPolicy.eligible(HttpMethod.PATCH, "/orders/o-1", false));
    }

    @Test
    void connectionLevelFailuresAreRetryable() {
        assertEquals("connection_refused",
                RetryPolicy.retryableReason(UpstreamException.Type.CONNECTION_REFUSED, null).orElseThrow());
        assertEquals("connect_timeout",
                RetryPolicy.retryableReason(UpstreamException.Type.CONNECT_TIMEOUT, null).orElseThrow());
        assertEquals("read_timeout",
                RetryPolicy.retryableReason(UpstreamException.Type.READ_TIMEOUT, null).orElseThrow());
    }

    @Test
    void resetIsRetryableOnlyWhenTheCauseSaysSo() {
        assertEquals("connection_reset", RetryPolicy.retryableReason(UpstreamException.Type.OTHER,
                new NoHttpResponseException("closed before response")).orElseThrow());
        assertEquals("connection_reset", RetryPolicy.retryableReason(UpstreamException.Type.OTHER,
                new SocketException("Connection reset")).orElseThrow());
        assertFalse(RetryPolicy.retryableReason(UpstreamException.Type.OTHER,
                new IllegalStateException("something else")).isPresent());
    }

    @Test
    void poolTimeoutIsNeverRetried() {
        assertFalse(RetryPolicy.retryableReason(UpstreamException.Type.POOL_TIMEOUT, null).isPresent(),
                "admission's own pool was full: nothing was sent");
    }

    @Test
    void resetDetectionDoesNotSeeAConnectionRefusedAsAReset() {
        assertFalse(RetryPolicy.isReset(new ConnectException("Connection refused")));
        assertFalse(RetryPolicy.isReset(new SocketTimeoutException("Read timed out")));
    }

    @Test
    void retriesOnlyAtNormalLoad() {
        assertTrue(RetryPolicy.loadAllowsRetry(LoadLevel.NORMAL));
        assertFalse(RetryPolicy.loadAllowsRetry(LoadLevel.ELEVATED));
        assertFalse(RetryPolicy.loadAllowsRetry(LoadLevel.HIGH));
        assertFalse(RetryPolicy.loadAllowsRetry(LoadLevel.HARD_CAP));
    }

    @Test
    void theRetryMustFitInsideTheDeadlineWithOneMoreReadTimeout() {
        long ms = 1_000_000L;
        // deadline 3 s, read timeout 2 s, jitter up to 100 ms
        assertTrue(RetryPolicy.fitsDeadline(10 * ms, 100 * ms, 2000 * ms, 3000 * ms), "connect failure at 10 ms fits");
        assertFalse(RetryPolicy.fitsDeadline(2000 * ms, 0, 2000 * ms, 3000 * ms),
                "after a 2 s read timeout no second 2 s attempt fits under 3 s");
        assertFalse(RetryPolicy.fitsDeadline(900 * ms, 100 * ms, 2000 * ms, 2999 * ms),
                "boundary: 900 + 100 + 2000 is exactly 3000, which is over 2999");
    }
}
