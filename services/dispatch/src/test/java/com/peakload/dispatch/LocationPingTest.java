package com.peakload.dispatch;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

// Pure unit test: coordinate ranges, no Spring and no Redis.
class LocationPingTest {

    @Test
    void boundaryValuesAreAccepted() {
        assertThatCode(() -> new LocationPing(90.0, 180.0).validated()).doesNotThrowAnyException();
        assertThatCode(() -> new LocationPing(-90.0, -180.0).validated()).doesNotThrowAnyException();
        assertThatCode(() -> new LocationPing(18.52, 73.85).validated()).doesNotThrowAnyException();
    }

    @Test
    void outOfRangeOrMissingCoordinatesAreRejected() {
        assertThatThrownBy(() -> new LocationPing(90.1, 0.0).validated()).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new LocationPing(-90.1, 0.0).validated()).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new LocationPing(0.0, 180.1).validated()).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new LocationPing(0.0, -180.1).validated()).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new LocationPing(Double.NaN, 0.0).validated()).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new LocationPing(0.0, null).validated()).isInstanceOf(ApiException.class);
    }
}
