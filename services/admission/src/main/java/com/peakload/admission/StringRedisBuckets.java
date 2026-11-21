package com.peakload.admission;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** {@link RedisBuckets} backed by the real Redis (through the token-bucket Lua script). */
@Component
class StringRedisBuckets implements RedisBuckets {

    private final StringRedisTemplate redis;

    StringRedisBuckets(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public List<Long> take(String key, long capacity, double refillPerSecond) {
        return TokenBucketScript.take(redis, key, capacity, refillPerSecond);
    }
}
