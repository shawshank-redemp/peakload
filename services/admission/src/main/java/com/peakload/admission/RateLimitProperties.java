package com.peakload.admission;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rate-limit settings, bound from {@code peakload.admission.rate-limit}. The defaults in application.yml are
 * placeholders, not recommendations. They are unmeasured.
 *
 * @param enabled when false, no request is limited and Redis is not called
 * @param classes capacity and refill rate for each request class. All three classes must be present.
 */
@ConfigurationProperties("peakload.admission.rate-limit")
public record RateLimitProperties(boolean enabled, Map<RequestClass, Bucket> classes) {

    /**
     * @param capacity        tokens a client can spend at once (the burst size)
     * @param refillPerSecond tokens added per second; must be greater than zero
     */
    public record Bucket(long capacity, double refillPerSecond) {}

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
    }
}
