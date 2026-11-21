package com.peakload.admission;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** EWMA and fade, with a fake clock. */
class UpstreamLatencyTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void zeroBeforeAnySample() {
        AtomicLong clock = new AtomicLong(0);
        UpstreamLatency latency = new UpstreamLatency(0.2, Duration.ofSeconds(5), clock::get,
                new SimpleMeterRegistry());

        assertThat(latency.valueMillis()).isEqualTo(0.0);
    }

    @Test
    void firstSampleSetsTheValueThenSamplesBlendByTheSmoothingWeight() {
        AtomicLong clock = new AtomicLong(0);
        UpstreamLatency latency = new UpstreamLatency(0.2, Duration.ofSeconds(5), clock::get,
                new SimpleMeterRegistry());

        latency.record(100);
        assertThat(latency.valueMillis()).isEqualTo(100.0);

        latency.record(200); // 100 + 0.2 * (200 - 100) = 120
        assertThat(latency.valueMillis()).isEqualTo(120.0);
    }

    @Test
    void valueHalvesEveryHalfLifeWithoutSamples() {
        AtomicLong clock = new AtomicLong(0);
        UpstreamLatency latency = new UpstreamLatency(1.0, Duration.ofSeconds(5), clock::get,
                new SimpleMeterRegistry());

        latency.record(800);
        clock.set(5 * SECOND);
        assertThat(latency.valueMillis()).isCloseTo(400.0, org.assertj.core.data.Offset.offset(1e-6));
        clock.set(10 * SECOND);
        assertThat(latency.valueMillis()).isCloseTo(200.0, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void aSampleAfterAFadeBlendsFromTheFadedValue() {
        AtomicLong clock = new AtomicLong(0);
        UpstreamLatency latency = new UpstreamLatency(0.5, Duration.ofSeconds(5), clock::get,
                new SimpleMeterRegistry());

        latency.record(800);
        clock.set(5 * SECOND); // faded to 400
        latency.record(0); // 400 + 0.5 * (0 - 400) = 200
        assertThat(latency.valueMillis()).isCloseTo(200.0, org.assertj.core.data.Offset.offset(1e-6));
    }
}
