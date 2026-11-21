package com.peakload.admission;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.client5.http.ConnectTimeoutException;
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
 */
@Service
class ProxyService {

    /** Request headers sent upstream. Anything else from the client is dropped. */
    static final Set<String> FORWARDED_REQUEST_HEADERS = Set.of(
            "Idempotency-Key", "X-Client-Id", "If-Match", "Content-Type", "Accept");

    /** Response headers returned to the client. Idempotent-Replay tells the client a stored answer was replayed. */
    static final Set<String> FORWARDED_RESPONSE_HEADERS = Set.of(
            "Content-Type", "Idempotent-Replay", "Location", "ETag");

    private final RestClient upstream;
    private final AdmissionMetrics metrics;
    private final UpstreamLatency latency;

    ProxyService(RestClient upstreamRestClient, AdmissionMetrics metrics, UpstreamLatency latency) {
        this.upstream = upstreamRestClient;
        this.metrics = metrics;
        this.latency = latency;
    }

    /** The upstream's answer, passed on unchanged. */
    record Answer(int status, HttpHeaders headers, byte[] body) {}

    /**
     * @param pathAndQuery the request path, with the query string if there was one
     * @param inbound      the client's request headers
     * @param body         the request body, or null
     * @throws UpstreamException if the upstream gave no answer
     */
    Answer forward(RequestClass requestClass, HttpMethod method, String pathAndQuery,
                   HttpHeaders inbound, byte[] body) {
        long started = System.nanoTime();
        try {
            return upstream.method(method)
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
        } catch (ResourceAccessException e) {
            UpstreamException failure = classify(e);
            metrics.upstreamError(failure.type());
            throw failure;
        } finally {
            long elapsed = System.nanoTime() - started;
            metrics.upstreamTimer(requestClass).record(elapsed, TimeUnit.NANOSECONDS);
            // Timeouts and refusals count too: how long the call took is the signal the load policy needs.
            latency.record(TimeUnit.NANOSECONDS.toMillis(elapsed));
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
