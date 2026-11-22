#!/usr/bin/env bash
# Week 6, step 2: one measured run. Same procedure for every mode, so the runs can be compared.
#
#   MODE=direct OUT=<dir> RATE=2000 bash loadtest/week6/run-step2.sh
#       order service direct on the compose network (no admission), POST /orders at RATE for 30 s.
#   MODE=noisy OUT=<dir> LIMITER=on|off bash loadtest/week6/run-step2.sh
#       admission; five normal clients at 20 req/s each and one abusive client at 1200 req/s, 30 s.
#   MODE=nye OUT=<dir> SHED=on|off PEAK_CRITICAL=2000 bash loadtest/week6/run-step2.sh
#       admission; the NYE-shaped curve (loadtest/nye-curve.js, 300 s), POST /orders peak PEAK_CRITICAL.
#
# Every run: pre-run check (refuses and retries, then stops after 5 minutes), order outbox drained, tables reset
# (loadtest/db/seed.sh EMPTY=1), a discarded warm-up at the same load (20 s; the NYE warm-up is its own 30 s calm
# stage), reset again, a second pre-run check, Postgres counters, the timed run with the sampler, drain, counters
# after, end-of-run snapshots. Dispatch is stopped for the run (the create path is what is measured) and started
# again at the end. Admission is restarted only in the admission modes, with the flag for that run.
set -uo pipefail
cd "$(dirname "$0")/../.."
MODE=${MODE:?direct|noisy|nye}; OUT=${OUT:?set OUT}
mkdir -p "$OUT"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/driver.log"; }
pgq() { docker compose exec -T postgres psql -U peakload -d peakload -At -c "$1"; }
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

gate() {
  local d=$1 tries=0
  until bash loadtest/precheck.sh "$d" > /dev/null; do
    tries=$((tries + 1)); log "precheck refused ($d), try $tries"
    [ "$tries" -ge 10 ] && { log "FAILED: precheck refused 10 times ($d); stopping"; exit 1; }
    sleep 30
  done
  log "precheck ok ($d)"
}

drain_orders() { # $1 = cap in seconds
  local start; start=$(date +%s)
  until [ "$(pgq 'select count(*) from outbox where published_at is null')" = 0 ]; do
    [ $(( $(date +%s) - start )) -ge "$1" ] && { log "FAILED: order outbox not drained in $1 s"; exit 1; }
    sleep 2
  done
}

reset_state() { # $1 = label; drains first, then truncates, then writes the state to reset.txt
  drain_orders 900
  EMPTY=1 bash loadtest/db/seed.sh > /dev/null
  {
    echo "# reset: $1 at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "orders=$(pgq 'select count(*) from orders')"
    echo "idempotency_keys=$(pgq 'select count(*) from idempotency_keys')"
    echo "outbox_total=$(pgq 'select count(*) from outbox')"
    echo "outbox_unpublished=$(pgq 'select count(*) from outbox where published_at is null')"
  } >> "$OUT/reset.txt"
  log "reset ($1): $(tail -4 "$OUT/reset.txt" | tr '\n' ' ')"
}

# Postgres counters, so a stall can be matched to a checkpoint or an autovacuum burst (PostgreSQL 16 counters).
pg_counters() {
  {
    pgq "select 'checkpoints_timed='||checkpoints_timed||' checkpoints_req='||checkpoints_req||' buffers_checkpoint='||buffers_checkpoint||' buffers_backend='||buffers_backend from pg_stat_bgwriter"
    pgq "select 'autovacuum_count='||coalesce(sum(autovacuum_count),0)||' autoanalyze_count='||coalesce(sum(autoanalyze_count),0)||' n_dead_tup='||coalesce(sum(n_dead_tup),0) from pg_stat_user_tables where schemaname='public'"
    pgq "select 'wal_lsn='||pg_current_wal_lsn()||' wal_bytes_from_origin='||pg_wal_lsn_diff(pg_current_wal_lsn(), '0/0')"
    pgq "select 'autovacuum_workers_now='||count(*) from pg_stat_activity where backend_type='autovacuum worker'"
    pgq "select 'checkpointer_active_now='||count(*) from pg_stat_activity where backend_type='checkpointer'"
  } > "$1"
}

# One k6 run. $1 = folder under OUT, $2 = BASE_URL, $3 = script path in the container, rest = -e settings.
k6_run() {
  local sub=$1 base=$2 script=$3; shift 3
  mkdir -p "$OUT/$sub"
  docker compose --profile loadtest run --rm --no-deps -e BASE_URL="$base" "$@" \
    -v "$PWD/$OUT/$sub":/out k6 run --summary-export /out/k6-summary.json --out json=/out/points.json.gz "$script" \
    > "$OUT/$sub/k6-stdout.log" 2>&1
}

