#!/usr/bin/env bash
# Phase 1b: interleaved 2000 req/s repeats, to separate a first-run effect from a rate effect. Starts only when
# phase 1 completes cleanly; stops at the first failed run or invariant.
cd /Users/sindhus/Downloads/peakload
ROOT=$1
Q=$ROOT/queue.log
until grep -q "PHASE 1 COMPLETE" "$Q" 2>/dev/null; do
  if grep -q "STOP" "$Q" 2>/dev/null; then echo "phase 1 stopped; phase 1b not started" >> "$Q"; exit 1; fi
  sleep 20
done
run() {
  local name=$1; shift
  echo "[$(date +%H:%M:%S)] start $name" >> "$Q"
  env "$@" OUT="$ROOT/$name" bash loadtest/week6/run-step2.sh > "$ROOT/$name.stdout" 2>&1 \
    || { echo "[$(date +%H:%M:%S)] STOP $name: run-step2 exit $?" >> "$Q"; exit 1; }
  python3 loadtest/week6/analyse_step2.py "$ROOT/$name" > "$ROOT/$name.analysis.stdout" 2>&1 \
    || { echo "[$(date +%H:%M:%S)] STOP $name: invariant or analysis failed" >> "$Q"; exit 1; }
  echo "[$(date +%H:%M:%S)] done $name" >> "$Q"
}
run interleave-3200-a MODE=direct RATE=3200
run interleave-2000-a MODE=direct RATE=2000
run interleave-4400-a MODE=direct RATE=4400
run interleave-2000-b MODE=direct RATE=2000
run interleave-3200-b MODE=direct RATE=3200
run interleave-2000-c MODE=direct RATE=2000
echo "[$(date +%H:%M:%S)] PHASE 1B COMPLETE" >> "$Q"
