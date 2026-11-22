#!/usr/bin/env bash
# Week 4 step 5, item 2: cold versus warmed admission, alternating, two runs each. Creates through admission with
# the limiter and shedding off (as in the bisect), dispatch stopped, empty tables, pre-run check before each run.
#   OUT=<dir> bash loadtest/step2/cold-start.sh
#   cold : admission freshly recreated, then the 30 s measured run starts at once (steady rate from the start)
#   warm : admission freshly recreated, a 30 s unmeasured run at the full rate, then the 30 s measured run
set -uo pipefail
cd "$(dirname "$0")/../.."
OUT=${OUT:?set OUT}; mkdir -p "$OUT"
RATE=${RATE:-4400}
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/driver.log"; }
recreate_admission() {
  RATE_LIMIT_ENABLED=false SHED_ENABLED=false docker compose up -d --force-recreate admission > /dev/null 2>&1
  for t in $(seq 1 60); do curl -s -o /dev/null localhost:8080/actuator/health && break; sleep 1; done
}
k6_run() {  # $1 = out dir, $2 = duration
  docker compose --profile loadtest run --rm --no-deps -e BASE_URL=http://admission:8080 \
    -e CREATE_RATE="$RATE" -e DURATION="$2" -v "$PWD/$1":/out k6 run --summary-export /out/k6-summary.json \
    /loadtest/step2/cold-start-probe.js > "$1/k6-stdout.log" 2>&1
}
docker compose stop dispatch > /dev/null
for rep in 1 2; do
  for mode in cold warm; do
    d="$OUT/r$rep-$mode"; mkdir -p "$d"
    until bash loadtest/precheck.sh "$d/precheck" > /dev/null; do log "$mode r$rep: precheck refused, waiting"; sleep 30; done
    EMPTY=1 bash loadtest/db/seed.sh > /dev/null
    recreate_admission
    echo "{\"mode\": \"$mode\", \"rate\": $RATE, \"limiter\": \"off\", \"shedding\": \"off\", \"recreated_at\": \"$(date -u +%H:%M:%S)\"}" > "$d/condition.json"
    if [ "$mode" = warm ]; then
      mkdir -p "$d/ramp"; k6_run "$d/ramp" 30s; log "$mode r$rep: full-rate ramp done (not measured)"
    fi
    mkdir -p "$d/run"; k6_run "$d/run" 30s
    log "$mode r$rep: measured run exit=$?"
    curl -s localhost:8080/actuator/prometheus | grep -E "^admission_upstream_errors_total|^admission_requests_total" > "$d/run/admission-metrics.txt"
    docker compose logs --no-color admission > "$d/run/admission-log.txt" 2>&1
  done
done
docker compose start dispatch > /dev/null
log "done"
