package com.peakload.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Database-wide invariants for riders and assignments. Each check is one SQL statement, so each sees a
 * committed snapshot. Every committed change must keep these true. Call {@link #assertHold} after a test
 * has waited for the system to settle.
 *
 * <ul>
 *   <li>No order has more than one assignment.</li>
 *   <li>No rider has more than one ACTIVE assignment.</li>
 *   <li>Every BUSY rider has exactly one ACTIVE assignment.</li>
 *   <li>Every ACTIVE assignment's rider is BUSY.</li>
 * </ul>
 */
final class Invariants {

    private Invariants() {}

    static void assertHold(JdbcTemplate jdbc) {
        assertEquals(0, count(jdbc, """
                SELECT count(*) FROM (
                    SELECT order_id FROM assignments GROUP BY order_id HAVING count(*) > 1) x
                """), "an order has more than one assignment");

        assertEquals(0, count(jdbc, """
                SELECT count(*) FROM (
                    SELECT rider_id FROM assignments WHERE status = 'ACTIVE'
                    GROUP BY rider_id HAVING count(*) > 1) x
                """), "a rider has more than one ACTIVE assignment");

        assertEquals(0, count(jdbc, """
                SELECT count(*) FROM riders r
                WHERE r.status = 'BUSY'
                  AND (SELECT count(*) FROM assignments a
                       WHERE a.rider_id = r.id AND a.status = 'ACTIVE') <> 1
                """), "a BUSY rider does not have exactly one ACTIVE assignment");

        assertEquals(0, count(jdbc, """
                SELECT count(*) FROM assignments a
                JOIN riders r ON r.id = a.rider_id
                WHERE a.status = 'ACTIVE' AND r.status <> 'BUSY'
                """), "an ACTIVE assignment's rider is not BUSY");
    }

    private static long count(JdbcTemplate jdbc, String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
