#!/usr/bin/env bash
# Week 4 step 5, item 4: timing before/after on the CLEAN experimental database (peakload_exp2).
#   order-create : pgbench, the order-create transaction (bench-create-flat.sql before, bench-create-part.sql after)
#   relay claim  : pgbench, one claim-and-mark step per transaction (10 flat before, 11 part after), with a 300,000-row
#                  backlog inserted before each run (12-backlog.sql)
# Alternating repeats: order-flat, order-part, relay-flat, relay-part, three times. Pre-run check before each run.
#   OUT=<dir> CLIENTS=16 SECONDS_PER_RUN=60 bash loadtest/partition/after-measure.sh
set -uo pipefail
cd "$(dirname "$0")/../.."
OUT=${OUT:?set OUT}; DB=peakload_exp2; CLIENTS=${CLIENTS:-16}; T=${SECONDS_PER_RUN:-60}
mkdir -p "$OUT"
PG() { docker compose exec -T postgres psql -U peakload -d $DB -v ON_ERROR_STOP=1 "$@"; }
for f in bench-create-flat bench-create-part; do docker compose exec -T postgres sh -c "cat > /tmp/$f.sql" < loadtest/partition/$f.sql; done
docker compose exec -T postgres sh -c "cat > /tmp/relay-flat.sql" < loadtest/partition/10-relay-claim-replay.sql
docker compose exec -T postgres sh -c "cat > /tmp/relay-part.sql" < loadtest/partition/11-relay-claim-replay-part.sql
gate() {  # pre-run check, retried until it passes (max 60 tries)
  local dir=$1 tries=0
  until bash loadtest/precheck.sh "$dir" > /dev/null; do
    tries=$((tries + 1)); echo "precheck refused $dir (try $tries)" >> "$OUT/driver.log"
    [ $tries -ge 60 ] && return 1; sleep 15
  done
}
run_pgbench() {  # $1 = label, $2 = script basename in /tmp
  docker compose exec -T postgres pgbench -n -U peakload -d $DB -c "$CLIENTS" -j 4 -T "$T" -f "/tmp/$2.sql" > "$OUT/pgbench-$1.txt" 2>&1
  echo "$1 exit=$?" >> "$OUT/driver.log"
}
for rep in 1 2 3; do
  for variant in order-flat order-part relay-flat relay-part; do
    gate "$OUT/precheck-$rep-$variant" || { echo "giving up $rep $variant" >> "$OUT/driver.log"; continue; }
    case $variant in
      order-flat) run_pgbench "$rep-$variant" bench-create-flat ;;
      order-part) run_pgbench "$rep-$variant" bench-create-part ;;
      relay-flat) PG -q -v tbl=outbox -f - < loadtest/partition/12-backlog.sql > /dev/null; run_pgbench "$rep-$variant" relay-flat ;;
      relay-part) PG -q -v tbl=outbox_p -f - < loadtest/partition/12-backlog.sql > /dev/null; run_pgbench "$rep-$variant" relay-part ;;
    esac
  done
done
echo "all done" >> "$OUT/driver.log"
