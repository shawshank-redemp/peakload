package com.peakload.dispatch;

import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for dispatch integration tests. Starts Postgres 16, Redis 7 and Redpanda (same image as compose) on
 * random host ports, once per JVM. Nothing here uses the compose stack.
 *
 * <p>The consumer reads from the earliest offset in tests, so a test cannot miss an event produced before
 * the consumer group was assigned its partitions. Each test uses its own city, so GEO sets and riders do
 * not interfere between tests that share the same containers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.consumer.auto-offset-reset=earliest")
abstract class AbstractDispatchIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
    static final RedpandaContainer REDPANDA =
            new RedpandaContainer(DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v24.2.7"));

    static {
        POSTGRES.start();
        REDIS.start();
        REDPANDA.start();
        createTopic("order-events");
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.kafka.bootstrap-servers", REDPANDA::getBootstrapServers);
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MeterRegistry metrics;

    @Autowired
    protected ObjectMapper json;

    protected String registerRider(String name, String city) {
        ResponseEntity<Map> response = rest.postForEntity("/riders", Map.of("name", name, "city", city), Map.class);
        if (response.getStatusCode().value() != 201) {
            fail("setup: rider registration returned " + response.getStatusCode());
        }
        return (String) response.getBody().get("id");
    }

    protected void ping(String riderId, double lat, double lon) {
        ResponseEntity<Void> response = rest.postForEntity("/riders/" + riderId + "/location",
                Map.of("lat", lat, "lon", lon), Void.class);
        if (response.getStatusCode().value() != 204) {
            fail("setup: location ping returned " + response.getStatusCode());
        }
    }

    /**
     * Publishes an OrderCreated event the way the order service does: key = order id, headers event-id and
     * event-type, payload with the pickup point.
     */
    protected void produceOrderCreated(UUID eventId, UUID orderId, String city, double lat, double lon) {
        String payload = String.format(
                "{\"orderId\":\"%s\",\"status\":\"CREATED\",\"version\":0,\"city\":\"%s\","
                        + "\"occurredAt\":\"%s\",\"pickupLat\":%s,\"pickupLon\":%s}",
                orderId, city, Instant.now(), lat, lon);
        ProducerRecord<String, String> record = new ProducerRecord<>("order-events", orderId.toString(), payload);
        record.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("event-type", "OrderCreated".getBytes(StandardCharsets.UTF_8));
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(record).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("could not produce OrderCreated", e);
        }
    }

    protected long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    protected double counter(String name) {
        return metrics.find(name).counter() == null ? 0 : metrics.find(name).counter().count();
    }

    protected String riderStatus(String riderId) {
        return jdbc.queryForObject("SELECT status FROM riders WHERE id = ?", String.class, UUID.fromString(riderId));
    }

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
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void createTopic(String name) {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, REDPANDA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(name, 3, (short) 1))).all().get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("could not create topic " + name, e);
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not create topic " + name, e);
        }
    }
}
