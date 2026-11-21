package com.peakload.order;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import java.util.UUID;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Order endpoints. POST /orders needs both idempotency headers; a repeated request with the same
 * key gets the first answer. PATCH /orders/{id}/status needs If-Match with the version last read.
 */
@RestController
class OrderController {

    private static final int MAX_HEADER_LENGTH = 255;

    private final OrderService orders;
    private final OrderStatusService statuses;

    OrderController(OrderService orders, OrderStatusService statuses) {
        this.orders = orders;
        this.statuses = statuses;
    }

    @GetMapping("/orders/{id}")
    OrderView getOrder(@PathVariable UUID id) {
        return statuses.find(id);
    }

    @PatchMapping("/orders/{id}/status")
    OrderView changeStatus(
            @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) StatusChangeRequest body) {
        long expectedVersion = parseIfMatch(ifMatch);
        OrderStatus target = OrderStatus.parse(body == null ? null : body.status());
        return statuses.changeStatus(id, expectedVersion, target);
    }

    /** Accepts 3 or "3" (the quoted ETag form). */
    private static long parseIfMatch(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("missing_if_match", "If-Match header with the expected version is required");
        }
        String trimmed = value.trim().replace("\"", "");
        try {
            long version = Long.parseLong(trimmed);
            if (version < 0) {
                throw new NumberFormatException(trimmed);
            }
            return version;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_if_match", "If-Match must be a non-negative version number");
        }
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
