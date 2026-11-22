#!/usr/bin/env bash
# Week 4 step 2 (bisect): one run of one condition. Measures; does not tune.
#
#   OUT=<dir> CODE=current|week1 DISPATCH=on|off PGSTAT=on|off TARGET=direct|admission \
#     bash loadtest/step2/run-cond.sh
#
#   CODE       current = this repo's order service; week1 = the Week 1 order service image (peakload-w1-order),
#              on its own empty database "week1" on the same Postgres.
#   DISPATCH   off stops the dispatch container for the run.
#   PGSTAT     off sets pg_stat_statements.track = none for the run (restored to all afterwards).
#   TARGET     direct posts to the order service; admission posts through admission with rate limiting and
#              shedding switched off, so the only difference is the extra hop.
#
# Each run: empty tables (truncate) -> 30 s warm-up at 20 creates/s (discarded) -> truncate, pg_stat_statements reset ->
# 30 s at RATE creates/s,
# with sampling (sample.py, 2 s) and host CPU (ps, 2 s) -> wait for the outbox to drain.
# Same workload script for every condition: loadtest/db-baseline.js. READ_RATE (default 0) adds GET reads on the ids in
# loadtest/db/order-ids.txt; the bisect reads condition is the only one with reads.
set -uo pipefail
cd "$(dirname "$0")/../.."
OUT=${OUT:?set OUT}
CODE=${CODE:-current}; DISPATCH=${DISPATCH:-off}; PGSTAT=${PGSTAT:-on}; TARGET=${TARGET:-direct}
RATE=${RATE:-4400}
READ_RATE=${READ_RATE:-0}
mkdir -p "$OUT"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/driver.log"; }
psql_db() { docker compose exec -T postgres psql -U peakload -d "$1" -At -c "$2"; }
DB=peakload; [ "$CODE" = week1 ] && DB=week1
backlog() { psql_db "$DB" "select count(*) from outbox where published_at is null" 2>/dev/null || echo "?"; }

wait_drain() {
  local start n
  start=$(date +%s)
  while :; do
    n=$(backlog)
    [ "$n" = "0" ] && { echo $(( $(date +%s) - start )); return 0; }
    [ $(( $(date +%s) - start )) -ge 900 ] && { echo null; return 1; }
    sleep 1
  done
}

truncate_tables() {
  wait_drain > /dev/null || log "WARNING: outbox not drained before truncate"
  if [ "$CODE" = current ]; then
    EMPTY=1 bash loadtest/db/seed.sh > /dev/null
  else
    psql_db week1 "TRUNCATE orders, idempotency_keys, outbox, processed_events" > /dev/null
  fi
}

wait_healthy() {  # $1 = container name; waits for the actuator health endpoint inside it
  local i
  for i in $(seq 1 90); do
    docker exec "$1" bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/health HTTP/1.0\r\nHost: x\r\n\r\n" >&3 && grep -q UP <&3' 2>/dev/null && return 0
    sleep 2
  done
  return 1
}

# ---- stack set-up for this condition ----
log "condition: CODE=$CODE DISPATCH=$DISPATCH PGSTAT=$PGSTAT TARGET=$TARGET RATE=$RATE"
if [ "$DISPATCH" = on ]; then docker compose start dispatch > /dev/null; else docker compose stop dispatch > /dev/null; fi

if [ "$CODE" = week1 ]; then
  docker compose stop order > /dev/null
  docker rm -f w1-order > /dev/null 2>&1
  docker compose exec -T postgres psql -U peakload -d postgres -c "DROP DATABASE IF EXISTS week1" -c "CREATE DATABASE week1" > /dev/null
  docker run -d --name w1-order --network peakload_default -p 8081:8081 \
    -e DB_URL=jdbc:postgresql://postgres:5432/week1 -e KAFKA_BOOTSTRAP_SERVERS=redpanda:9092 \
    peakload-w1-order > /dev/null || { log "ERROR: week1 container did not start"; exit 1; }
  wait_healthy w1-order || { log "ERROR: week1 order not healthy"; exit 1; }
  TARGET_URL=http://w1-order:8081
else
  docker rm -f w1-order > /dev/null 2>&1
  docker compose start order > /dev/null
  docker compose up -d --wait order > /dev/null 2>&1 || true
  TARGET_URL=http://order:8081
