#!/usr/bin/env bash
# Batch for Week 6 step 2 phase 1. Runs in order; stops at the first run that fails or breaks an invariant.
cd /Users/sindhus/Downloads/peakload
ROOT=$1
Q=$ROOT/queue.log
run() { # $1 = folder name, rest = environment for run-step2.sh
  local name=$1; shift
  echo "[$(date +%H:%M:%S)] start $name" >> "$Q"
  env "$@" OUT="$ROOT/$name" bash loadtest/week6/run-step2.sh > "$ROOT/$name.stdout" 2>&1 \
    || { echo "[$(date +%H:%M:%S)] STOP $name: run-step2 exit $?" >> "$Q"; exit 1; }
  python3 loadtest/week6/analyse_step2.py "$ROOT/$name" > "$ROOT/$name.analysis.stdout" 2>&1 \
    || { echo "[$(date +%H:%M:%S)] STOP $name: invariant or analysis failed" >> "$Q"; exit 1; }
  echo "[$(date +%H:%M:%S)] done $name" >> "$Q"
}
run direct-2000-1 MODE=direct RATE=2000
run direct-3200-1 MODE=direct RATE=3200
run direct-4400-1 MODE=direct RATE=4400
run noisy-off-1 MODE=noisy LIMITER=off
run noisy-on-1  MODE=noisy LIMITER=on
run noisy-off-2 MODE=noisy LIMITER=off
run noisy-on-2  MODE=noisy LIMITER=on
run noisy-off-3 MODE=noisy LIMITER=off
run noisy-on-3  MODE=noisy LIMITER=on
run nye-off-1 MODE=nye SHED=off PEAK_CRITICAL=2000
run nye-on-1  MODE=nye SHED=on  PEAK_CRITICAL=2000
run nye-off-2 MODE=nye SHED=off PEAK_CRITICAL=2000
run nye-on-2  MODE=nye SHED=on  PEAK_CRITICAL=2000
run nye-off-3 MODE=nye SHED=off PEAK_CRITICAL=2000
run nye-on-3  MODE=nye SHED=on  PEAK_CRITICAL=2000
echo "[$(date +%H:%M:%S)] PHASE 1 COMPLETE" >> "$Q"
