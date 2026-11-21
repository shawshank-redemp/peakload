package com.peakload.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

// Pure unit test: no Spring context and no database.
class RequestHasherTest {

    private final RequestHasher hasher = new RequestHasher(new ObjectMapper());

    @Test
    void canonicalFormSortsKeysAndIgnoresWhitespace() {
        OrderInput input = new OrderInput("c-1", "Pune", 1200L, 18.52, 73.85);

        assertThat(hasher.canonicalJson(input))
                .isEqualTo("{\"city\":\"Pune\",\"customerId\":\"c-1\",\"pickupLat\":18.52,\"pickupLon\":73.85,\"totalPaise\":1200}");
    }

    @Test
    void sameOrderGivesSameHashAndDifferentOrderGivesDifferentHash() {
        String first = hasher.hash(new OrderInput("c-1", "Pune", 1200L, 18.52, 73.85));

        assertThat(hasher.hash(new OrderInput("c-1", "Pune", 1200L, 18.52, 73.85))).isEqualTo(first);
        assertThat(hasher.hash(new OrderInput("c-1", "Pune", 1300L, 18.52, 73.85))).isNotEqualTo(first);
        assertThat(first).hasSize(64);
    }

    @Test
    void validationTrimsFieldsSoSpacingDoesNotChangeTheHash() {
        OrderInput padded = OrderInput.validated(new CreateOrderRequest("  c-1 ", " Pune", 1200L, 18.52, 73.85));
        OrderInput clean = OrderInput.validated(new CreateOrderRequest("c-1", "Pune", 1200L, 18.52, 73.85));

        assertThat(hasher.hash(padded)).isEqualTo(hasher.hash(clean));
    }
}
