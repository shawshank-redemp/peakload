package com.peakload.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The retry through admission, with the order service stubbed. The breaker is off here so that it does not
 * change the counts; the breaker has its own test class.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RetryIT extends AbstractAdmissionIT {

    static final StubOrderService STUB = new StubOrderService();
    private static final String ORDERS = "/orders";
    private static final String ORDER = "/orders/o-1";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("peakload.admission.upstream.order-url", STUB::url);
        registry.add("peakload.admission.upstream.read-timeout", () -> "PT2S");
        registry.add("peakload.admission.rate-limit.enabled", () -> "false");
        registry.add("peakload.admission.shed.enabled", () -> "false");
        registry.add("peakload.admission.resilience.retry.enabled", () -> "true");
        registry.add("peakload.admission.resilience.retry.jitter-max", () -> "PT0.01S");
        registry.add("peakload.admission.resilience.circuit.enabled", () -> "false");
    }

    /** The JDK server normalises header names ("Idempotency-key"), so look them up without regard to case. */
    private static String header(StubOrderService.Received r, String name) {
        return r.headers().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(java.util.Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    @BeforeEach
    void reset() throws Exception {
        // The budget is a share of recent calls (10%), so with few calls there is no budget for a retry. Earn some
        // budget first with 20 plain successful reads, then clear what was received.
        STUB.reply("GET", ORDER, StubOrderService.Reply.of(200, "{\"orderId\":\"o-1\"}"));
        for (int i = 0; i < 20; i++) {
            send(request("GET", ORDER, null));
        }
        STUB.clearReceived();
    }

    private HttpResponse<String> postOrder(String key) throws Exception {
        HttpRequest.Builder request = request("POST", ORDERS,
                "{\"customerId\":\"c-retry\",\"city\":\"Pune\",\"totalPaise\":1200,\"pickupLat\":18.52,\"pickupLon\":73.85}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return send(request);
    }

    @Test
    void aPostWithAKeyIsRetriedOnceWithTheSameKeyAndSucceeds() throws Exception {
        STUB.replySequence("POST", ORDERS, StubOrderService.Reply.of(StubOrderService.ABORT, ""));
        STUB.reply("POST", ORDERS, StubOrderService.Reply.of(201, "{\"orderId\":\"o-1\",\"status\":\"CREATED\"}"));

        HttpResponse<String> response = postOrder("key-retry-1");

        assertEquals(201, response.statusCode(), response.body());
        var sent = STUB.received().stream().filter(r -> r.method().equals("POST")).toList();
        assertEquals(2, sent.size(), "one failed attempt and one retry");
        assertEquals("key-retry-1", header(sent.get(0), "Idempotency-Key"));
        assertEquals("key-retry-1", header(sent.get(1), "Idempotency-Key"), "the retry must send the same key");
    }

    @Test
    void aKeylessPostIsNeverRetried() throws Exception {
        STUB.replySequence("POST", ORDERS, StubOrderService.Reply.of(StubOrderService.ABORT, ""));
        STUB.reply("POST", ORDERS, StubOrderService.Reply.of(201, "{}"));

        HttpResponse<String> response = postOrder(null);

        assertEquals(503, response.statusCode(), "the failure is returned, not retried");
        assertEquals(1, STUB.received().stream().filter(r -> r.method().equals("POST")).count());
    }

    @Test
    void aGetIsRetriedWhenTheConnectionDrops() throws Exception {
        STUB.replySequence("GET", ORDER, StubOrderService.Reply.of(StubOrderService.ABORT, ""));
        STUB.reply("GET", ORDER, StubOrderService.Reply.of(200, "{\"orderId\":\"o-1\"}"));

        HttpResponse<String> response = send(request("GET", ORDER, null));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals(2, STUB.received().stream().filter(r -> r.method().equals("GET")).count());
    }

    @Test
    void a4xxIsReturnedAndNeverRetried() throws Exception {
        STUB.reply("POST", ORDERS, StubOrderService.Reply.of(400, "{\"error\":\"invalid\"}"));

        HttpResponse<String> response = postOrder("key-4xx");

        assertEquals(400, response.statusCode());
        assertEquals(1, STUB.received().stream().filter(r -> r.method().equals("POST")).count());
    }

    @Test
    void anUpstream5xxIsReturnedAndNeverRetried() throws Exception {
        STUB.reply("POST", ORDERS, StubOrderService.Reply.of(500, "{\"error\":\"boom\"}"));

        HttpResponse<String> response = postOrder("key-5xx");

        assertEquals(500, response.statusCode());
        assertEquals(1, STUB.received().stream().filter(r -> r.method().equals("POST")).count());
    }
}
