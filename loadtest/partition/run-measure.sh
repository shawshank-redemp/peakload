#!/usr/bin/env bash
# Week 4 step 3: runs the deterministic measures three times (full output kept), after the pre-run check.
#   OUT=<dir> bash loadtest/partition/run-measure.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
OUT=${OUT:?set OUT}; mkdir -p "$OUT"
bash loadtest/precheck.sh "$OUT" > /dev/null || { echo "precheck refused" > "$OUT/REFUSED"; exit 2; }
for i in 1 2 3; do
  docker compose exec -T postgres psql -U peakload -d peakload_exp -f - < loadtest/partition/02-measure-deterministic.sql > "$OUT/measure-$i.txt" 2>&1
  echo "measure $i exit=$?" >> "$OUT/driver.log"
done
