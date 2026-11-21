package com.peakload.admission;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Per-client, per-class rate limiting. Redis holds the bucket for "client X, class C" as rl:X:C.
 *
 * <p>Normally every request asks Redis. When Redis fails, the request is decided by the local bucket of the same
 * key, and the {@link LimiterCircuit} decides whether the next requests still ask Redis. A request is never allowed
 * just because the limiter is down: the local buckets keep limiting. The limit is the local one during an outage, and
 * with N instances the effective limit during an outage is up to N times the configured one.
 */
@Component
class RateLimiter {

    /** The outcome for one request. retryAfterMillis is meaningful only when denied. */
    record Decision(boolean allowed, long retryAfterMillis) {
        static final Decision ALLOWED = new Decision(true, 0);
    }

    private final RedisBuckets redis;
    private final LocalTokenBuckets local;
    private final LimiterCircuit circuit;
    private final RateLimitProperties properties;
    private final Timer latency;
    private final Counter errors;
    private final Counter fallbackAllowed;
    private final Counter fallbackLimited;

    @Autowired
    RateLimiter(RedisBuckets redis, LocalTokenBuckets local, LimiterCircuit circuit,
                RateLimitProperties properties, MeterRegistry registry) {
        this.redis = redis;
        this.local = local;
        this.circuit = circuit;
        this.properties = properties;
        this.latency = Timer.builder("admission.limiter")
                .description("Time spent in the rate limiter's Redis round trip")
                .publishPercentileHistogram()
                .register(registry);
        this.errors = Counter.builder("admission.limiter.errors")
                .description("Rate-limit checks against Redis that failed or timed out")
                .register(registry);
        this.fallbackAllowed = Counter.builder("admission.limiter.fallback")
                .description("Requests decided by the local fallback, by outcome")
                .tag("outcome", "allowed")
                .register(registry);
        this.fallbackLimited = Counter.builder("admission.limiter.fallback")
                .description("Requests decided by the local fallback, by outcome")
                .tag("outcome", "limited")
                .register(registry);
    }

    Decision check(String clientId, RequestClass requestClass) {
        if (!properties.enabled()) {
            return Decision.ALLOWED;
        }
        RateLimitProperties.Bucket bucket = properties.classes().get(requestClass);
        String key = key(clientId, requestClass);

        LimiterCircuit.Route route = circuit.acquire();
        if (route == LimiterCircuit.Route.LOCAL) {
            return fallback(key, bucket);
        }

        long started = System.nanoTime();
        try {
            List<Long> reply = redis.take(key, bucket.capacity(), bucket.refillPerSecond());
            circuit.redisSucceeded(route);
            return reply.get(0) == 1 ? Decision.ALLOWED : new Decision(false, reply.get(1));
        } catch (DataAccessException e) {
            circuit.redisFailed(route, e);
            errors.increment();
            return fallback(key, bucket);
        } finally {
            latency.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    /** The local bucket for this key, with the local settings derived from the Redis ones. */
    private Decision fallback(String key, RateLimitProperties.Bucket bucket) {
        RateLimitProperties.Local settings = properties.local();
        long capacity = Math.max(1, Math.round(bucket.capacity() * settings.capacityFactor()));
        double refill = bucket.refillPerSecond() * settings.refillFactor();
        LocalTokenBuckets.Result result = local.take(key, capacity, refill);
        if (result.allowed()) {
            fallbackAllowed.increment();
            return Decision.ALLOWED;
        }
        fallbackLimited.increment();
        return new Decision(false, result.retryAfterMillis());
    }

    /** rl:{clientId}:{class}. A client id cannot collide with another: the class is always the last segment. */
    static String key(String clientId, RequestClass requestClass) {
        return "rl:" + clientId + ":" + requestClass.name();
    }
}
