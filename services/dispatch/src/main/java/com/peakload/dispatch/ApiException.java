package com.peakload.dispatch;

import org.springframework.http.HttpStatus;

/** An error with a specific HTTP status and a stable error code, returned as a small JSON body. */
class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
