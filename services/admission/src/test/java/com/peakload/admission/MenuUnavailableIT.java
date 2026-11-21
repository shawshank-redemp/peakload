package com.peakload.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * A menu read that cannot get a database connection (the menu's pool is full) must answer 503 with a clear body,
 * and be counted under its own outcome. It used to answer 500, which no counter recorded.
 */
@TestPropertySource(properties = "peakload.admission.rate-limit.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MenuUnavailableIT extends AbstractAdmissionIT {

    @MockBean
    MenuService menus;

    @Autowired
    private MeterRegistry metrics;

    @Test
    void poolTimeoutOnTheMenuIs503WithBodyAndItsOwnOutcome() throws Exception {
        when(menus.menu(any())).thenThrow(new CannotGetJdbcConnectionException(
                "HikariPool-1 - Connection is not available, request timed out after 2000ms"));
        double before = metrics.counter("admission.requests", "class", "LOW", "outcome", "database_unavailable")
                .count();

        HttpResponse<String> response = send(request("GET", "/browse/restaurants/" + SPICE_ROUTE + "/menu", null));

        // Fails if the answer is a 500, or the body does not say what happened, or the request is not counted.
        assertEquals(503, response.statusCode(), response.body());
        assertEquals("1", response.headers().firstValue("Retry-After").orElse(null));
        assertTrue(response.body().contains("menu_unavailable"), response.body());
        assertEquals(before + 1, metrics.counter("admission.requests", "class", "LOW", "outcome",
                "database_unavailable").count());
    }
}
