#!/usr/bin/env bash
cd /Users/sindhus/Downloads/peakload
R=$1
run() {  # $1 = label, $2 = KILLS
  OUT=$R/$1 KILLS=$2 RATE=20 DURATION=150s RIDERS_PER_CITY=1200 DRAIN_LIMIT=300 bash loadtest/chaos/run-chaos.sh > $R/$1.stdout 2>&1
  echo "$1 exit=$?" >> $R/queue.log
  viol=$(python3 -c "
import json;r=json.load(open('$R/$1/report.json'))
print(sum(r['invariants'].values()) + abs(r['counts']['orders_minus_assignments_and_pending']))" 2>/dev/null || echo ERR)
  echo "$1 invariant_total=$viol" >> $R/queue.log
  if [ "$viol" != "0" ]; then echo "STOP: invariant problem in $1" >> $R/queue.log; exit 1; fi
}
run kill-1 3
run control-1 0
run kill-2 3
run control-2 0
echo "all done" >> $R/queue.log
