package com.peakload.admission;

import com.peakload.admission.LoadPolicy.LoadLevel;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Forwards a request to the upstream unchanged, except for the headers listed here, and returns the upstream
 * answer unchanged: status, body and the listed response headers.
 *
 * <p>An upstream 4xx or 5xx is an answer, not a failure: it is returned as is. Only "no answer" (timeouts,
 * refused connections, a full pool) becomes an {@link UpstreamException}.
 *
 * <p>Week 5: one retry for the cases in {@link RetryPolicy}, and a circuit breaker around every order call. The
 * breaker counts timeouts, connection errors and upstream 502/503/504 as failures. A call that is not allowed
 * (breaker open) throws {@link CircuitOpenException} and makes no upstream call.
 */
@Service
class ProxyService {

    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);

    /** Request headers sent upstream. Anything else from the client is dropped. */
    static final Set<String> FORWARDED_REQUEST_HEADERS = Set.of(
            "Idempotency-Key", "X-Client-Id", "If-Match", "Content-Type", "Accept");

    /** Response headers returned to the client. Idempotent-Replay tells the client a stored answer was replayed. */
    static final Set<String> FORWARDED_RESPONSE_HEADERS = Set.of(
            "Content-Type", "Idempotent-Replay", "Location", "ETag");

    private final RestClient upstream;
    private final AdmissionMetrics metrics;
    private final UpstreamLatency latency;
    private final LoadController load;
    private final ResilienceProperties resilience;
    private final long readTimeoutNanos;
    private final CircuitBreaker breaker;
    private final RetryBudget budget;
    private final ThrottledLog otherFailureLog;

    ProxyService(RestClient upstreamRestClient, AdmissionMetrics metrics, UpstreamLatency latency,
                 LoadController load, AdmissionProperties admission, ResilienceProperties resilience,
                 @Value("${peakload.admission.resilience.log-interval:PT10S}") java.time.Duration logInterval) {
        this.upstream = upstreamRestClient;
        this.metrics = metrics;
        this.latency = latency;
        this.load = load;
        this.resilience = resilience;
        this.readTimeoutNanos = admission.upstream().readTimeout().toNanos();
        this.breaker = new CircuitBreaker(resilience.circuit(), System::nanoTime, metrics::circuitTransitioned);
        this.budget = new RetryBudget(resilience.retry().budgetShare(), resilience.retry().budgetWindow(), System::nanoTime);
        this.otherFailureLog = new ThrottledLog(log, logInterval.toNanos());
        metrics.registerCircuit(() -> breaker.state().ordinal());
    }

    /** The upstream's answer, passed on unchanged. */
    record Answer(int status, HttpHeaders headers, byte[] body) {}

    /**
     * @param pathAndQuery the request path, with the query string if there was one
     * @param inbound      the client's request headers
     * @param body         the request body, or null
     * @throws UpstreamException    if the upstream gave no answer (after any retry)
     * @throws CircuitOpenException if the breaker is open and no call was made
     */
    Answer forward(RequestClass requestClass, HttpMethod method, String pathAndQuery,
                   HttpHeaders inbound, byte[] body) {
        long started = System.nanoTime();
        boolean hasKey = inbound.getFirst("Idempotency-Key") != null;
        boolean retryable = resilience.retry().enabled() && RetryPolicy.eligible(method, pathAndQuery, hasKey);
        budget.recordCall();

        if (!breaker.tryAcquire()) {
            throw new CircuitOpenException(breaker.retryAfterMillis());
        }
        try {
            return once(requestClass, method, pathAndQuery, inbound, body);
        } catch (UpstreamException first) {
            Optional<String> reason = RetryPolicy.retryableReason(first.type(), first.getCause());
            if (!retryable || reason.isEmpty()) {
                throw first;
            }
            if (!RetryPolicy.loadAllowsRetry(load.currentLevel())) {
                metrics.retrySkipped("load");
                throw first;
            }
            long jitter = ThreadLocalRandom.current().nextLong(resilience.retry().jitterMax().toNanos() + 1);
            long deadline = resilience.retry().deadline().toNanos();
            if (!RetryPolicy.fitsDeadline(System.nanoTime() - started, jitter, readTimeoutNanos, deadline)) {
                metrics.retrySkipped("deadline");
                throw first;
            }
            if (!budget.tryRetry()) {
                metrics.retryBudgetExhausted();
                metrics.retrySkipped("budget");
                throw first;
            }
            sleep(jitter);
            if (!breaker.tryAcquire()) {
                throw new CircuitOpenException(breaker.retryAfterMillis());
            }
            metrics.retry(requestClass, reason.get());
            return once(requestClass, method, pathAndQuery, inbound, body);
        }
    }

    /** One attempt. Records the outcome with the breaker; does not retry. */
    private Answer once(RequestClass requestClass, HttpMethod method, String pathAndQuery,
                        HttpHeaders inbound, byte[] body) {
        long started = System.nanoTime();
        try {
            Answer answer = upstream.method(method)
                    .uri(pathAndQuery)
                    .headers(out -> FORWARDED_REQUEST_HEADERS.forEach(name -> {
                        var values = inbound.get(name);
                        if (values != null) {
                            out.put(name, values);
                        }
                    }))
                    .body(body == null ? new byte[0] : body)
                    .exchange((request, response) -> {
                        byte[] payload = response.getBody().readAllBytes();
                        HttpHeaders headers = new HttpHeaders();
                        response.getHeaders().forEach((name, values) -> {
                            if (FORWARDED_RESPONSE_HEADERS.stream().anyMatch(keep -> keep.equalsIgnoreCase(name))) {
                                headers.put(name, values);
                            }
                        });
                        HttpStatusCode code = response.getStatusCode();
                        return new Answer(code.value(), headers, payload);
                    });
            breaker.record(isUpstreamFailureStatus(answer.status()));
            return answer;
        } catch (ResourceAccessException e) {
            UpstreamException failure = classify(e);
            metrics.upstreamError(failure.type());
            breaker.record(RetryPolicy.countsAsBreakerFailure(failure.type()));
            if (failure.type() == UpstreamException.Type.OTHER) {
                Throwable cause = e.getMostSpecificCause();
                otherFailureLog.warn("order service call failed for {} {}: {}: {}", method, pathAndQuery,
                        cause.getClass().getName(), cause.getMessage());
            }
            throw failure;
        } catch (RuntimeException unexpected) {
            // Not a "no answer" failure. Still release a half-open probe, so the breaker cannot stick.
            breaker.record(false);
            throw unexpected;
        } finally {
            long elapsed = System.nanoTime() - started;
            metrics.upstreamTimer(requestClass).record(elapsed, TimeUnit.NANOSECONDS);
            // Timeouts and refusals count too: how long the call took is the signal the load policy needs.
            latency.record(TimeUnit.NANOSECONDS.toMillis(elapsed));
        }
    }

    /** Statuses that count as a failure of the order service for the breaker. */
    static boolean isUpstreamFailureStatus(int status) {
        return status == 502 || status == 503 || status == 504;
    }

    private static void sleep(long nanos) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Maps a failed call to an error type by the cause at the bottom of the exception chain. */
    static UpstreamException classify(ResourceAccessException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectionRequestTimeoutException) {
                return new UpstreamException(UpstreamException.Type.POOL_TIMEOUT,
                        "admission is at its upstream connection limit", e);
            }
            if (cause instanceof ConnectTimeoutException) {
                return new UpstreamException(UpstreamException.Type.CONNECT_TIMEOUT,
                        "the order service could not be reached in time", e);
            }
            if (cause instanceof java.net.ConnectException) {
                return new UpstreamException(UpstreamException.Type.CONNECTION_REFUSED,
                        "the order service is not accepting connections", e);
            }
            if (cause instanceof java.net.SocketTimeoutException) {
                return new UpstreamException(UpstreamException.Type.READ_TIMEOUT,
                        "the order service did not answer in time", e);
            }
        }
        return new UpstreamException(UpstreamException.Type.OTHER, "the order service call failed", e);
    }
}
