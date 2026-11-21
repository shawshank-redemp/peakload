package com.peakload.order;

import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for *IT tests. Starts a Postgres 16 and a Redpanda container on random host ports, once
 * per JVM. Nothing here uses the docker compose stack. The containers are shared by all IT classes,
 * and Spring caches the application context, so the suite starts each container only once.
 *
 * <p>Each test should use its own client id and idempotency key. That keeps tests independent
 * even though they share one database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractIntegrationTest {

    static final String TOPIC = OutboxRelay.TOPIC;

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    /** Same image as docker-compose.yml. */
    static final RedpandaContainer REDPANDA =
            new RedpandaContainer(DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v24.2.7"));

    static {
        POSTGRES.start();
        REDPANDA.start();
        createTopic();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
    }

    protected static final String ORDER_BODY = "{\"customerId\":\"c-it\",\"city\":\"Pune\",\"totalPaise\":1200}";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    private int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper json;

    // ---------- HTTP helpers ----------

    protected HttpResponse<String> postOrder(String idempotencyKey, String clientId, String body) throws Exception {
        return send(HttpRequest.newBuilder(url("/orders"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .header("X-Client-Id", clientId)
                .POST(BodyPublishers.ofString(body)));
    }

    /** Sends PATCH /orders/{id}/status. Pass null for ifMatch to leave the header out. */
    protected HttpResponse<String> patchStatus(UUID orderId, Long ifMatch, String status) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(url("/orders/" + orderId + "/status"))
                .header("Content-Type", "application/json");
        if (ifMatch != null) {
            request.header("If-Match", String.valueOf(ifMatch));
        }
        return send(request.method("PATCH", BodyPublishers.ofString("{\"status\":\"" + status + "\"}")));
    }

    protected HttpResponse<String> getOrder(UUID orderId) throws Exception {
        return send(HttpRequest.newBuilder(url("/orders/" + orderId)).GET());
    }

    protected UUID createdOrderId(HttpResponse<String> createResponse) throws Exception {
        return UUID.fromString(json.readTree(createResponse.body()).get("orderId").asText());
    }

    /** Creates an order with a fresh key and client, and returns its id. Asserts the 201. */
    protected UUID createOrder(String clientId) throws Exception {
        HttpResponse<String> response = postOrder(unique("key"), clientId, ORDER_BODY);
        if (response.statusCode() != 201) {
            fail("setup: expected 201 creating order, got " + response.statusCode() + " " + response.body());
        }
        return createdOrderId(response);
    }

    protected JsonNode parse(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HTTP.send(request.build(), BodyHandlers.ofString());
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    // ---------- database helpers ----------

    protected long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    protected long ordersForClient(String clientId) {
        return count("SELECT count(*) FROM orders WHERE client_id = ?", clientId);
    }

    protected long outboxEventsFor(UUID orderId, String eventType) {
        return count("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND event_type = ?", orderId, eventType);
    }

    protected long unpublishedOutboxRows() {
        return count("SELECT count(*) FROM outbox WHERE published_at IS NULL");
    }

    // ---------- concurrency and waiting helpers ----------

    /** Runs {@code task} on {@code threads} threads that all start at the same moment. */
    protected static <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return task.call();
                }));
            }
            startGate.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(90, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Polls until the condition is true, or fails the test after the timeout. */
    protected static void awaitTrue(String what, Duration timeout, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("timed out after " + timeout + " waiting for: " + what);
            }
            Thread.sleep(200);
        }
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private static void createTopic() {
        try (Admin admin = Admin.create(
                java.util.Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("could not create topic " + TOPIC, e);
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not create topic " + TOPIC, e);
        }
    }

    /** Pauses or unpauses the Redpanda container (the broker outage in the outage test). */
    protected static void setBrokerPaused(boolean paused) {
        DockerClient docker = DockerClientFactory.instance().client();
        if (paused) {
            docker.pauseContainerCmd(REDPANDA.getContainerId()).exec();
        } else {
            docker.unpauseContainerCmd(REDPANDA.getContainerId()).exec();
        }
    }
}
