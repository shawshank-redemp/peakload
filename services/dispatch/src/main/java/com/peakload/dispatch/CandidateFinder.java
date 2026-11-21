package com.peakload.dispatch;

import java.util.ArrayList;
import java.util.List;
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
import org.springframework.stereotype.Component;

/**
 * Finds riders who may take an order: GEOSEARCH around the pickup point, nearest first, then only
 * riders whose last-seen key still exists.
 *
 * <p>Redis is a hint here. A candidate is only a suggestion: the assignment step re-checks the rider's
 * status in Postgres, and a stale position cannot assign a rider that is not available.
 */
@Component
class CandidateFinder {

    /**
     * GEOSEARCH returns the nearest members first. Some of them may have stale seen keys, so ask for
     * more than we need and keep the first fresh ones.
     */
    private static final int FETCH_FACTOR = 3;

    private static final Logger log = LoggerFactory.getLogger(CandidateFinder.class);

    private final StringRedisTemplate redis;
    private final double radiusKm;
    private final int maxCandidates;

    CandidateFinder(StringRedisTemplate redis, DispatchProperties properties) {
        this.redis = redis;
        this.radiusKm = properties.assignment().radiusKm();
        this.maxCandidates = properties.assignment().maxCandidates();
    }

    /** Fresh candidates for an order at (lat, lon) in the given city, nearest first, at most maxCandidates. */
    List<UUID> fresh(String city, double lat, double lon) {
        GeoResults<GeoLocation<String>> results = redis.opsForGeo().search(
                RedisKeys.geo(city),
                GeoReference.fromCoordinate(new Point(lon, lat)),
                new Distance(radiusKm, Metrics.KILOMETERS),
                GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().sortAscending().limit(maxCandidates * FETCH_FACTOR));

        List<UUID> fresh = new ArrayList<>();
        if (results == null) {
            return fresh;
        }
        for (GeoResult<GeoLocation<String>> result : results) {
            UUID riderId = UUID.fromString(result.getContent().getName());
            if (Boolean.TRUE.equals(redis.hasKey(RedisKeys.seen(riderId)))) {
                fresh.add(riderId);
            } else {
                log.debug("skipping stale candidate {}: no last-seen key", riderId);
            }
            if (fresh.size() >= maxCandidates) {
                break;
            }
        }
        return fresh;
    }
}
