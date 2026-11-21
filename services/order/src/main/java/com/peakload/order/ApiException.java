package com.peakload.order;

import org.springframework.http.HttpStatus;

/** An error that maps to a specific HTTP status and a small JSON body with a stable error code. */
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

    static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
