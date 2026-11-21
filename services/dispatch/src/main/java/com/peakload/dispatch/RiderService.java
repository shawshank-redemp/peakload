package com.peakload.dispatch;

import com.github.f4b6a3.uuid.UuidCreator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Registers riders and records their location.
 *
 * <p>Postgres holds every rider. Redis holds only hints: a GEO position per rider, and a last-seen key
 * that expires. A location ping first checks that the rider exists in Postgres, and only then writes
 * to Redis. If Redis loses data, the next ping restores the hint, and no assignment is affected.
 */
@Service
public class RiderService {

    private static final String SELECT_CITY = "SELECT city FROM riders WHERE id = ?";

    private static final String SELECT_RIDER = """
            SELECT id, name, city, status, version, created_at, updated_at
            FROM riders
            WHERE id = ?
            """;

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final Duration lastSeenTtl;
    private final Counter registered;
    private final Counter pings;
    private final Counter lookupsFromRedis;
    private final Counter lookupsFromPostgres;

    RiderService(JdbcTemplate jdbc, StringRedisTemplate redis, DispatchProperties properties, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.lastSeenTtl = properties.location().lastSeenTtl();
        this.registered = Counter.builder("riders.registered")
                .description("Riders registered by POST /riders")
                .register(registry);
        this.pings = Counter.builder("location.pings")
                .description("Location pings accepted for an existing rider")
                .register(registry);
        this.lookupsFromRedis = Counter.builder("location.lookups")
                .description("Rider city lookups for location pings, by where the answer came from")
                .tag("source", "redis")
                .register(registry);
        this.lookupsFromPostgres = Counter.builder("location.lookups")
                .description("Rider city lookups for location pings, by where the answer came from")
                .tag("source", "postgres")
                .register(registry);
    }

    /** Registers a rider with status AVAILABLE and a UUIDv7 id. */
    public Rider register(CreateRiderRequest body) {
        CreateRiderRequest input = body.validated();
        UUID id = UuidCreator.getTimeOrderedEpoch();
        jdbc.update("INSERT INTO riders (id, name, city) VALUES (?, ?, ?)", id, input.name(), input.city());
        // Cache the city so the first ping does not need Postgres. If this write fails, the request fails
        // after the insert; the rider exists and the next ping fills the cache from Postgres.
        redis.opsForValue().set(RedisKeys.rider(id), input.city());
        registered.increment();
        return find(id);
    }

    /** @throws ApiException 404 if the rider does not exist */
    public Rider find(UUID id) {
        return findOptional(id).orElseThrow(() -> riderNotFound(id));
    }

    /**
     * Records a location ping: validates the coordinates, confirms the rider exists in Postgres, then
     * updates the GEO position and the last-seen key.
     *
     * @throws ApiException 400 for invalid coordinates; 404 if the rider does not exist
     */
    public void recordLocation(UUID id, LocationPing ping) {
        LocationPing coordinates = ping.validated();
        String city = cachedCity(id).orElseGet(() -> cityFromPostgres(id));

        // GEOADD takes longitude first. The member is the rider id.
        redis.opsForGeo().add(RedisKeys.geo(city), new Point(coordinates.lon(), coordinates.lat()), id.toString());
        redis.opsForValue().set(RedisKeys.seen(id), Instant.now().toString(), lastSeenTtl);
        pings.increment();
    }

    /** Redis first. A hit means the rider was registered and its city is cached. */
    private Optional<String> cachedCity(UUID id) {
        String city = redis.opsForValue().get(RedisKeys.rider(id));
        if (city != null) {
            lookupsFromRedis.increment();
        }
        return Optional.ofNullable(city);
    }

    /**
     * Fallback on a Redis miss only: read the city from Postgres and write it back to Redis. A rider that
     * does not exist gives 404, and that miss is not cached.
     */
    private String cityFromPostgres(UUID id) {
        lookupsFromPostgres.increment();
        List<String> cities = jdbc.query(SELECT_CITY, (rs, rowNum) -> rs.getString("city"), id);
        if (cities.isEmpty()) {
            throw riderNotFound(id);
        }
        String city = cities.get(0);
        redis.opsForValue().set(RedisKeys.rider(id), city);
        return city;
    }

    private Optional<Rider> findOptional(UUID id) {
        List<Rider> rows = jdbc.query(SELECT_RIDER, (rs, rowNum) -> new Rider(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("city"),
                rs.getString("status"),
                rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)), id);
        return rows.stream().findFirst();
    }

    private static ApiException riderNotFound(UUID id) {
        return ApiException.notFound("rider_not_found", "rider " + id + " does not exist");
    }
}
