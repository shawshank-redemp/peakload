#!/usr/bin/env python3
"""Week 5, step 2: invariants and counts for one dispatch run, from the live database and Redis at the end of the run.
Computes only what it reads. Writes report.json into the run folder and prints a summary.

    python3 loadtest/chaos/chaos_report.py <run folder>

Reads: the run folder's events.txt (T0, kill_N, restart_N), dispatch-metrics.txt (duplicates rejected, one line per
dispatch process lifetime), lag.txt (Redpanda consumer lag per second, from rpk).
"""
import json
import os
import re
import statistics
import subprocess
import sys

COMPOSE = ["docker", "compose", "exec", "-T"]


def sql(query):
    out = subprocess.run(COMPOSE + ["postgres", "psql", "-U", "peakload", "-d", "peakload", "-At", "-c", query],
                         capture_output=True, text=True, check=True).stdout.strip()
    return out


def scalar(query):
    return int(sql(query) or 0)


def redis(*args):
    return subprocess.run(COMPOSE + ["redis", "redis-cli", *args], capture_output=True, text=True,
                          check=True).stdout.strip()


def read_events(run):
    ev = {}
    with open(os.path.join(run, "events.txt")) as fh:
        for line in fh:
            if "=" in line:
                k, v = line.strip().split("=", 1)
                ev[k] = int(v) if v.lstrip("-").isdigit() else v
    return ev


def duplicates_rejected(run):
    """Sum of events.duplicates.ignored over every dispatch process lifetime (each restart resets the counter)."""
    path = os.path.join(run, "dispatch-metrics.txt")
    total = 0.0
    if os.path.exists(path):
        for line in open(path):
            m = re.match(r"events_duplicates_ignored_total\S* ([0-9.eE+-]+)", line.strip())
            if m:
                total += float(m.group(1))
    return int(round(total))


def lag_timeline(run):
    path = os.path.join(run, "lag.txt")
    rows = []
    if os.path.exists(path):
        for line in open(path):
            parts = line.split()
            if len(parts) == 2 and parts[1].lstrip("-").isdigit():
                rows.append((int(parts[0]), int(parts[1])))
    return rows


def pending_reasons(limit=500):
    """For up to `limit` pending orders: is there a free rider (AVAILABLE in Postgres and with a seen key) within 3 km
    of the pickup? 'free_rider_in_radius' = the candidate window missed it (busy riders in front of it);
    'no_free_rider_in_radius' = no free rider in the radius. Returns counts and the sample size."""
    available = set(sql("SELECT id FROM dispatch.riders WHERE status = 'AVAILABLE'").splitlines())
    rows = sql("SELECT p.order_id, o.payload->>'city', o.pickup_lon, o.pickup_lat FROM dispatch.pending_orders p "
               f"LEFT JOIN orders o ON o.id = p.order_id LIMIT {limit}").splitlines()
    reasons = {"free_rider_in_radius": 0, "no_free_rider_in_radius": 0, "order_not_found": 0, "sample": 0}
    for row in rows:
        if not row:
            continue
        reasons["sample"] += 1
        _, city, lon, lat = row.split("|")
        if not city or lon in ("", "None"):
            reasons["order_not_found"] += 1
            continue
        found = redis("GEOSEARCH", f"riders:geo:{city.lower()}", "FROMLONLAT", lon, lat, "BYRADIUS", "3", "km",
                      "ASC", "COUNT", "500").split()
        free = any(r in available and redis("EXISTS", f"riders:seen:{r}") == "1" for r in found)
        reasons["free_rider_in_radius" if free else "no_free_rider_in_radius"] += 1
    return reasons


def main():
    run = sys.argv[1]
    ev = read_events(run)
    report = {"run": os.path.basename(run.rstrip("/"))}

    report["invariants"] = {
        "orders_with_two_assignments": scalar(
            "SELECT count(*) FROM (SELECT order_id FROM dispatch.assignments GROUP BY order_id HAVING count(*) > 1) x"),
        "riders_with_two_active_assignments": scalar(
            "SELECT count(*) FROM (SELECT rider_id FROM dispatch.assignments WHERE status = 'ACTIVE' "
            "GROUP BY rider_id HAVING count(*) > 1) x"),
        "active_assignment_to_rider_not_busy": scalar(
            "SELECT count(*) FROM dispatch.assignments a JOIN dispatch.riders r ON r.id = a.rider_id "
            "WHERE a.status = 'ACTIVE' AND r.status <> 'BUSY'"),
        "busy_rider_without_active_assignment": scalar(
            "SELECT count(*) FROM dispatch.riders r WHERE r.status = 'BUSY' AND NOT EXISTS "
            "(SELECT 1 FROM dispatch.assignments a WHERE a.rider_id = r.id AND a.status = 'ACTIVE')"),
    }
    orders = scalar("SELECT count(*) FROM orders")
    assigned = scalar("SELECT count(*) FROM dispatch.assignments")
    pending = scalar("SELECT count(*) FROM dispatch.pending_orders")
    report["counts"] = {
        "orders_created": orders,
        "assignments": assigned,
        "pending_orders": pending,
        "orders_minus_assignments_and_pending": orders - assigned - pending,
        "dispatch_processed_events": scalar("SELECT count(*) FROM dispatch.processed_events"),
        "redeliveries_rejected_as_duplicates": duplicates_rejected(run),
    }
    lags = sql("SELECT count(*) FROM dispatch.outbox WHERE published_at IS NULL")
    report["counts"]["dispatch_outbox_unpublished_at_end"] = int(lags or 0)
    report["counts"]["order_outbox_unpublished_at_end"] = scalar("SELECT count(*) FROM outbox WHERE published_at IS NULL")

    lag_rows = [(t, v) for t, v in lag_timeline(run)]
    recovery = {}
    if "start_1" in ev or any(k.startswith("restart_") for k in ev):
        for k in sorted(k for k in ev if k.startswith("restart_")):
            n = k.split("_")[1]
            restart = ev[k]
            zero = next((t for t, v in lag_rows if t >= restart and v == 0), None)
            recovery[f"kill_{n}"] = None if zero is None else round((zero - restart) / 1000, 1)
    report["consumer_lag_recovery_seconds_after_restart"] = recovery

    # Assignment lag: order created -> assignment created (both in the database, so both clocks are the same).
    lag = sql("SELECT round(extract(epoch FROM (a.created_at - o.created_at))::numeric, 3) FROM dispatch.assignments a "
              "JOIN orders o ON o.id = a.order_id")
    values = sorted(float(x) for x in lag.splitlines() if x)
    if values:
        report["assignment_lag_seconds"] = {
            "n": len(values), "p50": values[len(values) // 2],
            "p95": values[int(len(values) * 0.95) - 1] if len(values) > 1 else values[0],
            "max": values[-1], "mean": round(statistics.mean(values), 3),
        }
    report["pending_reasons"] = pending_reasons() if pending else {}

    with open(os.path.join(run, "report.json"), "w") as fh:
        json.dump(report, fh, indent=2)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
