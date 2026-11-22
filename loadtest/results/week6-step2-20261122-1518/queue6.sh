#!/usr/bin/env bash
# Phase 2 (decision 2): three runs at 4400 req/s, interleaved with one 3200 and one 2000 run. Same stop rule.
cd /Users/sindhus/Downloads/peakload
ROOT=$1
Q=$ROOT/queue.log
run() {
  local name=$1; shift
  echo "[$(date +%H:%M:%S)] start $name" >> "$Q"
  env "$@" OUT="$ROOT/$name" bash loadtest/week6/run-step2.sh > "$ROOT/$name.stdout" 2>&1 \
    || { echo "[$(date +%H:%M:%S)] STOP $name: run-step2 exit $?" >> "$Q"; exit 1; }
  python3 loadtest/week6/analyse_step2.py "$ROOT/$name" > "$ROOT/$name.analysis.stdout" 2>&1 \
    || { echo "[$(date +%H:%M:%S)] STOP $name: invariant failed" >> "$Q"; exit 1; }
  echo "[$(date +%H:%M:%S)] done $name" >> "$Q"
}
run phase2b-4400-c MODE=direct RATE=4400
run phase2b-3200-c MODE=direct RATE=3200
run phase2b-4400-d MODE=direct RATE=4400
run phase2b-2000-d MODE=direct RATE=2000
run phase2b-4400-e MODE=direct RATE=4400
echo "[$(date +%H:%M:%S)] PHASE 2B COMPLETE" >> "$Q"
