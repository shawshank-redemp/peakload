package com.peakload.admission;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

// Pure unit test: the route-to-class mapping, with no Spring and no network.
class RouteTableTest {

    @Test
    void placingAnOrderIsCritical() {
        var route = RouteTable.match(HttpMethod.POST, "/orders").orElseThrow();
        assertThat(route.requestClass()).isEqualTo(RequestClass.CRITICAL);
        assertThat(route.target()).isEqualTo(RouteTable.Target.ORDER_SERVICE);
    }

    @Test
    void readingAndChangingAnOrderAreStandard() {
        assertThat(RouteTable.match(HttpMethod.GET, "/orders/abc-123").orElseThrow().requestClass())
                .isEqualTo(RequestClass.STANDARD);
        assertThat(RouteTable.match(HttpMethod.PATCH, "/orders/abc-123/status").orElseThrow().requestClass())
                .isEqualTo(RequestClass.STANDARD);
    }

    @Test
    void browsingIsLowAndServedLocally() {
        var route = RouteTable.match(HttpMethod.GET, "/browse/restaurants/11111111-1111-4111-8111-111111111111/menu")
                .orElseThrow();
        assertThat(route.requestClass()).isEqualTo(RequestClass.LOW);
        assertThat(route.target()).isEqualTo(RouteTable.Target.LOCAL_MENU);
    }

    @Test
    void unknownPathsAndWrongMethodsMatchNothing() {
        assertThat(RouteTable.match(HttpMethod.GET, "/nope")).isEmpty();
        assertThat(RouteTable.match(HttpMethod.DELETE, "/orders")).isEmpty();
        assertThat(RouteTable.match(HttpMethod.GET, "/orders")).isEmpty();
        assertThat(RouteTable.match(HttpMethod.POST, "/orders/abc/status")).isEmpty();
    }
}
