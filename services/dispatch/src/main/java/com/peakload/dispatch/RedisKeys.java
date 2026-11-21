package com.peakload.dispatch;

import java.util.Locale;
import java.util.UUID;

/** Names of the Redis keys this service writes. Redis holds only hints: a city cache, GEO positions and last-seen times. */
final class RedisKeys {

    private RedisKeys() {}

    /** GEO set of riders in one city, e.g. riders:geo:pune. The city is lower-cased so "Pune" and "pune" share a key. */
    static String geo(String city) {
        return "riders:geo:" + city.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Cached city for one rider, written at registration and on the first Postgres fallback. No expiry: one
     * small string per rider, and a city does not change in this service. A miss is never a reason to
     * reject a ping; the service falls back to Postgres.
     */
    static String rider(UUID riderId) {
        return "rider:" + riderId;
    }

    /** Last-seen key for one rider. It expires after the configured TTL when the rider stops pinging. */
    static String seen(UUID riderId) {
        return "riders:seen:" + riderId;
    }
}
