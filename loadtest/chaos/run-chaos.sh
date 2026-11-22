#!/usr/bin/env bash
# Week 5, step 2: one dispatch chaos run. Reset, riders seeded fresh, 20 s warm-up (discarded), reset and reseed,
# then the timed run: create-orders.js for DURATION at RATE, dispatch killed with docker kill (KILLS=3, at +20 s,
# +60 s and +100 s, restarted 15 s after each kill), or no kill (KILLS=0, the control). The backlog is then
# drained (up to DRAIN_LIMIT seconds) and the report is written.
#   OUT=<dir> KILLS=3|0 RATE=20 DURATION=150s RIDERS_PER_CITY=1200 bash loadtest/chaos/run-chaos.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
OUT=${OUT:?set OUT}; KILLS=${KILLS:-3}; RATE=${RATE:-20}; DUR=${DURATION:-150s}
PER_CITY=${RIDERS_PER_CITY:?riders per city}; DRAIN_LIMIT=${DRAIN_LIMIT:-900}
mkdir -p "$OUT"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/driver.log"; }
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }
pgq() { docker compose exec -T postgres psql -U peakload -d peakload -At -c "$1"; }
gate() { until bash loadtest/precheck.sh "$1" > /dev/null; do log "precheck refused ($1), waiting"; sleep 30; done; }
reset_run() {  # the reset procedure: drain, wait for lag, stop dispatch, truncate, reseed, start dispatch
  OUT="$1" RIDERS_PER_CITY="$PER_CITY" WAIT_LIMIT="${WAIT_LIMIT:-300}" bash loadtest/chaos/reset.sh || { log "reset failed: stopping"; exit 1; }
}
create_run() {  # $1 = out dir, $2 = duration
  mkdir -p "$1"
  docker compose --profile loadtest run --rm --no-deps -e BASE_URL=http://order:8081 -e RATE="$RATE" \
    -e DURATION="$2" -v "$PWD/$1":/out k6 run --summary-export /out/k6-summary.json /loadtest/chaos/create-orders.js \
    > "$1/k6-stdout.log" 2>&1
}
dispatch_counters() {  # one snapshot per dispatch process lifetime (taken just before each kill, and at the end)
  echo "# lifetime snapshot $(now_ms)" >> "$OUT/dispatch-metrics.txt"
  curl -s localhost:8082/actuator/prometheus | grep "^events_duplicates_ignored_total" >> "$OUT/dispatch-metrics.txt" || true
}
lag_now() { docker compose exec -T redpanda rpk group describe dispatch-order-events 2>/dev/null | awk '/^TOTAL-LAG/ {print $2}'; }

gate "$OUT/precheck-before-reset"
log "chaos run kills=$KILLS rate=$RATE duration=$DUR riders per city=$PER_CITY"
reset_run "$OUT/reset-1"
log "warm-up 20 s (discarded)"
create_run "$OUT/warmup" 20s
reset_run "$OUT/reset-2"
gate "$OUT/precheck-after-reset"
echo "orders_before=$(pgq 'select count(*) from orders')" > "$OUT/pg-before.txt"

# lag poller: one line per second, "<epoch ms> <lag>", for the whole timed run and the drain
( while :; do echo "$(now_ms) $(lag_now)"; sleep 1; done ) > "$OUT/lag.txt" &
POLLER=$!
echo "T0=$(now_ms)" > "$OUT/events.txt"
T0=$(awk -F= '/T0/ {print $2}' "$OUT/events.txt")
create_run "$OUT/k6" "$DUR" &
K6=$!
if [ "$KILLS" = 3 ]; then
  for k in 1 2 3; do
    at=$(( (k == 1 ? 20 : k == 2 ? 60 : 100) ))
    target=$(( T0 + at * 1000 ))
    while [ "$(now_ms)" -lt "$target" ]; do sleep 0.2; done
    dispatch_counters
    docker kill peakload-dispatch-1 > /dev/null
    echo "kill_$k=$(now_ms)" >> "$OUT/events.txt"; log "dispatch killed ($k)"
    sleep 15
    docker compose start dispatch > /dev/null
    echo "restart_$k=$(now_ms)" >> "$OUT/events.txt"; log "dispatch restarted ($k)"
  done
fi
wait $K6; log "k6 exit=$?"
# drain: the drain check (processed == published, orders balance, per-partition offsets in sync). Fails loudly at the limit.
drain_start=$(now_ms)
until python3 loadtest/chaos/drain.py "$OUT" > /dev/null 2>&1; do
  if [ $(( ($(now_ms) - drain_start) / 1000 )) -ge "$DRAIN_LIMIT" ]; then
    log "DRAIN FAILED: limit ${DRAIN_LIMIT}s reached; last check: $(python3 loadtest/chaos/drain.py "$OUT" 2>/dev/null | tail -1)"
    echo "drain_failed=1" >> "$OUT/events.txt"
    kill $POLLER 2>/dev/null; exit 1
  fi
  sleep 3
done
echo "drain_seconds=$(( ($(now_ms) - drain_start) / 1000 ))" >> "$OUT/events.txt"
sleep 3
kill $POLLER 2>/dev/null; wait $POLLER 2>/dev/null
dispatch_counters
echo "orders_after=$(pgq 'select count(*) from orders')" >> "$OUT/pg-before.txt"
python3 loadtest/chaos/chaos_report.py "$OUT" > "$OUT/report.stdout" 2>&1
python3 loadtest/chaos/recovery.py "$OUT" > "$OUT/recovery.stdout" 2>&1
log "done"
