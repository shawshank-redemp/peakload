#!/usr/bin/env python3
"""Turns one bisect results folder into a table. Reads only what the runs wrote; computes nothing that was not measured.

    python3 loadtest/step2/summarise_step2.py <results dir>        -> prints a markdown table

Columns come from k6-summary.json (measured run only), run/samples.jsonl (sampled every 2 s), run/host-cpu.jsonl
(host process CPU, ps %CPU summed over processes: 100 = one core) and run/events.json (outbox drain after the run).
"""
import glob
import json
import os
import sys


def load_json(path):
    try:
        with open(path) as fh:
            return json.load(fh)
    except Exception:
        return None


def metric(summary, name, key):
    try:
        return summary["metrics"][name][key]
    except (KeyError, TypeError):
        return None


def fmt(x, nd=2):
    return "n/a" if x is None else f"{x:.{nd}f}"


def samples_stats(path):
    cpu_order, cpu_pg, pend, backlog = [], [], [], []
    if not os.path.exists(path):
        return {}
    with open(path) as fh:
        for line in fh:
            try:
                row = json.loads(line)
            except ValueError:
                continue
            ds = row.get("docker_stats") or {}
            for name, v in ds.items():
                if name.endswith("order-1") or name == "w1-order":
                    cpu_order.append(v["cpu_pct"])
                if name.endswith("postgres-1"):
                    cpu_pg.append(v["cpu_pct"])
            h = row.get("hikari") or {}
            if h.get("hikaricp_connections_pending") is not None:
                pend.append(h["hikaricp_connections_pending"])
            o = row.get("outbox") or {}
            if o.get("outbox_backlog") is not None:
                backlog.append(o["outbox_backlog"])
    mean = lambda xs: sum(xs) / len(xs) if xs else None
    return {
        "order_cpu_mean": mean(cpu_order), "order_cpu_peak": max(cpu_order) if cpu_order else None,
        "pg_cpu_mean": mean(cpu_pg), "pg_cpu_peak": max(cpu_pg) if cpu_pg else None,
        "pending_peak": max(pend) if pend else None,
        "backlog_peak": max(backlog) if backlog else None,
    }


def host_stats(path):
    vals = []
    if os.path.exists(path):
        with open(path) as fh:
            for line in fh:
                try:
                    vals.append(json.loads(line)["host_cpu_sum_pct"])
                except (ValueError, KeyError):
                    pass
    return (sum(vals) / len(vals), max(vals)) if vals else (None, None)


def main():
    root = sys.argv[1]
    rows = []
    for d in sorted(glob.glob(os.path.join(root, "r*-*"))):
        if not os.path.isdir(d):
            continue
        name = os.path.basename(d)
        cond = load_json(os.path.join(d, "condition.json")) or {}
        k6 = load_json(os.path.join(d, "run", "k6-summary.json")) or {}
        s = samples_stats(os.path.join(d, "run", "samples.jsonl"))
        hm, hp = host_stats(os.path.join(d, "run", "host-cpu.jsonl"))
        ev = load_json(os.path.join(d, "run", "events.json")) or {}
        rows.append((name, cond, k6, s, hm, hp, ev))

    print("| Run | Condition | Achieved req/s | Dropped iter. | p50 ms | p95 ms | p99 ms | Fail rate | Order CPU mean/peak % | PG CPU mean/peak % | Hikari pending peak | Outbox backlog peak | Drain s | Host CPU sum mean/peak (100 = 1 core) |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for name, cond, k6, s, hm, hp, ev in rows:
        cond_txt = f"{cond.get('code', '?')} / dispatch {cond.get('dispatch', '?')} / pg_stat {cond.get('pg_stat', '?')} / {cond.get('target', '?')}"
        rps = metric(k6, "http_reqs", "rate")
        dropped = metric(k6, "dropped_iterations", "count")
        print("| {} | {} | {} | {} | {} | {} | {} | {} | {}/{} | {}/{} | {} | {} | {} | {}/{} |".format(
            name, cond_txt,
            fmt(rps), "n/a" if dropped is None else int(dropped),
            fmt(metric(k6, "http_req_duration", "med")), fmt(metric(k6, "http_req_duration", "p(95)")),
            fmt(metric(k6, "http_req_duration", "p(99)")),
            fmt(metric(k6, "http_req_failed", "value"), 4),
            fmt(s.get("order_cpu_mean"), 0), fmt(s.get("order_cpu_peak"), 0),
            fmt(s.get("pg_cpu_mean"), 0), fmt(s.get("pg_cpu_peak"), 0),
            "n/a" if s.get("pending_peak") is None else int(s["pending_peak"]),
            "n/a" if s.get("backlog_peak") is None else int(s["backlog_peak"]),
            ev.get("drain_s", "n/a"),
            fmt(hm, 0), fmt(hp, 0),
        ))


if __name__ == "__main__":
    main()
