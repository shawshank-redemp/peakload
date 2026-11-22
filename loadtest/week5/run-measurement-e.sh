#!/usr/bin/env bash
# Week 5, measurement E: ON, OFF, ON, OFF at 800 req/s, order service killed at +30 s and restarted at +60 s.
#   bash loadtest/week5/run-measurement-e.sh <results dir>
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=${1:?results dir}; mkdir -p "$ROOT"
i=1
for mode in on off on off; do
  OUT="$ROOT/run$i-$mode" MODE=$mode RATE=800 SECONDS_RUN=90 KILL=1 bash loadtest/week5/run-kill.sh > "$ROOT/run$i-$mode.stdout" 2>&1
  echo "run$i-$mode exit=$?" >> "$ROOT/queue.log"
  i=$((i+1))
done
echo "all done" >> "$ROOT/queue.log"
