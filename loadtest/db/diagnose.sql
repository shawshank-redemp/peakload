-- Read-only diagnosis of the order database. Run with: psql -f loadtest/db/diagnose.sql (inside the postgres
-- container or through compose exec). Every section prints its own header.
\pset footer off
\echo '== table sizes (data and indexes) and planner row estimates'
SELECT c.relname AS table,
       pg_size_pretty(pg_relation_size(c.oid))  AS data,
       pg_size_pretty(pg_indexes_size(c.oid))   AS indexes,
       pg_size_pretty(pg_total_relation_size(c.oid)) AS total,
       c.reltuples::bigint AS est_rows
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r'
  AND c.relname IN ('orders', 'outbox', 'processed_events', 'idempotency_keys')
ORDER BY pg_total_relation_size(c.oid) DESC;

\echo '== exact row counts'
SELECT 'orders' AS t, count(*) FROM orders
UNION ALL SELECT 'outbox', count(*) FROM outbox
UNION ALL SELECT 'processed_events', count(*) FROM processed_events
UNION ALL SELECT 'idempotency_keys', count(*) FROM idempotency_keys
UNION ALL SELECT 'outbox unpublished', count(*) FROM outbox WHERE published_at IS NULL;

\echo '== dead tuples and autovacuum activity (since the last statistics reset)'
SELECT relname, n_live_tup, n_dead_tup, n_tup_ins, n_tup_upd, n_tup_del,
       autovacuum_count, last_autovacuum, autoanalyze_count, last_autoanalyze
FROM pg_stat_user_tables
WHERE relname IN ('orders', 'outbox', 'processed_events', 'idempotency_keys')
ORDER BY relname;

\echo '== indexes: definition, scans since the last statistics reset, size'
SELECT s.relname AS table, s.indexrelname AS index, s.idx_scan, s.idx_tup_read, s.idx_tup_fetch,
       pg_size_pretty(pg_relation_size(s.indexrelid)) AS size,
       pg_get_indexdef(s.indexrelid) AS definition
FROM pg_stat_user_indexes s
WHERE s.relname IN ('orders', 'outbox', 'processed_events', 'idempotency_keys')
ORDER BY s.relname, s.indexrelname;

\echo '== pg_stat_statements: top 15 by total time'
SELECT calls, round(total_exec_time::numeric, 1) AS total_ms, round(mean_exec_time::numeric, 3) AS mean_ms,
       rows, shared_blks_hit AS hit, shared_blks_read AS read, left(regexp_replace(query, '\s+', ' ', 'g'), 110) AS query
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'peakload')
ORDER BY total_exec_time DESC
LIMIT 15;

\echo '== pg_stat_statements: top 15 by mean time (at least 100 calls)'
SELECT calls, round(total_exec_time::numeric, 1) AS total_ms, round(mean_exec_time::numeric, 3) AS mean_ms,
       rows, left(regexp_replace(query, '\s+', ' ', 'g'), 110) AS query
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = 'peakload') AND calls >= 100
ORDER BY mean_exec_time DESC
LIMIT 15;
