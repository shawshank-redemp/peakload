-- Retention, option B: DETACH and DROP the monthly partitions that end at or before the cut-off (ret_part).
-- Measured in one session, same reporting as 04-op-batch.sql.
\set ON_ERROR_STOP on
\timing on
SELECT coalesce(sum(pg_total_relation_size(relid)), 0) AS size_before FROM pg_partition_tree('ret_part'::regclass) WHERE isleaf \gset
SELECT count(*) AS rows_before FROM ret_part \gset
SELECT pg_current_wal_insert_lsn()::text AS w0 \gset
CALL drop_old_partitions(:'cut'::date);
SELECT pg_wal_lsn_diff(pg_current_wal_insert_lsn(), :'w0'::pg_lsn) AS wal_bytes;
SELECT pg_stat_force_next_flush();
SELECT pg_sleep(2);
SELECT n_dead_tup AS dead_tuples_after FROM pg_stat_user_tables WHERE relname = 'ret_part';
SELECT coalesce(sum(pg_total_relation_size(relid)), 0) AS size_after FROM pg_partition_tree('ret_part'::regclass) WHERE isleaf;
SELECT count(*) AS rows_after FROM ret_part;
SELECT :size_before AS size_before_bytes, :rows_before AS rows_before;
