-- Week 4 step 5, item 4: relay statements, flat outbox (before) vs outbox_p (after), EXPLAIN (ANALYZE, BUFFERS).
-- Run against the CLEAN experimental database (peakload_exp2). Each block is rolled back: the data is unchanged.
\set ON_ERROR_STOP on
\pset pager off
-- 200 unpublished rows, so the relay's batch select has something to claim.
BEGIN;
INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
SELECT gen_random_uuid(), 'order', gen_random_uuid(), 'OrderCreated', '{}'::jsonb FROM generate_series(1, 200);
\echo '== relay batch select (100 rows), flat outbox (before)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT id, created_at, aggregate_id, event_type, payload::text FROM outbox WHERE published_at IS NULL
ORDER BY created_at, id LIMIT 100 FOR UPDATE SKIP LOCKED;
\echo '== relay batch select (100 rows), outbox_p (after)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT id, created_at, aggregate_id, event_type, payload::text FROM outbox_p WHERE published_at IS NULL
ORDER BY created_at, id LIMIT 100 FOR UPDATE SKIP LOCKED;
ROLLBACK;

-- One published-row update, on a row from the same month in each variant.
SELECT id::text AS rid, created_at::text AS rca FROM outbox_p WHERE created_at >= now() - interval '3 days' LIMIT 1 \gset
BEGIN;
\echo '== per-row relay update, flat outbox (before): WHERE id only'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
UPDATE outbox SET published_at = now() WHERE id = :'rid'::uuid;
ROLLBACK;
BEGIN;
\echo '== per-row relay update, outbox_p, WHERE id only (what the old statement would do after partitioning)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
UPDATE outbox_p SET published_at = now() WHERE id = :'rid'::uuid;
ROLLBACK;
BEGIN;
\echo '== per-row relay update, outbox_p, WHERE id AND created_at (the new statement)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
UPDATE outbox_p SET published_at = now() WHERE id = :'rid'::uuid AND created_at = :'rca'::timestamptz;
ROLLBACK;
