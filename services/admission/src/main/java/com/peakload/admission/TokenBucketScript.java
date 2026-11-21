package com.peakload.admission;

import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.FileCopyUtils;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Runs the token-bucket Lua script once. The script is the only code that decides allow or deny. */
final class TokenBucketScript {

    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>(load(), List.class);

    private TokenBucketScript() {}

    /**
     * @return {allowed (1 or 0), retry_ms}
     */
    static List<Long> take(StringRedisTemplate redis, String key, long capacity, double refillPerSecond) {
        @SuppressWarnings("unchecked")
        List<Long> reply = (List<Long>) redis.execute(SCRIPT, List.of(key),
                String.valueOf(capacity), String.valueOf(refillPerSecond));
        if (reply == null || reply.size() != 2) {
            throw new IllegalStateException("token bucket script returned an unexpected reply: " + reply);
        }
        return reply;
    }

    private static String load() {
        try (InputStreamReader reader = new InputStreamReader(
                new ClassPathResource("ratelimit/token-bucket.lua").getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        } catch (IOException e) {
            throw new IllegalStateException("cannot load ratelimit/token-bucket.lua", e);
        }
    }
}
