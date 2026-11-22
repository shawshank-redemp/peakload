#!/usr/bin/env bash
cd /Users/sindhus/Downloads/peakload
R=$1
for i in 1 2; do
  OUT=$R/baseline-$i KILLS=0 RATE=20 DURATION=60s RIDERS_PER_CITY=480 bash loadtest/chaos/run-chaos.sh > $R/baseline-$i.stdout 2>&1
  echo "baseline-$i exit=$?" >> $R/queue.log
done
echo "all done" >> $R/queue.log
