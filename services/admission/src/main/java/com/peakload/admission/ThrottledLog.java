package com.peakload.admission;

import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;

/**
 * Logs at most once per interval. Lines that were not written are counted, and the next line that is written says how many.
 * The metrics still count every event; only the log line is limited.
 */
final class ThrottledLog {

    private final Logger log;
    private final long intervalNanos;
    private final AtomicLong lastNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong suppressed = new AtomicLong();

    ThrottledLog(Logger log, long intervalNanos) {
        this.log = log;
        this.intervalNanos = intervalNanos;
    }

    void warn(String format, Object... args) {
        long now = System.nanoTime();
        long last = lastNanos.get();
        boolean due = last == Long.MIN_VALUE || now - last >= intervalNanos;
        if (!due || !lastNanos.compareAndSet(last, now)) {
            suppressed.incrementAndGet();
            return;
        }
        long skipped = suppressed.getAndSet(0);
        if (skipped == 0) {
            log.warn(format, args);
            return;
        }
        Object[] withCount = new Object[args.length + 1];
        withCount[0] = skipped;
        System.arraycopy(args, 0, withCount, 1, args.length);
        log.warn("[{} similar lines suppressed in the last interval] " + format, withCount);
    }
}
