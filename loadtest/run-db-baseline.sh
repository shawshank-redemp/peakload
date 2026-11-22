#!/usr/bin/env bash
# Week 4 Step 1: one database baseline run on the big dataset (loadtest/db/seed-orders.sql must have run first).
#   OUT=<dir> bash loadtest/run-db-baseline.sh
# Order: wait for the outbox to drain; sample order ids; warm-up (discarded); reset pg_stat_statements; snapshot
# index and table statistics; measured run (create at 4400 req/s plus reads at 50 req/s, 30 s); wait for the outbox to
# drain; snapshot again; diagnostics and EXPLAIN into OUT. The big dataset is kept between the warm-up and the run.
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=${OUT:?set OUT}
mkdir -p "$OUT"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/driver.log"; }
psql_q() { docker compose exec -T postgres psql -U peakload -d peakload -At -c "$1"; }
psql_f() { docker compose exec -T postgres psql -U peakload -d peakload "$@"; }

wait_outbox_drain() {
  local waited=0
  while [ "$(psql_q "select count(*) from outbox where published_at is null")" != "0" ] && [ "$waited" -lt 900 ]; do
    sleep 1; waited=$((waited + 1))
  done
  echo "$waited"
}

log "outbox drained before the run after $(wait_outbox_drain) s"
bash loadtest/db/sample-ids.sh > /dev/null && log "sampled $(wc -l < loadtest/db/order-ids.txt) order ids for reads"

# Warm-up: Week 1's warm-up (creates at 20 req/s for 30 s). Discarded.
mkdir -p "$OUT/warmup"
docker compose --profile loadtest run --rm --no-deps -e BASE_URL=http://order:8081 -e WARMUP=1 \
  -v "$PWD/$OUT/warmup":/out k6 run --summary-export /out/k6-summary.json /loadtest/db-baseline.js \
  > "$OUT/warmup/k6-stdout.log" 2>&1
log "warm-up done (discarded), k6 exit=$?"
log "outbox drained after warm-up in $(wait_outbox_drain) s"

psql_q "select pg_stat_statements_reset()" > /dev/null
psql_q "select relname, indexrelname, idx_scan from pg_stat_user_indexes where schemaname='public' order by 1,2" > "$OUT/index-scans-before.txt"
psql_q "select relname, n_tup_ins, n_tup_upd, n_tup_del, n_dead_tup, autovacuum_count, autoanalyze_count from pg_stat_user_tables where schemaname='public' order by 1" > "$OUT/table-stats-before.txt"
log "pg_stat_statements reset; index and table statistics snapshotted"

python3 loadtest/sample.py --out "$OUT/stack.jsonl" --interval 2 &
STACK=$!
echo "t0=$(python3 -c 'import time; print(f"{time.time():.3f}")')" > "$OUT/events.txt"

mkdir -p "$OUT/run"
docker compose --profile loadtest run --rm --no-deps -e BASE_URL=http://order:8081 \
  -e CREATE_RATE=4400 -e READ_RATE=50 -e DURATION=30s \
  -v "$PWD/$OUT/run":/out k6 run --summary-export /out/k6-summary.json --out json=/out/points.json.gz \
  /loadtest/db-baseline.js > "$OUT/run/k6-stdout.log" 2>&1
log "measured run done, k6 exit=$?"
echo "t_end=$(python3 -c 'import time; print(f"{time.time():.3f}")')" >> "$OUT/events.txt"

echo "outbox_drained_after_run_s=$(wait_outbox_drain)" >> "$OUT/events.txt"
sleep 3
kill $STACK 2>/dev/null; wait $STACK 2>/dev/null

psql_q "select relname, indexrelname, idx_scan from pg_stat_user_indexes where schemaname='public' order by 1,2" > "$OUT/index-scans-after.txt"
psql_q "select relname, n_tup_ins, n_tup_upd, n_tup_del, n_dead_tup, autovacuum_count, autoanalyze_count from pg_stat_user_tables where schemaname='public' order by 1" > "$OUT/table-stats-after.txt"

# Diagnostics after the run (sizes, counts, dead tuples, index usage, pg_stat_statements).
psql_f < loadtest/db/diagnose.sql > "$OUT/diagnose.txt" 2>&1
log "diagnostics written"

# EXPLAIN for the hot queries, using one real order (its id, version, status and client id).
read -r oid ver st cid key <<< "$(psql_q "select o.id, o.version, o.status, o.client_id, k.idempotency_key from orders o join idempotency_keys k on k.order_id = o.id order by o.created_at desc limit 1" | tr '|' ' ')"
psql_f -v oid="$oid" -v ver="$ver" -v st="$st" -v cid="$cid" -v key="$key" < loadtest/db/explain-hot.sql > "$OUT/explain-hot.txt" 2>&1
log "EXPLAIN written for order $oid (version $ver, status $st)"
log "done"
