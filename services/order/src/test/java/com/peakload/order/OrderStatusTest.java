package com.peakload.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

// Pure unit test: checks the transition table for every pair of states. No Spring, no database.
class OrderStatusTest {

    private static final OrderStatus CREATED = OrderStatus.CREATED;
    private static final OrderStatus CONFIRMED = OrderStatus.CONFIRMED;
    private static final OrderStatus PREPARING = OrderStatus.PREPARING;
    private static final OrderStatus READY = OrderStatus.READY;
    private static final OrderStatus PICKED_UP = OrderStatus.PICKED_UP;
    private static final OrderStatus DELIVERED = OrderStatus.DELIVERED;
    private static final OrderStatus CANCELLED = OrderStatus.CANCELLED;

    /** The complete list of legal moves, written out by hand so the test does not copy the enum. */
    private static final Set<String> LEGAL = Set.of(
            CREATED + ">" + CONFIRMED,
            CREATED + ">" + CANCELLED,
            CONFIRMED + ">" + PREPARING,
            CONFIRMED + ">" + CANCELLED,
            PREPARING + ">" + READY,
            PREPARING + ">" + CANCELLED,
            READY + ">" + PICKED_UP,
            READY + ">" + CANCELLED,
            PICKED_UP + ">" + DELIVERED);

    @Test
    void everyLegalMovePasses() {
        for (String move : LEGAL) {
            String[] parts = move.split(">");
            OrderStatus from = OrderStatus.valueOf(parts[0]);
            OrderStatus to = OrderStatus.valueOf(parts[1]);
            assertThat(from.canTransitionTo(to)).as(move).isTrue();
        }
    }

    @Test
    void everyOtherPairIsRejected() {
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                if (LEGAL.contains(from + ">" + to)) {
                    continue;
                }
                assertThat(from.canTransitionTo(to)).as(from + " -> " + to).isFalse();
            }
        }
    }

    @Test
    void sameStatusIsNeverAllowed() {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(status.canTransitionTo(status)).as(status + " -> " + status).isFalse();
        }
    }

    @Test
    void deliveredAndCancelledAllowNothing() {
        assertThat(DELIVERED.allowedNext()).isEmpty();
        assertThat(CANCELLED.allowedNext()).isEmpty();
    }

    @Test
    void pickedUpCannotBeCancelled() {
        assertThat(PICKED_UP.canTransitionTo(CANCELLED)).isFalse();
        assertThat(PICKED_UP.allowedNext()).isEqualTo(EnumSet.of(DELIVERED));
    }

    @Test
    void parseAcceptsExactNamesOnly() {
        assertThat(OrderStatus.parse("PREPARING")).isEqualTo(PREPARING);
        assertThatThrownBy(() -> OrderStatus.parse("preparing"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("unknown status");
        assertThatThrownBy(() -> OrderStatus.parse(null)).isInstanceOf(ApiException.class);
    }
}
