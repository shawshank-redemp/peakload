package com.peakload.admission;

import java.util.List;

/**
 * The Redis side of the limiter, as an interface so that tests can put a fake Redis behind the rate limiter.
 * {@link #take} runs the token-bucket script once and returns {allowed (1 or 0), retry_ms}. A failed or timed-out
 * call throws a {@link org.springframework.dao.DataAccessException}.
 */
interface RedisBuckets {

    List<Long> take(String key, long capacity, double refillPerSecond);
}
