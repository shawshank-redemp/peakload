-- Week 4 step 5: removes the rows the pgbench order-create replay added to the EXPERIMENTAL database, so flat and
-- partitioned variants hold the same seeded data again. Affects peakload_exp only.
\set ON_ERROR_STOP on
CREATE TEMP TABLE bench_ids AS SELECT id FROM orders WHERE client_id = 'bench';
DELETE FROM outbox WHERE aggregate_id IN (SELECT id FROM bench_ids);
DELETE FROM outbox_p WHERE aggregate_id IN (SELECT id FROM bench_ids);
DELETE FROM idempotency_keys WHERE client_id = 'bench';
DELETE FROM orders WHERE client_id = 'bench';
VACUUM ANALYZE orders;
VACUUM ANALYZE outbox;
VACUUM ANALYZE outbox_p;
VACUUM ANALYZE idempotency_keys;
SELECT 'orders' AS t, count(*) FROM orders UNION ALL SELECT 'orders_p', count(*) FROM orders_p
UNION ALL SELECT 'outbox', count(*) FROM outbox UNION ALL SELECT 'outbox_p', count(*) FROM outbox_p;
