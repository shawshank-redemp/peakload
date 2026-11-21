package com.peakload.dispatch;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A rider as stored in Postgres. Postgres is the source of truth for every field. */
record Rider(
        UUID id,
        String name,
        String city,
        String status,
        int version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
