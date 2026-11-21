#!/usr/bin/env bash
# One New Year's Eve curve run (Week 3 Step 5). Shedding as asked; everything else is the same for every run.
#   OUT=<dir> SHED=true|false bash loadtest/run-nye.sh
# Procedure, every run: admission restarted with SHED_ENABLED; order and dispatch state reset (outbox drained first,
# then tables truncated; dispatch riders set AVAILABLE); warm-up of 30 s at base rate (discarded); reset again;
# the 300 s curve; wait for the order outbox to drain (capped at 900 s); checks written to <OUT>/checks.txt.
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=${OUT:?set OUT}; SHED=${SHED:?true|false}
mkdir -p "$OUT"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/driver.log"; }
psql_q() { docker compose exec -T postgres psql -U peakload -d peakload -At -c "$1"; }
now() { python3 -c 'import time; print(f"{time.time():.3f}")'; }

wait_outbox_drain() { # $1 = cap in seconds; prints seconds waited
  local waited=0
  while [ "$(psql_q "select count(*) from outbox where published_at is null")" != "0" ] && [ "$waited" -lt "$1" ]; do
    sleep 1; waited=$((waited + 1))
  done
  echo "$waited"
}

wait_dispatch_quiet() { # waits until dispatch has processed no new events for 10 s
  local last="" same=0
  for i in $(seq 1 300); do
    local n; n=$(psql_q "select count(*) from dispatch.processed_events")
    if [ "$n" = "$last" ]; then same=$((same + 1)); else same=0; last=$n; fi
    [ "$same" -ge 10 ] && return 0
    sleep 1
  done
}

reset_all() {
  local w; w=$(wait_outbox_drain 900)
  log "order outbox drained before reset after ${w}s"
  psql_q "TRUNCATE orders, idempotency_keys, outbox, processed_events" > /dev/null
  wait_dispatch_quiet
  psql_q "TRUNCATE dispatch.assignments, dispatch.pending_orders, dispatch.outbox, dispatch.processed_events" > /dev/null
  psql_q "UPDATE dispatch.riders SET status = 'AVAILABLE'" > /dev/null
  log "order and dispatch state reset"
}

# Admission: shedding as asked; rate limiter at its normal defaults (no override in this environment).
env -u RATE_LIMIT_ENABLED -u RATE_LIMIT_CRITICAL_CAPACITY -u RATE_LIMIT_CRITICAL_REFILL \
  -u RATE_LIMIT_STANDARD_CAPACITY -u RATE_LIMIT_STANDARD_REFILL -u RATE_LIMIT_LOW_CAPACITY -u RATE_LIMIT_LOW_REFILL \
  SHED_ENABLED=$SHED docker compose up -d --force-recreate admission > /dev/null 2>&1
for i in $(seq 1 60); do [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/ping)" = "200" ] && break; sleep 2; done
log "admission up; SHED_ENABLED=$SHED"

k6_run() { # $1 = folder inside OUT, $2 = MODE
  local name=$1 mode=$2
  mkdir -p "$OUT/$name"
  docker compose --profile loadtest run --rm --no-deps \
    -e BASE_URL=http://admission:8080 -e MODE="$mode" \
    -v "$PWD/$OUT/$name":/out \
    k6 run --summary-export /out/k6-summary.json --out json=/out/points.json.gz /loadtest/nye-curve.js \
    > "$OUT/$name/k6-stdout.log" 2>&1
}

reset_all
k6_run warmup warmup
log "warm-up done (discarded), k6 exit=$?"
reset_all

python3 loadtest/shed_sampler.py "$OUT/shed.csv" &
SAMPLER=$!
python3 loadtest/sample.py --out "$OUT/stack.jsonl" --interval 2 &
STACK=$!

echo "t0=$(now)" > "$OUT/events.txt"
k6_run run curve
log "curve done, k6 exit=$?"
echo "t_end=$(now)" >> "$OUT/events.txt"

drain_wait=$(wait_outbox_drain 900)
echo "outbox_drained_after_k6_s=$drain_wait" >> "$OUT/events.txt"
log "order outbox drained $drain_wait s after the curve ended"
sleep 3
kill $SAMPLER $STACK 2>/dev/null; wait $SAMPLER $STACK 2>/dev/null

curl -s localhost:8080/actuator/prometheus > "$OUT/admission-metrics-final.txt"
curl -s localhost:8081/actuator/prometheus > "$OUT/order-metrics-final.txt"

# Checks. Each line is name=value. The dispatch invariants are the four SQL statements in
# services/dispatch/src/test/java/com/peakload/dispatch/Invariants.java, run against the live database.
{
  echo "orders=$(psql_q "select count(*) from orders")"
  echo "idempotency_keys=$(psql_q "select count(*) from idempotency_keys")"
  echo "orders_without_key=$(psql_q "select count(*) from orders o left join idempotency_keys k on k.order_id = o.id where k.order_id is null")"
  echo "order_ids_on_two_keys=$(psql_q "select count(*) from (select order_id from idempotency_keys where order_id is not null group by 1 having count(*) > 1) x")"
  echo "duplicate_client_key=$(psql_q "select count(*) from (select client_id, idempotency_key from idempotency_keys group by 1, 2 having count(*) > 1) x")"
  echo "order_outbox_unpublished_at_end=$(psql_q "select count(*) from outbox where published_at is null")"
  echo "dispatch_outbox_unpublished_at_end=$(psql_q "select count(*) from dispatch.outbox where published_at is null")"
  echo "dispatch_pending_orders_at_end=$(psql_q "select count(*) from dispatch.pending_orders")"
  echo "dispatch_assignments_at_end=$(psql_q "select count(*) from dispatch.assignments")"
  echo "inv_order_with_more_than_one_assignment=$(psql_q "select count(*) from (select order_id from dispatch.assignments group by order_id having count(*) > 1) x")"
  echo "inv_rider_with_more_than_one_active=$(psql_q "select count(*) from (select rider_id from dispatch.assignments where status = 'ACTIVE' group by rider_id having count(*) > 1) x")"
  echo "inv_busy_rider_without_one_active=$(psql_q "select count(*) from dispatch.riders r where r.status = 'BUSY' and (select count(*) from dispatch.assignments a where a.rider_id = r.id and a.status = 'ACTIVE') <> 1")"
  echo "inv_active_assignment_rider_not_busy=$(psql_q "select count(*) from dispatch.assignments a join dispatch.riders r on r.id = a.rider_id where a.status = 'ACTIVE' and r.status <> 'BUSY'")"
} > "$OUT/checks.txt"
echo "outbox_drain_s=$drain_wait" >> "$OUT/checks.txt"
log "done"
