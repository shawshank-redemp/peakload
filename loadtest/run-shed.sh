#!/usr/bin/env bash
# One load-shedding run, after a fresh admission start. Warm-up first (phase 1 only, discarded), then the measured
# run of four phases. Admission is restarted with SHED_ENABLED as asked. The rate limiter is set so it never limits
# this load; the limit is not what is being measured.
#   OUT=<dir> SHED=true|false CRIT_RATE=800 STD_RATE=50 LOW_RATES=400,1200,2400 PHASE_LEN=30s bash loadtest/run-shed.sh
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=${OUT:?set OUT}; SHED=${SHED:?true|false}
CRIT_RATE=${CRIT_RATE:-800}; STD_RATE=${STD_RATE:-50}; LOW_RATES=${LOW_RATES:-400,1200,2400}; PHASE_LEN=${PHASE_LEN:-30s}
mkdir -p "$OUT"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/driver.log"; }
psql_q() { docker compose exec -T postgres psql -U peakload -d peakload -At -c "$1"; }
now() { python3 -c 'import time; print(f"{time.time():.3f}")'; }

# Admission: shedding as asked; limiter present but with limits far above this load.
SHED_ENABLED=$SHED \
  RATE_LIMIT_ENABLED=true \
  RATE_LIMIT_CRITICAL_CAPACITY=1000000 RATE_LIMIT_CRITICAL_REFILL=1000000 \
  RATE_LIMIT_STANDARD_CAPACITY=1000000 RATE_LIMIT_STANDARD_REFILL=1000000 \
  RATE_LIMIT_LOW_CAPACITY=1000000 RATE_LIMIT_LOW_REFILL=1000000 \
  docker compose up -d --force-recreate admission > /dev/null 2>&1
for i in $(seq 1 60); do [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/ping)" = "200" ] && break; sleep 2; done
log "admission up; SHED_ENABLED=$SHED crit=$CRIT_RATE std=$STD_RATE low=$LOW_RATES phase=$PHASE_LEN"

k6_run() { # $1 = output folder name inside OUT, rest = extra -e args
  local name=$1; shift
  mkdir -p "$OUT/$name"
  docker compose --profile loadtest run --rm --no-deps \
    -e BASE_URL=http://admission:8080 -e CRIT_RATE="$CRIT_RATE" -e STD_RATE="$STD_RATE" \
    -e PHASE_LEN="$PHASE_LEN" "$@" \
    -v "$PWD/$OUT/$name":/out \
    k6 run --summary-export /out/k6-summary.json --out json=/out/points.json.gz /loadtest/shed-mix.js \
    > "$OUT/$name/k6-stdout.log" 2>&1
}

drain_and_truncate() {
  for i in $(seq 1 900); do [ "$(psql_q "select count(*) from outbox where published_at is null")" = "0" ] && break; sleep 1; done
  psql_q "TRUNCATE orders, idempotency_keys, outbox, processed_events" > /dev/null
}

# Warm-up: phase 1 only, discarded.
drain_and_truncate
k6_run warmup -e ONLY_FIRST=1 -e LOW_RATES=0,0,0
log "warm-up done (discarded), exit=$?"

# Measured run.
drain_and_truncate
python3 loadtest/shed_sampler.py "$OUT/shed.csv" &
SAMPLER=$!
python3 loadtest/sample.py --out "$OUT/stack.jsonl" --interval 5 &
STACK=$!

T0=$(now)
echo "t0=$T0" > "$OUT/events.txt"
k6_run run -e LOW_RATES="$LOW_RATES"
log "measured run done, k6 exit=$?"

sleep 3
kill $SAMPLER $STACK 2>/dev/null; wait $SAMPLER $STACK 2>/dev/null
curl -s localhost:8080/actuator/prometheus > "$OUT/admission-metrics-final.txt"
log "done"
