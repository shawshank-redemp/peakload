#!/usr/bin/env bash
# Runs 05-range-compare.sql three times (alternating variants inside each run), after the pre-run check.
#   OUT=<dir> DB=peakload_exp2 bash loadtest/partition/run-range.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
OUT=${OUT:?set OUT}; DB=${DB:-peakload_exp}; mkdir -p "$OUT"
for i in 1 2 3; do
  bash loadtest/precheck.sh "$OUT/precheck-$i" > /dev/null || { echo "precheck refused, repeat $i" >> "$OUT/driver.log"; sleep 60; bash loadtest/precheck.sh "$OUT/precheck-$i" > /dev/null || continue; }
  docker compose exec -T postgres psql -U peakload -d "$DB" -f - < loadtest/partition/05-range-compare.sql > "$OUT/range-$i.txt" 2>&1
  echo "repeat $i exit=$?" >> "$OUT/driver.log"
done
