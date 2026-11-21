package com.peakload.order;

import java.time.OffsetDateTime;
import java.util.UUID;

/** The order as returned by GET and PATCH. */
record OrderView(
        UUID id,
        String customerId,
        String city,
        String status,
        long totalPaise,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
