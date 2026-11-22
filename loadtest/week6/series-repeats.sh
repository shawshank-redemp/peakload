#!/usr/bin/env bash
# Week 6, step 1, after the ramp: the ramp stopped at 1600 req/s (first rate with more than 5% non-2xx; 800 was the last
# passing rate). Two more alternating runs at 800 and at 1600 (three runs each in total), then one run at 1600
# req/s with shedding disabled. Status lines go to queue.log only, not to standard output.
#   bash loadtest/week6/series-repeats.sh <results dir> <last pass rate> <first fail rate>
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=${1:?results dir}; PASS=${2:?last pass}; FAIL=${3:?first fail}
say() { echo "[$(date +%H:%M:%S)] $*" >> "$ROOT/queue.log"; }
run() {  # $1 = label, $2 = rate, $3 = shed
  OUT="$ROOT/$1" RATE=$2 SHED=$3 bash loadtest/week6/run-ramp.sh > "$ROOT/$1.stdout" 2>&1
  python3 loadtest/week6/analyse_ramp.py "$ROOT/$1" > "$ROOT/$1/analysis.txt" 2>&1
  say "$1 rate=$2 shed=$3 $(grep -o 'non-2xx share: [0-9.]*%' "$ROOT/$1/analysis.txt")"
}
for i in 2 3; do
  run "repeat-$PASS-$i" $PASS on
  run "repeat-$FAIL-$i" $FAIL on
done
run "shed-off-$FAIL" $FAIL off
say "all done"
