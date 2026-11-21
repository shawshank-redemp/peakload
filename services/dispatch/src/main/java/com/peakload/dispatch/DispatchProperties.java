package com.peakload.dispatch;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings bound from {@code peakload.dispatch} in application.yml. */
@ConfigurationProperties("peakload.dispatch")
public record DispatchProperties(Location location) {

    /**
     * @param lastSeenTtl how long a location ping keeps the rider's seen key. Redis expires keys in whole
     *                    seconds, so the value must be at least one second.
     */
    public record Location(Duration lastSeenTtl) {}
}