fi

if [ "$TARGET" = admission ]; then
  RATE_LIMIT_ENABLED=false SHED_ENABLED=false docker compose up -d admission > /dev/null
  TARGET_URL=http://admission:8080
  sleep 5
else
  docker compose stop admission > /dev/null
fi

if [ "$PGSTAT" = on ]; then TRACK=all; else TRACK=none; fi
docker compose exec -T postgres psql -U peakload -d peakload -At \
  -c "ALTER SYSTEM SET pg_stat_statements.track = '$TRACK'" -c "SELECT pg_reload_conf()" > /dev/null
log "pg_stat_statements.track = $(psql_db peakload 'show pg_stat_statements.track')"
echo "{\"code\": \"$CODE\", \"dispatch\": \"$DISPATCH\", \"pg_stat\": \"$PGSTAT\", \"target\": \"$TARGET\", \"target_url\": \"$TARGET_URL\", \"rate\": $RATE, \"read_rate\": $READ_RATE, \"dispatch_running\": \"$(docker compose ps --status running --services | grep -c '^dispatch$')\"}" > "$OUT/condition.json"

# ---- warm-up (discarded) ----
truncate_tables
mkdir -p "$OUT/warmup"
docker compose --profile loadtest run --rm --no-deps -e BASE_URL="$TARGET_URL" -e WARMUP=1 \
  -v "$PWD/$OUT/warmup":/out k6 run --summary-export /out/k6-summary.json /loadtest/db-baseline.js \
  > "$OUT/warmup/k6-stdout.log" 2>&1
log "warm-up done (discarded), k6 exit=$?"
log "outbox drained after warm-up in $(wait_drain) s"

# ---- measured run ----
truncate_tables
if [ "$PGSTAT" = on ] && [ "$CODE" = current ]; then psql_db peakload "select pg_stat_statements_reset()" > /dev/null; fi
mkdir -p "$OUT/run"
python3 loadtest/sample.py --out "$OUT/run/samples.jsonl" --interval 2 &
SAMPLER=$!
( while :; do
    echo "{\"ts\": $(date +%s), \"host_cpu_sum_pct\": $(ps -A -o %cpu= | awk '{s+=$1} END {printf "%.1f", s}'), \"top\": \"$(ps -A -o %cpu=,comm= | sort -rn | head -6 | awk '{printf "%s:%s ", $2, $1}')\"}"
    sleep 2
  done ) > "$OUT/run/host-cpu.jsonl" &
HOSTCPU=$!
T0=$(date +%s)
docker compose --profile loadtest run --rm --no-deps -e BASE_URL="$TARGET_URL" \
  -e CREATE_RATE="$RATE" -e READ_RATE="$READ_RATE" -e DURATION=30s \
  -v "$PWD/$OUT/run":/out k6 run --summary-export /out/k6-summary.json \
  /loadtest/db-baseline.js > "$OUT/run/k6-stdout.log" 2>&1
log "measured run done, k6 exit=$?"
DRAIN=$(wait_drain)
log "outbox drained after run in $DRAIN s"
sleep 3
kill "$SAMPLER" "$HOSTCPU" 2>/dev/null; wait "$SAMPLER" "$HOSTCPU" 2>/dev/null
echo "{\"t0\": $T0, \"drain_s\": \"$DRAIN\"}" > "$OUT/run/events.json"

if [ "$PGSTAT" = on ] && [ "$CODE" = current ]; then
  psql_db peakload "select calls, round(total_exec_time::numeric,1) as total_ms, round(mean_exec_time::numeric,3) as mean_ms, left(regexp_replace(query, '\s+', ' ', 'g'), 110) as query from pg_stat_statements order by total_exec_time desc limit 15" > "$OUT/run/pg-stat-top15.txt"
fi

# ---- restore the default stack ----
docker compose exec -T postgres psql -U peakload -d peakload -At \
  -c "ALTER SYSTEM RESET pg_stat_statements.track" -c "SELECT pg_reload_conf()" > /dev/null
docker rm -f w1-order > /dev/null 2>&1
docker compose start order > /dev/null
docker compose start dispatch > /dev/null
docker compose up -d admission > /dev/null
log "done"
