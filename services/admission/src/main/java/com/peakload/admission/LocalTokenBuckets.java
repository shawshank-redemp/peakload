package com.peakload.admission;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-memory token buckets, used only while Redis is unavailable. Same key shape and the same refill semantics as
 * the Redis script, but held in this process.
 *
 * <p>Memory is bounded. At most {@code maxKeys} buckets are kept; when the cap is reached, the least recently used
 * bucket is evicted. An evicted client starts again with a full bucket, so a flood of new client ids can push out
 * an abuser's bucket. That is a real weakness of this fallback, and it is counted in
 * {@code admission_limiter_local_evictions_total}. A bucket also expires after {@code idleExpiry} without use. That is
 * safe, because a bucket idle that long is full again.
 *
 * <p>Time is a monotonic clock ({@link System#nanoTime()}), injectable for tests.
 */
@Component
class LocalTokenBuckets {

    /** Outcome of one take. retryAfterMillis is meaningful only when denied. */
    record Result(boolean allowed, long retryAfterMillis) {}

    private final Cache<String, Bucket> buckets;
    private final LongSupplier nanoClock;

    @Autowired
    LocalTokenBuckets(RateLimitProperties properties, MeterRegistry registry) {
        this(properties.local(), System::nanoTime, registry);
    }

    LocalTokenBuckets(RateLimitProperties.Local local, LongSupplier nanoClock, MeterRegistry registry) {
        this.nanoClock = nanoClock;
        Counter evictions = Counter.builder("admission.limiter.local.evictions")
                .description("Local buckets evicted because the cap was reached")
                .register(registry);
        this.buckets = Caffeine.newBuilder()
                .maximumSize(local.maxKeys())
                .expireAfterAccess(local.idleExpiry())
                .removalListener((key, bucket, cause) -> {
                    if (cause == RemovalCause.SIZE) {
                        evictions.increment();
                    }
                })
                .build();
    }

    /** Takes one token from the bucket for {@code key}, creating the bucket full if it does not exist. */
    Result take(String key, long capacity, double refillPerSecond) {
        Bucket bucket = buckets.get(key, k -> new Bucket(capacity, nanoClock.getAsLong()));
        return bucket.take(capacity, refillPerSecond, nanoClock.getAsLong());
    }

    /** Number of buckets currently held. For tests and diagnostics. */
    long size() {
        buckets.cleanUp();
        return buckets.estimatedSize();
    }

    /** One bucket. All access is synchronized, so the check and the take are one atomic step. */
    static final class Bucket {
        private double tokens;
        private long lastNanos;

        Bucket(long capacity, long nowNanos) {
            this.tokens = capacity;
            this.lastNanos = nowNanos;
        }

        synchronized Result take(long capacity, double refillPerSecond, long nowNanos) {
            long elapsed = Math.max(0, nowNanos - lastNanos);
            tokens = Math.min(capacity, tokens + elapsed * refillPerSecond / 1_000_000_000d);
            lastNanos = nowNanos;
            if (tokens >= 1) {
                tokens -= 1;
                return new Result(true, 0);
            }
            long millis = (long) Math.ceil((1 - tokens) * 1000 / refillPerSecond);
            return new Result(false, millis);
        }
    }
}
