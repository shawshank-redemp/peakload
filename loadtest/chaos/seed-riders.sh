#!/usr/bin/env bash
# Week 5, step 2: seeds riders for one dispatch run. Writes the same riders to Postgres (riders, status AVAILABLE) and
# Redis (a GEO position per rider in riders:geo:<city>, and a riders:seen:<id> key that stays for an hour, so the
# candidate check does not drop them as stale). Riders from an earlier run are removed first.
#   bash loadtest/chaos/seed-riders.sh <riders per city>
# Cities and their centres match create-orders.js. Riders and pickups are spread over the same box around each
# centre (about +/- 0.07 degrees, roughly 15 km), so a pickup has riders in its 3 km search radius but not all of them.
set -euo pipefail
cd "$(dirname "$0")/../.."
PER_CITY=${1:?riders per city}
WORK=$(mktemp -d)
python3 - "$PER_CITY" "$WORK" <<'PY'
import csv, random, sys, uuid
from datetime import datetime, timezone
per_city, work = int(sys.argv[1]), sys.argv[2]
CENTRES = {"Pune": (18.52, 73.86), "Mumbai": (19.08, 72.88), "Delhi": (28.61, 77.21)}
random.seed(20261122)   # fixed seed: the same rider layout in every run
now = datetime.now(timezone.utc).isoformat()
with open(f"{work}/riders.csv", "w", newline="") as pg, open(f"{work}/redis.txt", "w") as rd:
    rows = csv.writer(pg)
    for city, (lat0, lon0) in CENTRES.items():
        key = f"riders:geo:{city.lower()}"
        members = []
        for _ in range(per_city):
            rid = str(uuid.uuid4())
            lat = lat0 + random.uniform(-0.07, 0.07)
            lon = lon0 + random.uniform(-0.07, 0.07)
            rows.writerow([rid, f"rider-{rid[:8]}", city, "AVAILABLE"])
            members.append((lon, lat, rid))
        for i in range(0, len(members), 500):
            chunk = members[i:i + 500]
            rd.write(f"GEOADD {key} " + " ".join(f"{lo:.7f} {la:.7f} {r}" for lo, la, r in chunk) + "\n")
        for _, _, rid in members:
            rd.write(f"SET riders:seen:{rid} {now} EX 3600\n")
print(f"riders: {per_city * len(CENTRES)} ({per_city} per city)")
PY
# Postgres: remove the previous run's riders (assignments must be empty first; the run reset truncates them),
# then load the new riders.
docker compose exec -T postgres psql -U peakload -d peakload -v ON_ERROR_STOP=1 -q -c "DELETE FROM dispatch.riders" > /dev/null
docker compose exec -T postgres psql -U peakload -d peakload -v ON_ERROR_STOP=1 -q \
  -c "COPY dispatch.riders (id, name, city, status) FROM STDIN WITH (FORMAT csv)" < "$WORK/riders.csv"
# Redis: the same keys as dispatch uses. Old GEO sets and seen keys for these cities are removed first.
for city in pune mumbai delhi; do docker compose exec -T redis redis-cli DEL "riders:geo:$city" > /dev/null; done
docker compose exec -T redis sh -c 'redis-cli --scan --pattern "riders:seen:*" | xargs -r -n 500 redis-cli DEL' > /dev/null
docker compose exec -T redis redis-cli < "$WORK/redis.txt" > /dev/null
echo "postgres riders AVAILABLE: $(docker compose exec -T postgres psql -U peakload -d peakload -At -c "select count(*) from dispatch.riders where status='AVAILABLE'")"
total=0
for c in pune mumbai delhi; do n=$(docker compose exec -T redis redis-cli ZCARD "riders:geo:$c" | tr -d '\r'); total=$((total + n)); done
echo "redis GEO members: $total"
echo "redis seen keys: $(docker compose exec -T redis sh -c 'redis-cli --scan --pattern "riders:seen:*" | wc -l' | tr -d ' ')"
rm -rf "$WORK"
