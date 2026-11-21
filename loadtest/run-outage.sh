#!/usr/bin/env bash
# One Redis-outage run. Noisy-neighbour load for 90 s; Redis is taken down at +30 s and back at +60 s.
#   OUT=<dir> OUTAGE=stop|pause BREAKER=true|false bash loadtest/run-outage.sh
#   stop  = docker compose stop  (connection refused: fails at once)
#   pause = docker compose pause (Redis accepts nothing: every call waits for the 50 ms command timeout)
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=${OUT:?set OUT}; OUTAGE=${OUTAGE:?stop|pause}; BREAKER=${BREAKER:-true}
DURATION=90s; DOWN_AT=30; UP_AT=60
mkdir -p "$OUT"
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/driver.log"; }
psql_q() { docker compose exec -T postgres psql -U peakload -d peakload -At -c "$1"; }

# Admission: limiter on, CRITICAL 100 burst / 50 per s (as Step 2), breaker as asked.
RATE_LIMIT_ENABLED=true RATE_LIMIT_CRITICAL_CAPACITY=100 RATE_LIMIT_CRITICAL_REFILL=50 \
  RATE_LIMIT_BREAKER_ENABLED="$BREAKER" docker compose up -d admission > /dev/null 2>&1
for i in $(seq 1 60); do [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:8080/api/ping)" = "200" ] && break; sleep 2; done
docker compose exec -T redis redis-cli PING > /dev/null
log "admission up; breaker=$BREAKER outage=$OUTAGE"

for i in $(seq 1 900); do [ "$(psql_q "select count(*) from outbox where published_at is null")" = "0" ] && break; sleep 1; done
psql_q "TRUNCATE orders, idempotency_keys, outbox, processed_events" > /dev/null

python3 loadtest/outage_sampler.py "$OUT/limiter.csv" &
SAMPLER=$!

T0=$(python3 -c 'import time; print(f"{time.time():.3f}")')
echo "t0=$T0" > "$OUT/events.txt"
docker compose --profile loadtest run --rm --no-deps \
  -e ABUSE_RATE=1200 -e NORMAL_RATE=20 -e DURATION="$DURATION" -e BASE_URL=http://admission:8080 \
  -v "$PWD/$OUT":/out \
  k6 run --summary-export /out/k6-summary.json --out json=/out/points.json.gz /loadtest/noisy-neighbour.js \
  > "$OUT/k6-stdout.log" 2>&1 &
K6=$!

sleep "$DOWN_AT"
T_DOWN=$(python3 -c 'import time; print(f"{time.time():.3f}")')
if [ "$OUTAGE" = "stop" ]; then docker compose stop redis > /dev/null; else docker compose pause redis > /dev/null; fi
echo "t_down=$T_DOWN" >> "$OUT/events.txt"; log "Redis $OUTAGE at $T_DOWN"

sleep $((UP_AT - DOWN_AT))
T_UP=$(python3 -c 'import time; print(f"{time.time():.3f}")')
if [ "$OUTAGE" = "stop" ]; then docker compose start redis > /dev/null; else docker compose unpause redis > /dev/null; fi
echo "t_up=$T_UP" >> "$OUT/events.txt"; log "Redis back at $T_UP"

wait $K6; log "k6 exit=$?"
sleep 5
kill $SAMPLER 2>/dev/null; wait $SAMPLER 2>/dev/null
docker compose logs --timestamps admission 2>&1 | grep "rate limiter" > "$OUT/limiter-log.txt"
log "done"
