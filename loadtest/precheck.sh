#!/usr/bin/env bash
# Pre-run check, run before every measured run. Writes the readings to <OUT>/precheck.txt and exits non-zero
# (and so the run must not start) when the machine is not idle enough to measure.
#
#   bash loadtest/precheck.sh <OUT>
#
# Readings:
#   - Mac thermal and CPU speed limits (pmset -g therm). Refuses on any recorded warning or speed limit.
#   - Docker idle CPU: sum of docker stats CPU % over 3 samples 2 s apart, median of the sums. The sum is out of
#     600% on this VM (6 vCPU). Refuses above IDLE_MAX_PCT (default 100, about 17% of the VM).
#   - Postgres maintenance: autovacuum workers and checkpointer activity now (pg_stat_activity), and the
#     checkpoint and autovacuum counters from pg_stat_bgwriter and pg_stat_all_tables, so a run can show whether
#     maintenance happened during it. Refuses if an autovacuum worker or the checkpointer is busy now.
# Thresholds are starting values chosen before any run in this step; they are not measured results.
set -uo pipefail
OUT=${1:?usage: precheck.sh <OUT>}
IDLE_MAX_PCT=${IDLE_MAX_PCT:-100}
mkdir -p "$OUT"
REPORT="$OUT/precheck.txt"
: > "$REPORT"
say() { echo "$*" | tee -a "$REPORT"; }
fail=0

say "precheck at $(date -u +%Y-%m-%dT%H:%M:%SZ)"

# 1. thermal and CPU limits
therm=$(pmset -g therm 2>&1)
say "pmset -g therm:"
echo "$therm" | sed 's/^/  /' | tee -a "$REPORT" > /dev/null
if echo "$therm" | grep -i "warning level" | grep -v -i "no .*warning level" | grep -q .; then
  say "  REFUSE: thermal or performance warning recorded"; fail=1
fi
if echo "$therm" | grep -E "CPU_Speed_Limit\s*=\s*[0-9]+" | grep -v -E "CPU_Speed_Limit\s*=\s*100\b" | grep -q .; then
  say "  REFUSE: CPU speed limit below 100"; fail=1
fi
[ $fail -eq 0 ] && say "  thermal: ok"

# 2. docker idle CPU
sums=()
for i in 1 2 3; do
  s=$(docker stats --no-stream --format '{{.CPUPerc}}' | tr -d '%' | awk '{s+=$1} END {printf "%.2f", s}')
  sums+=("$s")
  [ "$i" -lt 3 ] && sleep 2
done
median=$(printf '%s\n' "${sums[@]}" | sort -n | sed -n 2p)
say "docker CPU sums over 3 samples: ${sums[*]} (median $median %, limit $IDLE_MAX_PCT %)"
if awk -v m="$median" -v lim="$IDLE_MAX_PCT" 'BEGIN {exit !(m > lim)}'; then
  say "  REFUSE: idle CPU above limit"; fail=1
fi
docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' | sed 's/^/  /' >> "$REPORT"

# 3. Postgres maintenance
pg() { docker compose exec -T postgres psql -U peakload -d peakload -At -c "$1" 2>/dev/null; }
av=$(pg "select count(*) from pg_stat_activity where backend_type = 'autovacuum worker'")
ck=$(pg "select count(*) from pg_stat_activity where backend_type = 'checkpointer' and state is not null")
say "postgres now: autovacuum workers=${av:-?}, checkpointer active=${ck:-?}"
pg "select 'checkpoints_timed='||checkpoints_timed||' checkpoints_req='||checkpoints_req||' buffers_checkpoint='||buffers_checkpoint from pg_stat_bgwriter" | sed 's/^/  bgwriter: /' | tee -a "$REPORT" > /dev/null
pg "select 'autovacuum_count='||coalesce(sum(autovacuum_count),0)||' autoanalyze_count='||coalesce(sum(autoanalyze_count),0) from pg_stat_all_tables where schemaname='public'" | sed 's/^/  tables: /' >> "$REPORT"
if [ "${av:-1}" != "0" ] || [ "${ck:-1}" != "0" ]; then
  say "  REFUSE: autovacuum or checkpoint busy"; fail=1
fi

if [ $fail -eq 0 ]; then say "RESULT: go"; else say "RESULT: refused"; fi
exit $fail
