#!/usr/bin/env bash
# Week 5, step 2: retry measurement with docker pause on the order service. 800 req/s, breaker on in every run,
# retries ON and OFF alternating, two runs each. Pause at +30 s, unpause at +60 s.
#   bash loadtest/week5/run-retry-pause.sh <results dir>
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=${1:?results dir}; mkdir -p "$ROOT"
i=1
for retry in on off on off; do
  OUT="$ROOT/run$i-retry-$retry" RETRY=$retry MODE=$retry KILL_MODE=pause CIRCUIT_ENABLED_FLAG=true RATE=800 SECONDS_RUN=90 KILL=1 \
    bash loadtest/week5/run-kill.sh > "$ROOT/run$i-retry-$retry.stdout" 2>&1
  echo "run$i-retry-$retry exit=$?" >> "$ROOT/queue.log"
  i=$((i+1))
done
echo "all done" >> "$ROOT/queue.log"
