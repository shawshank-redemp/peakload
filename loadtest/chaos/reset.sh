#!/usr/bin/env bash
# Week 5, step 2: the reset for every chaos run. Order matters:
#   1. the load must already be stopped (the caller's responsibility; this script checks no k6 is running);
#   2. (the drain check below covers the outboxes too);
#   3. the drain check (loadtest/chaos/drain.py) must pass: processed == published, orders balance, per-partition
#      offsets in sync for both groups (not the group total lag), with dispatch still running;
#   4. stop dispatch, truncate the order and dispatch tables, reseed riders in Postgres and Redis, start dispatch and
#      wait for it to be healthy.
# Every wait has a limit (WAIT_LIMIT seconds). If a limit is reached the script exits non-zero: it does not reset.
#   OUT=<run folder> RIDERS_PER_CITY=1200 bash loadtest/chaos/reset.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
OUT=${OUT:?set OUT}; PER_CITY=${RIDERS_PER_CITY:?riders per city}; WAIT_LIMIT=${WAIT_LIMIT:-300}
mkdir -p "$OUT"
LOG="$OUT/reset.txt"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
fail() { log "RESET FAILED: $*"; exit 1; }
now() { date +%s; }
pgq() { docker compose exec -T postgres psql -U peakload -d peakload -At -c "$1"; }
lag() { docker compose exec -T redpanda rpk group describe "$1" 2>/dev/null | awk '/^TOTAL-LAG/ {print $2}'; }

log "reset start"
if pgrep -f "create-orders.js" > /dev/null; then fail "a load run is still active (k6 create-orders)"; fi
log "state before: orders=$(pgq 'select count(*) from orders') order_outbox_unpublished=$(pgq 'select count(*) from outbox where published_at is null') dispatch_outbox_unpublished=$(pgq 'select count(*) from dispatch.outbox where published_at is null') pending=$(pgq 'select count(*) from dispatch.pending_orders') assignments=$(pgq 'select count(*) from dispatch.assignments')"

start=$(now)
until python3 loadtest/chaos/drain.py "$OUT" > /dev/null 2>&1; do
  [ $(( $(now) - start )) -ge "$WAIT_LIMIT" ] && fail "drain not complete within ${WAIT_LIMIT}s: $(python3 loadtest/chaos/drain.py "$OUT" 2>/dev/null | tail -1)"
  sleep 3
done
log "drained after $(( $(now) - start )) s (processed == published, orders balance, offsets in sync)"

docker compose stop dispatch > /dev/null; log "dispatch stopped"
EMPTY=1 bash loadtest/db/seed.sh > /dev/null
log "order and dispatch tables truncated (riders kept, set AVAILABLE)"
bash loadtest/chaos/seed-riders.sh "$PER_CITY" | tee -a "$LOG"
docker compose start dispatch > /dev/null
for t in $(seq 1 60); do bash loadtest/http-in-network.sh dispatch 8082 /api/ping | grep -q dispatch && break; sleep 2; done
bash loadtest/http-in-network.sh dispatch 8082 /api/ping | grep -q dispatch || fail "dispatch not healthy after start"
log "dispatch started and healthy"
log "state after: orders=$(pgq 'select count(*) from orders') assignments=$(pgq 'select count(*) from dispatch.assignments') pending=$(pgq 'select count(*) from dispatch.pending_orders') riders_available=$(pgq "select count(*) from dispatch.riders where status='AVAILABLE'")"
log "reset done"
