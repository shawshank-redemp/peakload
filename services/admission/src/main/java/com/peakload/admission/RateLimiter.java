package com.peakload.admission;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-client, per-class rate limiting. The bucket for "client X, class C" is one Redis key, rl:X:C.
 *
 * <p>Fails open. If Redis errors or does not answer within the 50 ms command timeout, the request is allowed
 * and the error is counted. The limiter must not turn a Redis outage into an outage of ordering. An in-memory
 * fallback is a later step.
 */
@Component
class RateLimiter {

    /** The outcome for one request. retryAfterMillis is meaningful only when denied. */
    record Decision(boolean allowed, long retryAfterMillis) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final long ERROR_LOG_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final Timer latency;
    private final Counter errors;
    private final AtomicLong lastErrorLogMillis = new AtomicLong(0L);

    RateLimiter(StringRedisTemplate redis, RateLimitProperties properties, MeterRegistry registry) {
        this.redis = redis;
        this.properties = properties;
        this.latency = Timer.builder("admission.limiter")
                .description("Time spent in the rate limiter's Redis round trip")
                .publishPercentileHistogram()
                .register(registry);
        this.errors = Counter.builder("admission.limiter.errors")
                .description("Rate-limit checks that failed (Redis error or timeout); the request was allowed")
                .register(registry);
    }

    Decision check(String clientId, RequestClass requestClass) {
        if (!properties.enabled()) {
            return Decision.ALLOWED;
        }
        RateLimitProperties.Bucket bucket = properties.classes().get(requestClass);
        String key = key(clientId, requestClass);
        long started = System.nanoTime();
        try {
            List<Long> reply = TokenBucketScript.take(redis, key, bucket.capacity(), bucket.refillPerSecond());
            return reply.get(0) == 1 ? Decision.ALLOWED : new Decision(false, reply.get(1));
        } catch (DataAccessException e) {
            errors.increment();
            logErrorAtMostOncePerMinute(e);
            return Decision.ALLOWED;
        } finally {
            latency.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    /** rl:{clientId}:{class}. A client id cannot collide with another: the class is always the last segment. */
    static String key(String clientId, RequestClass requestClass) {
        return "rl:" + clientId + ":" + requestClass.name();
    }

    private void logErrorAtMostOncePerMinute(Exception e) {
        long now = System.currentTimeMillis();
        long last = lastErrorLogMillis.get();
        if (now - last >= ERROR_LOG_INTERVAL_MILLIS && lastErrorLogMillis.compareAndSet(last, now)) {
            log.warn("rate limiter unavailable, allowing requests (fail open): {}", e.toString());
        }
    }
}
