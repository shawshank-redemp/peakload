package com.peakload.order;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** POST /orders. Both headers are required; a repeated request with the same key gets the first answer. */
@RestController
class OrderController {

    private static final int MAX_HEADER_LENGTH = 255;

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/orders")
    ResponseEntity<String> createOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Client-Id") String clientId,
            @RequestBody(required = false) CreateOrderRequest body) {
        String key = requireHeader("Idempotency-Key", idempotencyKey);
        String client = requireHeader("X-Client-Id", clientId);

        OrderService.IdempotentResult result = orders.createOrder(client, key, body);

        ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status())
                .contentType(MediaType.APPLICATION_JSON);
        if (result.replay()) {
            response.header("Idempotent-Replay", "true");
        }
        return response.body(result.body());
    }

    private static String requireHeader(String name, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("missing_header", name + " header must not be empty");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_HEADER_LENGTH) {
            throw ApiException.badRequest("invalid_header", name + " header is longer than " + MAX_HEADER_LENGTH);
        }
        return trimmed;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> apiError(ApiException e) {
        return ResponseEntity.status(e.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", e.code(), "message", e.getMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Map<String, String>> missingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", "missing_header", "message", e.getHeaderName() + " header is required"));
    }
}
