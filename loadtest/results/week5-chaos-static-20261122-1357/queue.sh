#!/usr/bin/env bash
cd /Users/sindhus/Downloads/peakload
R=$1
run() {
  OUT=$R/$1 KILLS=$2 RATE=20 DURATION=150s RIDERS_PER_CITY=1200 DRAIN_LIMIT=900 bash loadtest/chaos/run-chaos.sh > $R/$1.stdout 2>&1
  echo "$1 exit=$?" >> $R/queue.log
  if grep -q drain_failed $R/$1/events.txt 2>/dev/null; then echo "STOP: drain failed in $1" >> $R/queue.log; exit 1; fi
  viol=$(python3 -c "
import json;r=json.load(open('$R/$1/report.json'))
print(sum(r['invariants'].values()) + abs(r['counts']['orders_minus_assignments_and_pending']))" 2>/dev/null || echo ERR)
  echo "$1 invariant_total=$viol" >> $R/queue.log
  if [ "$viol" != "0" ]; then echo "STOP: invariant problem in $1" >> $R/queue.log; exit 1; fi
}
run static-kill-1 3
run static-control-1 0
run static-kill-2 3
run static-control-2 0
echo "all done" >> $R/queue.log
