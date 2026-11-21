#!/usr/bin/env bash
# Order-creation load procedure (Week 1 baseline). Measures; does not tune.
#
#   loadtest/run-order-create.sh smoke    one 5 s run at 20 req/s, to check the pipeline
#   loadtest/run-order-create.sh          full procedure (warm-up, ladder, knee repeats)
#
# Before every run: wait until the outbox backlog is 0, then TRUNCATE the four tables, so each run
# starts from the same state. During a run: sample.py records docker stats, Postgres, Hikari and the
# outbox every 5 s, and keeps sampling until the outbox drains (that is the relay's drain time).
#
# Output: loadtest/results/order-create/<timestamp>/<run>/ with k6-summary.json, samples.jsonl,
# run.json and k6-stdout.log. summarise.py turns those into report.md.
set -uo pipefail
cd "$(dirname "$0")/.."

MODE=${1:-full}                    # full | smoke | extend (continue the ladder in OUT) | list (RATES, fixed)
TARGET_URL=${TARGET_URL:-http://order:8081}   # where k6 sends POST /orders: order directly, or admission
RATES=${RATES:-}
OUT=${OUT:-loadtest/results/order-create/$(date +%Y%m%d-%H%M%S)}
CAP=${CAP:-4000}                  # highest rate tried if the threshold never fails
DRAIN_CAP=${DRAIN_CAP:-900}       # seconds to wait for the outbox to drain before giving up
PY=python3
mkdir -p "$OUT"
LOG="$OUT/driver.log"

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG" >&2; }
now() { python3 -c 'from datetime import datetime,timezone; print(datetime.now(timezone.utc).isoformat(timespec="milliseconds"))'; }
psql_q() { docker compose exec -T postgres psql -U peakload -d peakload -At -c "$1"; }
backlog() { psql_q "select count(*) from outbox where published_at is null"; }

# Waits for the relay to publish everything. Prints the seconds waited, or "null" if it never drained.
wait_drain() {
  local start now_s n
  start=$(date +%s)
  while :; do
    n=$(backlog 2>/dev/null || echo "?")
    if [ "$n" = "0" ]; then
      echo $(( $(date +%s) - start ))
      return 0
    fi
    if [ $(( $(date +%s) - start )) -ge "$DRAIN_CAP" ]; then
      echo "null"
      return 1
    fi
    sleep 1
  done
}

reset_state() {
  local waited
  waited=$(wait_drain) || log "WARNING: outbox did not drain before reset (backlog $(backlog 2>/dev/null))"
  log "outbox drained before reset after ${waited}s"
  psql_q "TRUNCATE orders, idempotency_keys, outbox, processed_events" >/dev/null
  log "tables truncated"
}

# run_one <label> <rate> <duration>
run_one() {
  local label=$1 rate=$2 dur=$3
  local dir="$OUT/$label"
  mkdir -p "$dir"
  log "=== $label: RATE=$rate DURATION=$dur"
  reset_state

  "$PY" loadtest/sample.py --out "$dir/samples.jsonl" --interval 5 &
  local sampler=$!

  local k6_start k6_end k6_rc drain
  k6_start=$(now)
  docker compose --profile loadtest run --rm --no-deps \
    -e RATE="$rate" -e DURATION="$dur" -e BASE_URL="$TARGET_URL" \
    -v "$PWD/$dir":/out \
    k6 run --summary-export /out/k6-summary.json /loadtest/order-create.js > "$dir/k6-stdout.log" 2>&1
  k6_rc=$?
  k6_end=$(now)

  # The sampler keeps running while the relay drains, so the backlog curve after the run is recorded.
  drain=$(wait_drain) || true
  kill "$sampler" 2>/dev/null; wait "$sampler" 2>/dev/null

  cat > "$dir/run.json" <<EOF
{"label": "$label", "rate": $rate, "duration": "$dur", "k6_start": "$k6_start", "k6_end": "$k6_end",
 "k6_exit": $k6_rc, "drain_seconds": $drain}
EOF
  local verdict
  if [ -f "$dir/k6-summary.json" ]; then
    verdict=$("$PY" loadtest/summarise.py verdict "$dir/k6-summary.json" 2>"$dir/verdict.stderr")
  else
    verdict="NO-SUMMARY"
  fi
  log "$label: verdict=$verdict k6_exit=$k6_rc drain_seconds=$drain"
  echo "$verdict"
}

if [ "$MODE" = "smoke" ]; then
  run_one smoke-r20 20 5s
  exit 0
fi

log "output: $OUT ($MODE)"

VERDICTS="$OUT/verdicts.txt"   # "<rate> <PASS|FAIL>" per ladder run; bash 3.2 has no associative arrays
touch "$VERDICTS"
verdict_of() { awk -v r="$1" '$1==r {v=$2} END {print v}' "$VERDICTS"; }
run_rate() {
  local r=$1 label=$2 v
  v=$(run_one "$label" "$r" 30s)
  echo "$r $v" >> "$VERDICTS"
}

FIXED=(50 100 200 400 800)
if [ "$MODE" = "list" ]; then
  # Fixed rates against TARGET_URL: warm-up (discarded), then one 30 s run per rate, data reset before each.
  log "target $TARGET_URL, rates: $RATES"
  run_one warmup-r20 20 30s > /dev/null
  for r in $RATES; do run_rate "$r" "$(printf 'r%04d' "$r")"; done
  log "done. Results: $OUT"
  exit 0
fi
if [ "$MODE" = "extend" ]; then
  # Continue an existing run directory: keep its verdicts, and extend from the highest rate run so far.
  next=$(( $(awk '{print $1}' "$VERDICTS" | sort -n | tail -1) + 400 ))
  log "extending from $next req/s (cap $CAP)"
else
  run_one warmup-r20 20 30s > /dev/null   # discarded: JIT and pool warm-up
  for r in "${FIXED[@]}"; do
    run_rate "$r" "$(printf 'r%04d' "$r")"
  done
  next=1200
fi

# Extend upward in steps of 400 until the threshold fails, then run two more steps past the failure.
first_fail=$(awk '$2=="FAIL" {print $1; exit}' "$VERDICTS" | sort -n | head -1)
while :; do
  if [ -n "$first_fail" ]; then
    past=$(awk -v f="$first_fail" '$1>f' "$VERDICTS" | wc -l | tr -d ' ')
    [ "$past" -ge 2 ] && break
  fi
  if [ "$next" -gt "$CAP" ]; then
    log "cap $CAP reached without a threshold failure"
    break
  fi
  run_rate "$next" "$(printf 'r%04d' "$next")"
  if [ "$(verdict_of "$next")" = "FAIL" ] && [ -z "$first_fail" ]; then first_fail=$next; fi
  next=$((next + 400))
done

if [ -n "$first_fail" ]; then
  log "knee (lowest rate where the threshold fails): $first_fail req/s"
  # Rate just below the knee: the highest passing rate under it.
  below=$(awk -v f="$first_fail" '$2=="PASS" && $1<f {print $1}' "$VERDICTS" | sort -n | tail -1)
  [ -n "$below" ] && run_rate "$below" "$(printf 'rep-r%04d' "$below")"
  run_rate "$first_fail" "$(printf 'rep-r%04d' "$first_fail")"
else
  log "no knee found up to the cap; no knee repeats"
fi
log "done. Results: $OUT"
