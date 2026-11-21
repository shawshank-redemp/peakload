#!/usr/bin/env bash
# Runs the noisy-neighbour scenario once. Before the run: the outbox is drained and the order tables are truncated,
# as in run-order-create.sh. Admission's rate-limit settings are whatever admission was started with.
#   OUT=<dir> ABUSE_RATE=1200 NORMAL_RATE=20 DURATION=30s bash loadtest/run-noisy.sh
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=${OUT:?set OUT}
ABUSE_RATE=${ABUSE_RATE:-1200}
NORMAL_RATE=${NORMAL_RATE:-20}
DURATION=${DURATION:-30s}
mkdir -p "$OUT"
psql_q() { docker compose exec -T postgres psql -U peakload -d peakload -At -c "$1"; }

for i in $(seq 1 900); do [ "$(psql_q "select count(*) from outbox where published_at is null")" = "0" ] && break; sleep 1; done
psql_q "TRUNCATE orders, idempotency_keys, outbox, processed_events" >/dev/null
echo "tables truncated; running abuse=$ABUSE_RATE normal=$NORMAL_RATE/client for $DURATION" | tee "$OUT/driver.log"

start=$(python3 -c 'from datetime import datetime,timezone; print(datetime.now(timezone.utc).isoformat(timespec="milliseconds"))')
docker compose --profile loadtest run --rm --no-deps \
  -e ABUSE_RATE="$ABUSE_RATE" -e NORMAL_RATE="$NORMAL_RATE" -e DURATION="$DURATION" \
  -e BASE_URL=http://admission:8080 \
  -v "$PWD/$OUT":/out \
  k6 run --summary-export /out/k6-summary.json --out json=/out/points.json.gz /loadtest/noisy-neighbour.js > "$OUT/k6-stdout.log" 2>&1
rc=$?
echo "k6 exit=$rc" | tee -a "$OUT/driver.log"
echo "$start" > "$OUT/started.txt"
