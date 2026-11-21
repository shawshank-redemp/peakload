package com.peakload.order;

import java.util.EnumSet;
import java.util.Set;

/**
 * Order states and the allowed moves between them. This enum is the only place where the
 * transition rules are written. Everything else asks {@link #canTransitionTo}.
 *
 * <p>Main path: CREATED, CONFIRMED, PREPARING, READY, PICKED_UP, DELIVERED, one step at a time.
 * CANCELLED is allowed until PICKED_UP. DELIVERED and CANCELLED are final.
 */
enum OrderStatus {
    CREATED,
    CONFIRMED,
    PREPARING,
    READY,
    PICKED_UP,
    DELIVERED,
    CANCELLED;

    /** The states this one may move to. A same-status move is never in this set, so it is invalid. */
    Set<OrderStatus> allowedNext() {
        return switch (this) {
            case CREATED -> EnumSet.of(CONFIRMED, CANCELLED);
            case CONFIRMED -> EnumSet.of(PREPARING, CANCELLED);
            case PREPARING -> EnumSet.of(READY, CANCELLED);
            case READY -> EnumSet.of(PICKED_UP, CANCELLED);
            case PICKED_UP -> EnumSet.of(DELIVERED);
            case DELIVERED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    boolean canTransitionTo(OrderStatus target) {
        return allowedNext().contains(target);
    }

    /**
     * Reads a status from a request body. Matching is exact, so "confirmed" is rejected.
     *
     * @throws ApiException 400 if the value is missing or not a known state
     */
    static OrderStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.badRequest("missing_status", "status is required");
        }
        for (OrderStatus status : values()) {
            if (status.name().equals(raw)) {
                return status;
            }
        }
        throw ApiException.badRequest("unknown_status", "unknown status: " + raw);
    }
}
