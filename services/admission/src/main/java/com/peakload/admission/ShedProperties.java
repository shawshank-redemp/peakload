package com.peakload.admission;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Load-shedding settings, bound from {@code peakload.admission.shed} in application.yml.
 *
 * <p><strong>All threshold values are unmeasured starting values</strong>, chosen so the code runs and the policy
 * can be seen working. They are not tuned. The Week 3 Step 4 measurement is what the numbers should be checked
 * against, and it is reported as it came out.
 *
 * @param enabled         when false, no request is shed; the load level is still computed for the gauge
 * @param elevated        thresholds to enter and leave ELEVATED (sheds LOW)
 * @param high            thresholds to enter and leave HIGH (sheds LOW and STANDARD)
 * @param hardCap        thresholds to enter and leave HARD_CAP (sheds everything, including CRITICAL)
 * @param smoothing       EWMA weight of the newest upstream latency sample, between 0 and 1
 * @param latencyHalfLife how fast the smoothed latency fades when no upstream calls are made; without this, a
 *                        high value would stay after the load stopped, because shed requests never reach upstream
 * @param retryAfter      the Retry-After value sent with a shed response (rounded up to whole seconds, at least 1)
 */
@ConfigurationProperties("peakload.admission.shed")
public record ShedProperties(boolean enabled, Band elevated, Band high, Band hardCap,
                             double smoothing, Duration latencyHalfLife, Duration retryAfter) {

    /** Thresholds for one level. In-flight is a fraction of the upstream pool. Latency is in milliseconds. */
    public record Band(double inFlightEnter, double inFlightExit, double latencyEnterMs, double latencyExitMs) {}

    public ShedProperties {
        if (elevated == null || high == null || hardCap == null) {
            throw new IllegalStateException("shed needs elevated, high and hard-cap thresholds");
        }
        if (!(smoothing > 0 && smoothing <= 1)) {
            throw new IllegalStateException("shed smoothing must be greater than 0 and at most 1");
        }
        if (latencyHalfLife == null || latencyHalfLife.isNegative() || latencyHalfLife.isZero()) {
            throw new IllegalStateException("shed latency-half-life must be greater than zero");
        }
        if (retryAfter == null || retryAfter.isNegative()) {
            throw new IllegalStateException("shed retry-after must not be negative");
        }
    }

    LoadPolicy policy() {
        return new LoadPolicy(toBand(elevated), toBand(high), toBand(hardCap));
    }

    private static LoadPolicy.Band toBand(Band band) {
        return new LoadPolicy.Band(band.inFlightEnter(), band.inFlightExit(),
                band.latencyEnterMs(), band.latencyExitMs());
    }
}
