#!/usr/bin/env bash
# Reloads the big order dataset (N orders, default 1,000,000) and clears the dispatch tables that earlier runs left
# behind (pending_orders, assignments, processed_events, outbox; riders set AVAILABLE). Dispatch shares this Postgres.
#   N=1000000 bash loadtest/db/seed.sh        or        EMPTY=1 bash loadtest/db/seed.sh   (empty control)
set -euo pipefail
cd "$(dirname "$0")/../.."
N=${N:-1000000}
psql_q() { docker compose exec -T postgres psql -U peakload -d peakload -v ON_ERROR_STOP=1 -At -c "$1"; }
psql_q "TRUNCATE dispatch.assignments, dispatch.pending_orders, dispatch.outbox, dispatch.processed_events"
psql_q "UPDATE dispatch.riders SET status = 'AVAILABLE'" > /dev/null
if [ "${EMPTY:-0}" = "1" ]; then
  psql_q "TRUNCATE orders, idempotency_keys, outbox, processed_events"
  echo "order tables emptied (control)"
else
  docker compose exec -T postgres psql -U peakload -d peakload -v n="$N" -v ON_ERROR_STOP=1 < loadtest/db/seed-orders.sql
fi
