#!/usr/bin/env bash
# Phase 2: the third run at the first failing rate (4400 req/s), after the batch completes. Same stop rule.
cd /Users/sindhus/Downloads/peakload
ROOT=$1
Q=$ROOT/queue.log
until grep -q "PHASE 1B COMPLETE" "$Q" 2>/dev/null; do
  grep -q "STOP" "$Q" 2>/dev/null && exit 1
  sleep 20
done
run() {
  local name=$1; shift
  echo "[$(date +%H:%M:%S)] start $name" >> "$Q"
  env "$@" OUT="$ROOT/$name" bash loadtest/week6/run-step2.sh > "$ROOT/$name.stdout" 2>&1 \
    || { echo "[$(date +%H:%M:%S)] STOP $name: run-step2 exit $?" >> "$Q"; exit 1; }
  python3 loadtest/week6/analyse_step2.py "$ROOT/$name" > "$ROOT/$name.analysis.stdout" 2>&1 \
    || { echo "[$(date +%H:%M:%S)] STOP $name: invariant failed" >> "$Q"; exit 1; }
  echo "[$(date +%H:%M:%S)] done $name" >> "$Q"
}
run phase2-4400-b MODE=direct RATE=4400
echo "[$(date +%H:%M:%S)] PHASE 2 COMPLETE" >> "$Q"
