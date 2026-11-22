-- Runs once, when the Postgres volume is first created (docker-entrypoint-initdb.d).
-- The library itself is loaded by shared_preload_libraries in docker-compose.yml.
-- For an existing volume, run loadtest/db/enable-pg-stat.sh instead.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
