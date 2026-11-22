#!/usr/bin/env bash
# Week 4 step 2: runs every bisect condition twice, in alternating order (forward, then reverse).
#   bash loadtest/step2/run-bisect.sh <results dir>
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=${1:?results dir}
mkdir -p "$ROOT"
# label | CODE | DISPATCH | PGSTAT | TARGET | READ_RATE (optional, default 0)
CONDS=(
  "cur-disp-on-pg-on-direct|current|on|on|direct"
  "cur-disp-on-pg-on-direct-reads50|current|on|on|direct|50"
  "cur-disp-off-pg-on-direct|current|off|on|direct"
  "cur-disp-off-pg-off-direct|current|off|off|direct"
  "cur-disp-off-pg-off-admission|current|off|off|admission"
  "week1-disp-off-pg-off-direct|week1|off|off|direct"
)
run() {
  IFS='|' read -r label code disp pg tgt rr <<< "$1"
  local dir="$ROOT/$2-$label"
  OUT="$dir" CODE=$code DISPATCH=$disp PGSTAT=$pg TARGET=$tgt READ_RATE=${rr:-0} bash loadtest/step2/run-cond.sh > "$ROOT/$2-$label.stdout" 2>&1
  echo "$2 $label exit=$?" >> "$ROOT/queue.log"
}
i=1
for c in "${CONDS[@]}"; do run "$c" "r1-$(printf %02d $i)"; i=$((i+1)); done
i=1
for ((j=${#CONDS[@]}-1; j>=0; j--)); do run "${CONDS[$j]}" "r2-$(printf %02d $i)"; i=$((i+1)); done
echo "all done" >> "$ROOT/queue.log"
