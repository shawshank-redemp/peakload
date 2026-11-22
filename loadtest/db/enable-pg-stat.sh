#!/usr/bin/env bash
# Creates the pg_stat_statements extension in an existing Postgres volume. The library is preloaded by the compose
# command (shared_preload_libraries); a fresh volume gets the extension from ops/postgres/init instead.
set -euo pipefail
cd "$(dirname "$0")/../.."
docker compose exec -T postgres psql -U peakload -d peakload -v ON_ERROR_STOP=1 \
  -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;"
docker compose exec -T postgres psql -U peakload -d peakload -At -c \
  "select extname, extversion from pg_extension where extname = 'pg_stat_statements';"
