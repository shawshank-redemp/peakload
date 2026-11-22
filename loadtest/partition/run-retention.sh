#!/usr/bin/env bash
# Week 4 step 4: batched DELETE (flat) vs DETACH + DROP (monthly partitions), three alternating repeats.
# Each operation runs while a concurrent insert-only pgbench load (8 clients) writes to the same table, so the
# effect on write latency is measured (pgbench -l per-transaction log). Experimental database only.
#   OUT=<dir> bash loadtest/partition/run-retention.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
OUT=${OUT:?set OUT}; mkdir -p "$OUT"
CUT=2026-05-01
PSQL() { docker compose exec -T postgres psql -U peakload -d peakload_exp -v ON_ERROR_STOP=1 "$@"; }
gate() {  # waits for the pre-run check, up to 10 minutes
  local dir=$1 tries=0
  until bash loadtest/precheck.sh "$dir" > /dev/null; do
    tries=$((tries + 1)); echo "precheck refused (try $tries)" >> "$OUT/driver.log"
    [ $tries -ge 60 ] && return 1; sleep 10
  done
}
for rep in 1 2 3; do
  if [ $((rep % 2)) -eq 1 ]; then order="batch drop"; else order="drop batch"; fi
  gate "$OUT/precheck-$rep-setup" || { echo "rep $rep setup: giving up" >> "$OUT/driver.log"; continue; }
  t0=$(date +%s)
  PSQL -q -f - < loadtest/partition/03-retention-setup.sql > "$OUT/setup-$rep.txt" 2>&1
  echo "rep $rep setup exit=$? seconds=$(( $(date +%s) - t0 ))" >> "$OUT/driver.log"
  for op in $order; do
    if [ "$op" = batch ]; then tbl=ret_flat; ins=ins-flat.sql; else tbl=ret_part; ins=ins-part.sql; fi
    gate "$OUT/precheck-$rep-$op" || { echo "rep $rep $op: giving up" >> "$OUT/driver.log"; continue; }
    docker compose exec -T postgres sh -c "cat > /tmp/ins-$op.sql" < "loadtest/partition/$ins"
    docker compose exec -T postgres pgbench -n -U peakload -d peakload_exp -c 8 -j 2 -T 100 -l \
      --log-prefix="/tmp/ret-$rep-$op" -f "/tmp/ins-$op.sql" > "$OUT/pgbench-$rep-$op.txt" 2>&1 &
    PB=$!
    sleep 10
    PSQL -v cut="$CUT" -f - < "loadtest/partition/04-op-$op.sql" > "$OUT/op-$rep-$op.txt" 2>&1
    echo "rep $rep $op exit=$?" >> "$OUT/driver.log"
    wait $PB
    docker compose exec -T postgres sh -c "cat /tmp/ret-$rep-$op.*" > "$OUT/latency-$rep-$op.log" 2>/dev/null
  done
done
echo "all done" >> "$OUT/driver.log"
