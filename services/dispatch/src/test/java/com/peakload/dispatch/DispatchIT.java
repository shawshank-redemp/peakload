package com.peakload.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.geo.Point;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Rider registration, location pings, the GEO set, the last-seen TTL, and the city cache. Uses a two-second
 * last-seen TTL so the expiry can be observed within a test.
 */
@TestPropertySource(properties = "peakload.dispatch.location.last-seen-ttl=PT2S")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DispatchIT extends AbstractDispatchIT {

    @Test
    void registerPingThenRiderIsInGeoSetAndLastSeenKeyExpires() throws Exception {
        double postgresLookupsBefore = lookups("postgres");
        double redisLookupsBefore = lookups("redis");

        String id = registerRider("Asha", "Pune");
        ping(id, 18.52, 73.85);

        // Fails if a ping had to read Postgres: the city must come from the Redis cache written at registration.
        assertEquals(postgresLookupsBefore, lookups("postgres"), "a ping must not query Postgres");
        assertEquals(redisLookupsBefore + 1, lookups("redis"), "the ping's city must be served from Redis");

        // Fails if the rider was not written to the city's GEO set, or with the wrong coordinates.
        Point position = redis.opsForGeo().position("riders:geo:pune", id).get(0);
        assertNotNull(position);
        assertEquals(73.85, position.getX(), 0.0005, "GEO x is longitude");
        assertEquals(18.52, position.getY(), 0.0005, "GEO y is latitude");

        // Fails if the last-seen key was not set, or has no TTL.
        String seen = "riders:seen:" + id;
        assertTrue(redis.hasKey(seen), "last-seen key must exist right after a ping");
        Long ttl = redis.getExpire(seen, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 2, "TTL must come from the configured 2 s, was " + ttl);

        // Fails if the key never expires.
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (Boolean.TRUE.equals(redis.hasKey(seen))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("last-seen key still present 15 s after a 2 s TTL");
            }
            Thread.sleep(200);
        }
        assertFalse(redis.hasKey(seen));
    }

    @Test
    void redisMissFallsBackToPostgresOnceAndRefillsTheCache() {
        String id = registerRider("Meera", "Pune");
        redis.delete("rider:" + id);
        double postgresLookupsBefore = lookups("postgres");

        ping(id, 18.52, 73.85);

        // Fails if the fallback did not run, or ran more than once.
        assertEquals(postgresLookupsBefore + 1, lookups("postgres"));
        // Fails if the fallback did not write the city back to Redis.
        assertEquals("Pune", redis.opsForValue().get("rider:" + id));
    }

    @Test
    void locationPingForUnknownRiderIsRejectedWith404() {
        double postgresLookupsBefore = lookups("postgres");
        ResponseEntity<Map> response = rest.postForEntity("/riders/" + UUID.randomUUID() + "/location",
                Map.of("lat", 18.52, "lon", 73.85), Map.class);

        assertEquals(404, response.getStatusCode().value());
        assertEquals("rider_not_found", response.getBody().get("error"));
        // The miss must not be cached: a second unknown ping goes to Postgres again.
        assertEquals(postgresLookupsBefore + 1, lookups("postgres"));
    }

    @Test
    void invalidCoordinatesAreRejectedWith400() {
        String id = registerRider("Ravi", "Pune");

        ResponseEntity<Map> response = rest.postForEntity("/riders/" + id + "/location",
                Map.of("lat", 91.0, "lon", 73.85), Map.class);

        assertEquals(400, response.getStatusCode().value());
        assertEquals("invalid_latitude", response.getBody().get("error"));
    }

    private double lookups(String source) {
        return metrics.counter("location.lookups", "source", source).count();
    }
}
