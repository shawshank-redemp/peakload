package com.peakload.dispatch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoSearchCommandArgs;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Finds riders who may take an order, nearest first, in three steps:
 *
 * <ol>
 *   <li>Redis GEOSEARCH around the pickup point, returning the nearest {@value #SEARCH_WINDOW_FACTOR} x
 *       max-candidates members. This is a hint: it may hold riders who went offline or are busy.</li>
 *   <li>Redis last-seen check: a member without a riders:seen key is stale and is dropped.</li>
 *   <li>Postgres availability check: only riders whose status is AVAILABLE in the riders table are kept.
 *       Postgres is the authority for status. Without this step, busy riders would fill the candidate list,
 *       and riders beyond the first few would never be tried.</li>
 * </ol>
 *
 * <p>Limit: the window is the nearest {@code SEARCH_WINDOW_FACTOR x max-candidates} members. If more than that
 * many nearby riders are busy or stale, a farther available rider is not found. Pagination is not
 * implemented.
 *
 * <p>A candidate is only a suggestion. The assignment step re-checks the rider's status with a conditional
 * UPDATE, so a race between this check and the assignment is still handled correctly.
 */
@Component
class CandidateFinder {

    /** How many GEO members to fetch for each candidate wanted. */
    static final int SEARCH_WINDOW_FACTOR = 10;

    private static final Logger log = LoggerFactory.getLogger(CandidateFinder.class);

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final double radiusKm;
    private final int maxCandidates;

    CandidateFinder(StringRedisTemplate redis, JdbcTemplate jdbc, DispatchProperties properties) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.radiusKm = properties.assignment().radiusKm();
        this.maxCandidates = properties.assignment().maxCandidates();
    }

    /** Available, fresh candidates for an order at (lat, lon) in the given city, nearest first, at most maxCandidates. */
    List<UUID> fresh(String city, double lat, double lon) {
        GeoResults<GeoLocation<String>> results = redis.opsForGeo().search(
                RedisKeys.geo(city),
                GeoReference.fromCoordinate(new Point(lon, lat)),
                new Distance(radiusKm, Metrics.KILOMETERS),
                GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending()
                        .limit(maxCandidates * SEARCH_WINDOW_FACTOR));
        if (results == null) {
            return new ArrayList<>();
        }

        List<UUID> nearby = new ArrayList<>();
        for (GeoResult<GeoLocation<String>> result : results) {
            UUID riderId = UUID.fromString(result.getContent().getName());
            if (Boolean.TRUE.equals(redis.hasKey(RedisKeys.seen(riderId)))) {
                nearby.add(riderId);
            } else {
                log.debug("skipping stale candidate {}: no last-seen key", riderId);
            }
        }
        if (nearby.isEmpty()) {
            return nearby;
        }

        Set<UUID> available = availableAmong(nearby);
        List<UUID> candidates = new ArrayList<>();
        for (UUID riderId : nearby) {
            if (available.contains(riderId)) {
                candidates.add(riderId);
                if (candidates.size() >= maxCandidates) {
                    break;
                }
            }
        }
        return candidates;
    }

    /** One query: which of these riders are AVAILABLE in Postgres. */
    private Set<UUID> availableAmong(List<UUID> riders) {
        String placeholders = String.join(",", java.util.Collections.nCopies(riders.size(), "?"));
        List<UUID> rows = jdbc.queryForList(
                "SELECT id FROM riders WHERE status = 'AVAILABLE' AND id IN (" + placeholders + ")",
                UUID.class, riders.toArray());
        return new HashSet<>(rows);
    }
}
