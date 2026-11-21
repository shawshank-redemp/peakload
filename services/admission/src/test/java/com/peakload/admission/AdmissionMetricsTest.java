package com.peakload.admission;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** The in-flight total counts every class, so local menu reads (LOW) take part in the load signal. */
class AdmissionMetricsTest {

    @Test
    void menuReadsCountTowardsTheTotalInFlight() {
        AdmissionMetrics metrics = new AdmissionMetrics(new SimpleMeterRegistry());

        metrics.enter(RequestClass.LOW); // a menu read
        metrics.enter(RequestClass.CRITICAL);
        assertThat(metrics.inFlightTotal()).isEqualTo(2);

        metrics.exit(RequestClass.LOW);
        assertThat(metrics.inFlightTotal()).isEqualTo(1);
    }
}