# Admission flags for this run (only the admission modes restart admission).
case "$MODE" in
  direct) TARGET=http://order:8081; SCRIPT=/loadtest/week6/create-ramp.js; DUR=30s
          : "${RATE:?RATE for direct mode}"; DRAIN=300 ;;
  noisy)  TARGET=http://admission:8080; SCRIPT=/loadtest/noisy-neighbour.js; DUR=30s
          if [ "${LIMITER:?on|off}" = on ]; then LIM=true; else LIM=false; fi; DRAIN=300 ;;
  nye)    TARGET=http://admission:8080; SCRIPT=/loadtest/nye-curve.js; DUR=300s
          if [ "${SHED:?on|off}" = on ]; then SHEDF=true; else SHEDF=false; fi
          PEAK_CRITICAL=${PEAK_CRITICAL:?PEAK_CRITICAL}; DRAIN=900 ;;
  *) echo "MODE must be direct, noisy or nye" >&2; exit 2 ;;
esac

gate "$OUT/precheck-before-reset"
log "run mode=$MODE target=$TARGET ${RATE:+rate=$RATE }${LIMITER:+limiter=$LIMITER }${SHED:+shed=$SHED }${PEAK_CRITICAL:+peak_critical=$PEAK_CRITICAL }"

docker compose stop dispatch > /dev/null
docker compose start order > /dev/null
if [ "$MODE" = noisy ]; then
  RATE_LIMIT_ENABLED=$LIM docker compose up -d --force-recreate admission > /dev/null 2>&1
fi
if [ "$MODE" = nye ]; then
  SHED_ENABLED=$SHEDF docker compose up -d --force-recreate admission > /dev/null 2>&1
fi
for t in $(seq 1 60); do curl -s -o /dev/null localhost:8080/api/ping && break; sleep 1; done
for t in $(seq 1 60); do bash loadtest/http-in-network.sh order 8081 /actuator/health | grep -q UP && break; sleep 1; done

reset_state "before warm-up"

# Warm-up, discarded.
case "$MODE" in
  direct) log "warm-up 20 s at $RATE/s (discarded)"
          k6_run warmup "$TARGET" "$SCRIPT" -e RATE="$RATE" -e DURATION=20s ;;
  noisy)  log "warm-up 20 s (discarded)"
          k6_run warmup "$TARGET" "$SCRIPT" -e ABUSE_RATE=1200 -e NORMAL_RATE=20 -e DURATION=20s ;;
  nye)    log "warm-up 30 s calm stage (discarded)"
          k6_run warmup "$TARGET" "$SCRIPT" -e MODE=warmup -e PEAK_CRITICAL="$PEAK_CRITICAL" ;;
esac
log "warm-up done"
reset_state "after warm-up"
gate "$OUT/precheck-after-reset"
pg_counters "$OUT/pg-before.txt"

python3 loadtest/week6/sampler.py --out "$OUT/samples.jsonl" --interval 2 &
SAMPLER=$!
echo "T0=$(now_ms)" > "$OUT/events.txt"
log "timed run ($DUR)"
case "$MODE" in
  direct) k6_run k6 "$TARGET" "$SCRIPT" -e RATE="$RATE" -e DURATION="$DUR" ;;
  noisy)  k6_run k6 "$TARGET" "$SCRIPT" -e ABUSE_RATE=1200 -e NORMAL_RATE=20 -e DURATION="$DUR" ;;
  nye)    k6_run k6 "$TARGET" "$SCRIPT" -e MODE=curve -e PEAK_CRITICAL="$PEAK_CRITICAL" ;;
esac
log "k6 exit=$?"
kill $SAMPLER 2>/dev/null; wait $SAMPLER 2>/dev/null

drain_orders $DRAIN
log "order outbox drained"
pg_counters "$OUT/pg-after.txt"
{
  echo "orders=$(pgq 'select count(*) from orders')"
  echo "idempotency_keys=$(pgq 'select count(*) from idempotency_keys')"
  echo "idempotency_duplicate_pairs=$(pgq 'select count(*) - count(distinct (client_id, idempotency_key)) from idempotency_keys')"
  echo "outbox_unpublished=$(pgq 'select count(*) from outbox where published_at is null')"
} > "$OUT/counts.txt"
curl -s localhost:8080/actuator/prometheus > "$OUT/admission-prometheus.txt"
bash loadtest/http-in-network.sh order 8081 /actuator/prometheus > "$OUT/order-prometheus.txt"
docker compose logs --no-color admission > "$OUT/admission-log.txt" 2>&1
docker compose start dispatch > /dev/null
log "done"
