package com.peakload.order;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for idempotency keys, bound from {@code peakload.idempotency} in application.yml.
 *
 * @param ttl how long a key is remembered. After this, the same key counts as unseen.
 * @param cleanup settings for the job that deletes expired keys.
 */
@ConfigurationProperties("peakload.idempotency")
public record IdempotencyProperties(Duration ttl, Cleanup cleanup) {

    /**
     * @param batchSize maximum rows deleted per statement. Small batches keep each delete short.
     */
    public record Cleanup(Duration interval, int batchSize) {}
}
