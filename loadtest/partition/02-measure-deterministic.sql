-- Week 4 step 3: deterministic measures on peakload_exp. Read-only except the WAL section, which inserts 10,000
-- rows in a transaction and rolls it back (the WAL is written; the data is unchanged).
-- Run: docker compose exec -T postgres psql -U peakload -d peakload_exp -f - < loadtest/partition/02-measure-deterministic.sql
\set ON_ERROR_STOP on
\pset pager off

\echo '== sizes (bytes), summed over the leaf tables of each relation (a flat table is its own leaf)'
WITH rels(name) AS (VALUES ('outbox'), ('outbox_p'), ('orders'), ('orders_p')),
     leaves AS (
       SELECT r.name, t.relid
       FROM rels r
       CROSS JOIN LATERAL (
         SELECT p.relid FROM pg_partition_tree(r.name::regclass) p WHERE p.isleaf
         UNION ALL
         SELECT r.name::regclass::oid WHERE NOT EXISTS (SELECT 1 FROM pg_partition_tree(r.name::regclass))
       ) t
     )
SELECT name,
       sum(pg_relation_size(relid)) AS heap_bytes,
       sum(pg_indexes_size(relid)) AS index_bytes,
       sum(pg_total_relation_size(relid)) AS total_bytes,
       count(*) AS leaf_tables
FROM leaves
GROUP BY name
ORDER BY name;

-- Fixed sample: the most recent order (same id for both tables).
SELECT id::text AS oid, created_at::text AS ocreated FROM orders ORDER BY created_at DESC LIMIT 1 \gset
SELECT created_at::text AS ocreated FROM orders WHERE id = :'oid'::uuid \gset

\echo '== GET by id, flat orders'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON) SELECT * FROM orders WHERE id = :'oid'::uuid;
\echo '== GET by id, orders_p (no created_at in the predicate: every partition is probed)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON) SELECT * FROM orders_p WHERE id = :'oid'::uuid;
\echo '== GET by id with created_at, orders_p (pruning possible)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON) SELECT * FROM orders_p WHERE id = :'oid'::uuid AND created_at = :'ocreated'::timestamptz;

\echo '== range: orders created in the last 7 days, flat'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON) SELECT count(*) FROM orders WHERE created_at > now() - interval '7 days';
\echo '== range: orders created in the last 7 days, orders_p'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON) SELECT count(*) FROM orders_p WHERE created_at > now() - interval '7 days';

\echo '== relay batch (oldest 100 unpublished), flat outbox'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT id FROM outbox WHERE published_at IS NULL ORDER BY created_at, id LIMIT 100;
\echo '== relay batch (oldest 100 unpublished), outbox_p'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON)
SELECT id FROM outbox_p WHERE published_at IS NULL ORDER BY created_at, id LIMIT 100;

\echo '== WAL per 10,000-row insert, rolled back (insert LSN read inside the same transaction, before and after the insert)'
BEGIN;
SELECT pg_current_wal_insert_lsn()::text AS a0 \gset
INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload, created_at)
SELECT gen_random_uuid(), 'order', gen_random_uuid(), 'OrderCreated', '{}'::jsonb, now() FROM generate_series(1, 10000);
SELECT pg_wal_lsn_diff(pg_current_wal_insert_lsn(), :'a0'::pg_lsn) AS wal_bytes_flat_outbox;
ROLLBACK;

BEGIN;
SELECT pg_current_wal_insert_lsn()::text AS b0 \gset
INSERT INTO outbox_p (id, aggregate_type, aggregate_id, event_type, payload, created_at)
SELECT gen_random_uuid(), 'order', gen_random_uuid(), 'OrderCreated', '{}'::jsonb, now() FROM generate_series(1, 10000);
SELECT pg_wal_lsn_diff(pg_current_wal_insert_lsn(), :'b0'::pg_lsn) AS wal_bytes_outbox_p;
ROLLBACK;
