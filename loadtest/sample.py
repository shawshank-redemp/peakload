#!/usr/bin/env python3
"""Samples the stack every few seconds during a load run and appends one JSON line per sample.

    python3 loadtest/sample.py --out <file.jsonl> [--interval 5]

Runs until it receives SIGTERM. Each sample records what could be read; anything that failed is
written as null and the reason in "errors", so a missing number is visible in the output.

Sources:
  - docker stats --no-stream   -> CPU % and memory per running container
  - psql in the postgres service -> pg_stat_activity state counts, ungranted locks
  - order /actuator/prometheus  -> Hikari pool gauges and counters, outbox backlog and age
"""
import argparse
import json
import re
import signal
import subprocess
import sys
import time
from datetime import datetime, timezone

ORDER_METRICS = "http://localhost:8081/actuator/prometheus"
HIKARI_NAMES = (
    "hikaricp_connections_active",
    "hikaricp_connections_pending",
    "hikaricp_connections_timeout_total",
    "hikaricp_connections_usage_seconds_sum",
    "hikaricp_connections_usage_seconds_count",
    "hikaricp_connections_max",
)
OUTBOX_NAMES = ("outbox_backlog", "outbox_oldest_unpublished_age_seconds")
PG_SQL = (
    "select coalesce(sum(case when state='active' then 1 end),0),"
    "coalesce(sum(case when state='idle' then 1 end),0),"
    "coalesce(sum(case when state like 'idle in transaction%' then 1 end),0),"
    "coalesce(sum(case when wait_event_type='Lock' then 1 end),0),"
    "(select count(*) from pg_locks where not granted)"
    " from pg_stat_activity where datname='peakload' and pid <> pg_backend_pid();"
)

stop = False


def _on_term(signum, frame):
    global stop
    stop = True


def run(cmd, timeout=20):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def sample_docker_stats(errors):
    try:
        out = run(["docker", "stats", "--no-stream", "--format", "{{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}"])
        if out.returncode != 0:
            errors.append("docker stats: " + out.stderr.strip()[:200])
            return None
        result = {}
        for line in out.stdout.strip().splitlines():
            name, cpu, mem = line.split("\t")
            result[name] = {"cpu_pct": float(cpu.rstrip("%")), "mem": mem}
        return result
    except Exception as e:  # keep sampling even if one source fails
        errors.append(f"docker stats: {e}")
        return None


def sample_postgres(errors):
    try:
        out = run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "peakload", "-d", "peakload",
                   "-At", "-F", ",", "-c", PG_SQL])
        if out.returncode != 0:
            errors.append("psql: " + out.stderr.strip()[:200])
            return None
        active, idle, idle_tx, lock_waiters, ungranted = (int(x) for x in out.stdout.strip().split(","))
        return {"active": active, "idle": idle, "idle_in_tx": idle_tx,
                "lock_waiters": lock_waiters, "ungranted_locks": ungranted}
    except Exception as e:
        errors.append(f"psql: {e}")
        return None


def sample_order_metrics(errors):
    try:
        out = run(["curl", "-s", "--max-time", "5", ORDER_METRICS], timeout=10)
        if out.returncode != 0 or not out.stdout:
            errors.append("order metrics: no response")
            return None, None
        hikari, outbox = {}, {}
        pattern = re.compile(r"^([a-zA-Z_:]+)(\{[^}]*\})?\s+([-+0-9.eE]+|NaN)$")
        for line in out.stdout.splitlines():
            m = pattern.match(line)
            if not m:
                continue
            name, value = m.group(1), m.group(3)
            if name in HIKARI_NAMES:
                # keep only the order service's own pool (one pool, HikariPool-1)
                if m.group(2) and 'pool="HikariPool-' in m.group(2):
                    hikari[name] = float(value)
            elif name in OUTBOX_NAMES:
                outbox[name] = float(value)
        return hikari, outbox
    except Exception as e:
        errors.append(f"order metrics: {e}")
        return None, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--interval", type=float, default=5.0)
    args = ap.parse_args()
    signal.signal(signal.SIGTERM, _on_term)
    signal.signal(signal.SIGINT, _on_term)

    with open(args.out, "a", buffering=1) as fh:
        while not stop:
            started = time.monotonic()
            errors = []
            stats = sample_docker_stats(errors)
            pg = sample_postgres(errors)
            hikari, outbox = sample_order_metrics(errors)
            row = {
                "ts": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
                "docker_stats": stats,
                "postgres": pg,
                "hikari": hikari,
                "outbox": outbox,
                "errors": errors,
            }
            fh.write(json.dumps(row) + "\n")
            # Sleep for the rest of the interval, but wake quickly if asked to stop.
            remaining = args.interval - (time.monotonic() - started)
            end = time.monotonic() + max(0.0, remaining)
            while not stop and time.monotonic() < end:
                time.sleep(0.1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
