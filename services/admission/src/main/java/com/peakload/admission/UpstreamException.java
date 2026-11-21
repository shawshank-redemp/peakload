package com.peakload.admission;

import org.springframework.http.HttpStatus;

/** The upstream could not give an answer. Carries the HTTP status to return and the error type for metrics. */
final class UpstreamException extends RuntimeException {

    /** Error types, used as the "type" label of admission_upstream_errors_total. */
    enum Type {
        READ_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),
        CONNECT_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
        CONNECTION_REFUSED(HttpStatus.SERVICE_UNAVAILABLE),
        POOL_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
        OTHER(HttpStatus.SERVICE_UNAVAILABLE);

        private final HttpStatus status;

        Type(HttpStatus status) {
            this.status = status;
        }

        HttpStatus status() {
            return status;
        }
    }

    private final Type type;

    UpstreamException(Type type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    Type type() {
        return type;
    }

    HttpStatus status() {
        return type.status();
    }
}
