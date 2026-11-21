package com.peakload.admission;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Rate-limit settings, bound from {@code peakload.admission.rate-limit}. The class defaults in application.yml are
 * placeholders, not recommendations. They are unmeasured.
 *
 * @param enabled        when false, no request is limited and Redis is not called
 * @param classes        capacity and refill rate for each request class. All three classes must be present.
 * @param circuitBreaker how the limiter switches to local buckets when Redis fails
 * @param local          the in-memory buckets used during an outage, and how they relate to the Redis limits
 */
@ConfigurationProperties("peakload.admission.rate-limit")
public record RateLimitProperties(boolean enabled, Map<RequestClass, Bucket> classes,
                                  CircuitBreaker circuitBreaker, Local local) {

    /**
     * @param capacity        tokens a client can spend at once (the burst size)
     * @param refillPerSecond tokens added per second; must be greater than zero
     */
    public record Bucket(long capacity, double refillPerSecond) {}

    /**
     * @param enabled  when false, Redis is called for every request and a failure only affects that request
     * @param coolDown how long the circuit stays open (requests use local buckets, Redis is not called) before one
     *                 probe is sent
     */
    public record CircuitBreaker(boolean enabled, Duration coolDown) {
        public CircuitBreaker {
            if (coolDown == null || coolDown.isNegative() || coolDown.isZero()) {
                throw new IllegalStateException("circuit-breaker cool-down must be greater than zero");
            }
        }
    }

    /**
     * @param capacityFactor local capacity = Redis capacity x this factor (1.0 = same as Redis)
     * @param refillFactor   local refill rate = Redis refill rate x this factor (1.0 = same as Redis)
     * @param maxKeys        most buckets held at once; the least recently used is evicted beyond this
     * @param idleExpiry     a bucket unused for this long is dropped (it would be full again by then)
     */
    public record Local(double capacityFactor, double refillFactor, long maxKeys, Duration idleExpiry) {
        public Local {
            if (capacityFactor <= 0 || refillFactor <= 0) {
                throw new IllegalStateException("local factors must be greater than zero");
            }
            if (maxKeys <= 0) {
                throw new IllegalStateException("local max-keys must be greater than zero");
            }
            if (idleExpiry == null || idleExpiry.isNegative() || idleExpiry.isZero()) {
                throw new IllegalStateException("local idle-expiry must be greater than zero");
            }
        }
    }

    /** Settings for tests and code that do not need the breaker or the local buckets to differ from the defaults. */
    public RateLimitProperties(boolean enabled, Map<RequestClass, Bucket> classes) {
        this(enabled, classes, new CircuitBreaker(true, Duration.ofSeconds(2)),
                new Local(1.0, 1.0, 50_000, Duration.ofMinutes(5)));
    }

    @ConstructorBinding
    public RateLimitProperties {
        for (RequestClass requestClass : RequestClass.values()) {
            Bucket bucket = classes == null ? null : classes.get(requestClass);
            if (bucket == null) {
                throw new IllegalStateException("rate-limit has no settings for class " + requestClass);
            }
            if (bucket.capacity() <= 0 || bucket.refillPerSecond() <= 0) {
                throw new IllegalStateException("rate-limit for " + requestClass + " needs capacity > 0 and refill > 0");
            }
        }
        if (circuitBreaker == null || local == null) {
            throw new IllegalStateException("rate-limit needs circuit-breaker and local settings");
        }
    }
}
