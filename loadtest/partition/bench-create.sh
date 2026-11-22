#!/usr/bin/env bash
# Week 4 step 3, secondary measure: pgbench replay of the order-create transaction, flat vs partitioned outbox.
# Alternating repeats (flat, part, flat, part, flat, part). Each run waits for the pre-run check to pass.
#   OUT=<dir> CLIENTS=16 SECONDS_PER_RUN=60 bash loadtest/partition/bench-create.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
OUT=${OUT:?set OUT}; CLIENTS=${CLIENTS:-16}; T=${SECONDS_PER_RUN:-60}
mkdir -p "$OUT"
for v in flat part; do
  docker compose exec -T postgres sh -c "cat > /tmp/create-$v.sql" < loadtest/partition/bench-create-$v.sql
done
for rep in 1 2 3; do
  for v in flat part; do
    tries=0
    until bash loadtest/precheck.sh "$OUT/precheck-$rep-$v" > /dev/null; do
      tries=$((tries + 1))
      echo "rep $rep $v: precheck refused (try $tries), waiting" >> "$OUT/driver.log"
      [ $tries -ge 60 ] && { echo "rep $rep $v: giving up" >> "$OUT/driver.log"; break; }
      sleep 10
    done
    docker compose exec -T postgres pgbench -n -U peakload -d peakload_exp -c "$CLIENTS" -j 4 -T "$T" -f "/tmp/create-$v.sql" \
      > "$OUT/pgbench-$rep-$v.txt" 2>&1
    echo "rep $rep $v exit=$? tries=$tries" >> "$OUT/driver.log"
  done
done
