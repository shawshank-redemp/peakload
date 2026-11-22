#!/usr/bin/env bash
# Week 6, step 1: one admission capacity run at RATE for DURATION (30 s), with a 20 s discarded warm-up at the same rate.
#   OUT=<dir> RATE=800 SHED=on|off bash loadtest/week6/run-ramp.sh
# Dispatch is stopped for the whole series (this measures the create path, not dispatch). Each run: pre-run check,
# drain, reset (loadtest/db/seed.sh EMPTY=1), admission restarted with the shedding flag (limiter and breaker at
# defaults), warm-up, reset again, pre-run check, timed run with the sampler running, drain, end-of-run snapshot.
set -uo pipefail
cd "$(dirname "$0")/../.."
OUT=${OUT:?set OUT}; RATE=${RATE:?rate}; DUR=${DURATION:-30s}; SHED=${SHED:-on}
mkdir -p "$OUT"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/driver.log"; }
pgq() { docker compose exec -T postgres psql -U peakload -d peakload -At -c "$1"; }
gate() { local d=$1; until bash loadtest/precheck.sh "$d" > /dev/null; do log "precheck refused ($d), waiting"; sleep 30; done; }
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }
drain_orders() {
  local start; start=$(date +%s)
  until [ "$(pgq 'select count(*) from outbox where published_at is null')" = 0 ]; do
    [ $(( $(date +%s) - start )) -ge 300 ] && { log "FAILED: order outbox not drained in 300 s"; exit 1; }; sleep 2
  done
}
reset_tables() { drain_orders; EMPTY=1 bash loadtest/db/seed.sh > /dev/null; }
# Postgres counters for matching a stall to a checkpoint or an autovacuum burst later (PostgreSQL 16 counters).
pg_counters() {
  {
    pgq "select 'checkpoints_timed='||checkpoints_timed||' checkpoints_req='||checkpoints_req||' buffers_checkpoint='||buffers_checkpoint||' buffers_backend='||buffers_backend from pg_stat_bgwriter"
    pgq "select 'autovacuum_count='||coalesce(sum(autovacuum_count),0)||' autoanalyze_count='||coalesce(sum(autoanalyze_count),0)||' n_dead_tup='||coalesce(sum(n_dead_tup),0) from pg_stat_user_tables where schemaname='public'"
    pgq "select 'wal_lsn='||pg_current_wal_lsn()||' wal_bytes_from_origin='||pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')"
    pgq "select 'autovacuum_workers_now='||count(*) from pg_stat_activity where backend_type='autovacuum worker'"
    pgq "select 'checkpointer_active_now='||count(*) from pg_stat_activity where backend_type='checkpointer'"
  } > "$1"
}

gate "$OUT/precheck-before-reset"
log "run rate=$RATE duration=$DUR shed=$SHED"
docker compose stop dispatch > /dev/null
docker compose start order > /dev/null
if [ "$SHED" = on ]; then SHEDF=true; else SHEDF=false; fi
SHED_ENABLED=$SHEDF docker compose up -d --force-recreate admission > /dev/null 2>&1
for t in $(seq 1 60); do curl -s -o /dev/null localhost:8080/actuator/health && break; sleep 1; done
reset_tables
log "warm-up 20 s at $RATE/s (discarded)"
mkdir -p "$OUT/warmup"
docker compose --profile loadtest run --rm --no-deps -e BASE_URL=http://admission:8080 -e RATE="$RATE" -e DURATION=20s \
  -v "$PWD/$OUT/warmup":/out k6 run --out json=/out/points.json.gz /loadtest/week6/create-ramp.js > "$OUT/warmup/k6-stdout.log" 2>&1
reset_tables
gate "$OUT/precheck-after-reset"
echo "orders_before=$(pgq 'select count(*) from orders')" > "$OUT/counts.txt"
pg_counters "$OUT/pg-before.txt"
mkdir -p "$OUT/k6"
python3 loadtest/week6/sampler.py --out "$OUT/samples.jsonl" --interval 2 &
SAMPLER=$!
echo "T0=$(now_ms)" > "$OUT/events.txt"
log "timed run"
docker compose --profile loadtest run --rm --no-deps -e BASE_URL=http://admission:8080 -e RATE="$RATE" -e DURATION="$DUR" \
  -v "$PWD/$OUT/k6":/out k6 run --summary-export /out/k6-summary.json --out json=/out/points.json.gz \
  /loadtest/week6/create-ramp.js > "$OUT/k6/k6-stdout.log" 2>&1
log "k6 exit=$?"
kill $SAMPLER 2>/dev/null; wait $SAMPLER 2>/dev/null
drain_orders
echo "orders_after=$(pgq 'select count(*) from orders')" >> "$OUT/counts.txt"
pg_counters "$OUT/pg-after.txt"
curl -s localhost:8080/actuator/prometheus > "$OUT/admission-prometheus.txt"
docker compose logs --no-color admission > "$OUT/admission-log.txt" 2>&1
docker compose start dispatch > /dev/null
log "done"
