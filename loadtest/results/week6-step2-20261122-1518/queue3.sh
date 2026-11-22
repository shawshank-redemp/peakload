#!/usr/bin/env bash
# Resume after the nye-off-1 stop (its invariants were corrected and it passes). Stops only on an invariant failure
# (duplicate pair, 201 without an order, orders beyond the non-201 POST answers, unpublished outbox row) or a run error.
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
run nye-on-1  MODE=nye SHED=on  PEAK_CRITICAL=2000
run nye-off-2 MODE=nye SHED=off PEAK_CRITICAL=2000
run nye-on-2  MODE=nye SHED=on  PEAK_CRITICAL=2000
run nye-off-3 MODE=nye SHED=off PEAK_CRITICAL=2000
run nye-on-3  MODE=nye SHED=on  PEAK_CRITICAL=2000
echo "[$(date +%H:%M:%S)] PHASE 1 NYE COMPLETE" >> "$Q"
run interleave-3200-a MODE=direct RATE=3200
run interleave-2000-a MODE=direct RATE=2000
run interleave-4400-a MODE=direct RATE=4400
run interleave-2000-b MODE=direct RATE=2000
run interleave-3200-b MODE=direct RATE=3200
run interleave-2000-c MODE=direct RATE=2000
echo "[$(date +%H:%M:%S)] PHASE 1B COMPLETE" >> "$Q"
