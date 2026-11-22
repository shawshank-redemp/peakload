#!/usr/bin/env bash
# Week 6, step 1: the ramp (800..4000 req/s, stop at the first rate with more than 5% non-2xx), then two alternating
# repeats at the last passing and the first failing rate, then one run at the first failing rate with shedding off.
#   bash loadtest/week6/series.sh <results dir>
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=${1:?results dir}; mkdir -p "$ROOT"
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$ROOT/queue.log"; }
run() {  # $1 = label, $2 = rate, $3 = shed
  OUT="$ROOT/$1" RATE=$2 SHED=$3 bash loadtest/week6/run-ramp.sh > "$ROOT/$1.stdout" 2>&1
  python3 loadtest/week6/analyse_ramp.py "$ROOT/$1" > "$ROOT/$1/analysis.txt" 2>&1
  pct=$(grep -o "non-2xx share: [0-9.]*" "$ROOT/$1/analysis.txt" | awk '{print $3}')
  say "$1 rate=$2 shed=$3 non2xx=${pct}%"
  echo "$pct"
}
last_pass=""; first_fail=""
for r in 800 1600 2400 3200 4000; do
  pct=$(run "ramp-$r" $r on)
  if awk -v p="$pct" 'BEGIN{exit !(p>5)}'; then first_fail=$r; say "STOP RAMP: first failing rate $r"; break; fi
  last_pass=$r
done
if [ -n "$first_fail" ] && [ -n "$last_pass" ]; then
  for i in 1 2; do
    run "repeat-pass-$last_pass-$i" $last_pass on > /dev/null
    run "repeat-fail-$first_fail-$i" $first_fail on > /dev/null
  done
  run "shed-off-$first_fail" $first_fail off > /dev/null
fi
say "all done"
