#!/usr/bin/env bash
# Week 5, measurement E: one run. Every run starts from reset tables, after a discarded warm-up.
#   OUT=<dir> MODE=on|off RATE=800 SECONDS_RUN=90 [KILL=0] bash loadtest/week5/run-kill.sh
# on  = RETRY_ENABLED=true and CIRCUIT_ENABLED=true;  off = both false. Admission is recreated for each run,
# so its counters start at zero. Order: pre-run check, reset, admission restart, WARM-UP (20 s, discarded),
# reset again, pre-run check (records the table state), timed run. KILL=1 (default): order stopped at +30 s,
# started at +60 s. KILL=0: no kill (a clean check).
set -uo pipefail
cd "$(dirname "$0")/../.."
OUT=${OUT:?set OUT}; MODE=${MODE:?on|off}; RATE=${RATE:-800}; DUR=${SECONDS_RUN:-90}; KILL=${KILL:-1}
mkdir -p "$OUT"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/driver.log"; }
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }
gate() { local dir=$1; until bash loadtest/precheck.sh "$dir" > /dev/null; do log "precheck refused ($dir), waiting"; sleep 30; done; }
pgq() { docker compose exec -T postgres psql -U peakload -d peakload -At -c "$1"; }
pg_counters() {  # checkpoints and autovacuum counts, for a stall to be matched to a cause later
  pgq "select 'checkpoints_timed='||checkpoints_timed||' checkpoints_req='||checkpoints_req||' buffers_checkpoint='||buffers_checkpoint from pg_stat_bgwriter"
  pgq "select 'autovacuum_count='||coalesce(sum(autovacuum_count),0)||' autoanalyze_count='||coalesce(sum(autoanalyze_count),0) from pg_stat_user_tables where schemaname='public'"
}
reset_tables() {  # the standard reset: empty the order and dispatch tables (riders kept, set AVAILABLE)
  EMPTY=1 bash loadtest/db/seed.sh > /dev/null
}
mix_run() {  # $1 = out dir, $2 = duration, $3 = T0
  docker compose --profile loadtest run --rm --no-deps -e BASE_URL=http://admission:8080 -e RATE="$RATE" \
    -e DURATION="$2" -e T0="$3" -v "$PWD/$1":/out k6 run --out json=/out/points.json.gz \
    /loadtest/week5/mix.js > "$1/k6-stdout.log" 2>&1
}

gate "$OUT/precheck-before-reset"
log "run mode=$MODE rate=$RATE duration=${DUR}s kill=$KILL"
docker compose stop dispatch > /dev/null
docker compose start order > /dev/null
# RETRY (on|off) sets the retry flag; defaults to MODE. The breaker flag is always on unless CIRCUIT_ENABLED_FLAG says otherwise.
[ "${RETRY:-$MODE}" = on ] && RFLAG=true || RFLAG=false
CFLAG=${CIRCUIT_ENABLED_FLAG:-true}
reset_tables
RETRY_ENABLED=$RFLAG CIRCUIT_ENABLED=$CFLAG docker compose up -d --force-recreate admission > /dev/null 2>&1
for t in $(seq 1 60); do curl -s -o /dev/null localhost:8080/actuator/health && break; sleep 1; done
for t in $(seq 1 60); do docker compose exec -T order bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/health HTTP/1.0\r\nHost: x\r\n\r\n" >&3 && grep -q UP <&3' 2>/dev/null && break; sleep 1; done
log "warm-up 20 s at $RATE/s (discarded)"
mkdir -p "$OUT/warmup"
mix_run "$OUT/warmup" 20s "$(now_ms)"
reset_tables
gate "$OUT/precheck-after-reset"
pg_counters > "$OUT/pg-before.txt"
pgq "select 'orders_before='||count(*) from orders" >> "$OUT/pg-before.txt"

mkdir -p "$OUT/k6"
python3 loadtest/sample.py --out "$OUT/samples.jsonl" --interval 2 &
SAMPLER=$!
T0=$(now_ms)
echo "T0=$T0" > "$OUT/events.txt"
mix_run "$OUT/k6" "${DUR}s" "$T0" &
K6=$!
if [ "$KILL" = 1 ]; then
  # KILL_MODE=stop (default): the container is stopped and started. KILL_MODE=pause: the process is frozen and
  # thawed (docker pause/unpause). The event names in events.txt are the same for both: stop_order / start_order.
  if [ "${KILL_MODE:-stop}" = pause ]; then
    sleep 30; docker compose pause order > /dev/null; echo "stop_order=$(now_ms)" >> "$OUT/events.txt"; log "order paused"
    sleep 30; docker compose unpause order > /dev/null; echo "start_order=$(now_ms)" >> "$OUT/events.txt"; log "order unpaused"
  else
    sleep 30; docker compose stop order > /dev/null; echo "stop_order=$(now_ms)" >> "$OUT/events.txt"; log "order stopped"
    sleep 30; docker compose start order > /dev/null; echo "start_order=$(now_ms)" >> "$OUT/events.txt"; log "order started"
  fi
fi
wait $K6; log "k6 exit=$?"
docker compose unpause order > /dev/null 2>&1 || true   # never leave the order service frozen
kill $SAMPLER 2>/dev/null; wait $SAMPLER 2>/dev/null
pg_counters > "$OUT/pg-after.txt"
pgq "select 'orders_after='||count(*) from orders" >> "$OUT/pg-after.txt"
pgq "select 'duplicate_client_key_pairs='||count(*) from (select client_id, idempotency_key from idempotency_keys group by 1,2 having count(*)>1) d" >> "$OUT/pg-after.txt"
curl -s localhost:8080/actuator/prometheus | grep -E "^admission_(upstream|circuit|retry|requests_total)" > "$OUT/admission-metrics.txt"
docker compose logs --no-color admission > "$OUT/admission-log.txt" 2>&1
docker compose start dispatch > /dev/null
log "done"
