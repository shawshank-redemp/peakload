-- Retention, option A: batched DELETE of outbox rows older than the cut-off (flat table ret_flat).
-- Run through run-retention.sh, which passes the cut-off as a psql variable (cut). Measured in one session.
\set ON_ERROR_STOP on
\timing on
SELECT pg_total_relation_size('ret_flat') AS size_before \gset
SELECT count(*) AS rows_before FROM ret_flat \gset
SELECT coalesce((SELECT sum(autovacuum_count) FROM pg_stat_user_tables WHERE relname = 'ret_flat'), 0) AS av_before \gset
SELECT pg_current_wal_insert_lsn()::text AS w0 \gset
CALL purge_flat(:'cut'::timestamptz);
SELECT pg_wal_lsn_diff(pg_current_wal_insert_lsn(), :'w0'::pg_lsn) AS wal_bytes;
SELECT pg_stat_force_next_flush();
SELECT pg_sleep(2);
SELECT n_dead_tup AS dead_tuples_after FROM pg_stat_user_tables WHERE relname = 'ret_flat';
SELECT pg_total_relation_size('ret_flat') AS size_after;
SELECT count(*) AS rows_after FROM ret_flat;
SELECT coalesce((SELECT sum(autovacuum_count) FROM pg_stat_user_tables WHERE relname = 'ret_flat'), 0) - :av_before AS autovacuum_runs_during;
SELECT :size_before AS size_before_bytes, :rows_before AS rows_before;
