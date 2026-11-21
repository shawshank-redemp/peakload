package com.peakload.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The order service is down: admission must answer 503 with a clear body, and keep serving the local menu.
 * The upstream URL points at a port nothing listens on.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdmissionDownIT extends AbstractAdmissionIT {

    static final String DEAD_UPSTREAM = "http://127.0.0.1:" + freePort();

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("peakload.admission.upstream.order-url", () -> DEAD_UPSTREAM);
    }

    @Test
    void orderServiceDownBecomes503WithClearBody() throws Exception {
        HttpResponse<String> response = send(request("POST", "/orders", "{\"customerId\":\"c\"}")
                .header("Idempotency-Key", "down-1")
                .header("X-Client-Id", "client-down"));

        // Fails if an unreachable upstream is reported as anything other than 503.
        assertEquals(503, response.statusCode());
        assertTrue(response.body().contains("connection_refused"), response.body());
    }

    @Test
    void localMenuStillWorksWhenTheOrderServiceIsDown() throws Exception {
        HttpResponse<String> response = send(request("GET", "/browse/restaurants/" + SPICE_ROUTE + "/menu", null));

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Spice Route"));
    }

    /** A port that was free a moment ago. Nothing is listening on it. */
    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
