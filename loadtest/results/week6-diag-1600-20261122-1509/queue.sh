#!/usr/bin/env bash
cd /Users/sindhus/Downloads/peakload
R=$1
for i in 1 2 3; do
  OUT=$R/diag-$i RATE=1600 SHED=on bash loadtest/week6/run-ramp.sh > $R/diag-$i.stdout 2>&1
  python3 loadtest/week6/analyse_ramp.py $R/diag-$i > $R/diag-$i/analysis.txt 2>&1
  echo "diag-$i $(grep -o 'non-2xx share: [0-9.]*%' $R/diag-$i/analysis.txt)" >> $R/queue.log
done
echo "all done" >> $R/queue.log
