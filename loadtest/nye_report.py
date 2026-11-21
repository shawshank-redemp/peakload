#!/usr/bin/env python3
"""Report for New Year's Eve curve runs (Week 3 Step 5).

    python3 loadtest/nye_report.py <run-dir> [<run-dir> ...]     per-run markdown (stdout) and report.json in each run
    python3 loadtest/nye_report.py --across <run-dir> ...         range across runs of one condition

Reads, per run folder: run/points.json.gz (k6), shed.csv (admission sampler, 0.5 s), stack.jsonl (sample.py, 2 s),
events.txt (t0 and the outbox drain wait), admission-metrics-final.txt (level seconds, circuit opens) and checks.txt.
Every number comes from those files. Nothing is averaged across runs in the per-run tables.
"""
import argparse
import gzip
import json
import os
from collections import defaultdict
from datetime import datetime

# Stage windows in seconds from t0 (the start of the curve). Must match loadtest/nye-curve.js.
STAGES = [
    ("calm", 0, 60, 0.0), ("ramp1", 60, 15, 0.25), ("ramp2", 75, 15, 0.5), ("ramp3", 90, 15, 0.75),
    ("ramp4", 105, 15, 1.0), ("peak", 120, 30, 1.0), ("decline1", 150, 20, 0.75), ("decline2", 170, 20, 0.5),
    ("decline3", 190, 20, 0.25), ("tail", 210, 90, 0.0),
]
BASE = {"CRITICAL": 200, "STANDARD": 20, "LOW": 400}
PEAK = {"CRITICAL": 1000, "STANDARD": 100, "LOW": 2400}
CLASSES = ["CRITICAL", "STANDARD", "LOW"]
ACCEPTED = {"200", "201"}
PEAK_END = 150  # seconds from t0


def percentile(values, p):
    if not values:
        return None
    k = max(0, min(len(values) - 1, int(round(p / 100 * (len(values) - 1)))))
    return values[k]


def read_points(path):
    """durations and counts by (phase, class, status); dropped iterations by scenario."""
    durations = defaultdict(list)
    counts = defaultdict(int)
    dropped = defaultdict(int)
    with gzip.open(path, "rt") as f:
        for line in f:
            if '"Point"' not in line:
                continue
            p = json.loads(line)
            tags = p["data"].get("tags", {})
            key = (tags.get("phase", "?"), tags.get("class", "?"), tags.get("status", "0"))
            if p["metric"] == "http_req_duration" and key[0] != "?":
                durations[key].append(p["data"]["value"])
            elif p["metric"] == "http_reqs" and key[0] != "?":
                counts[key] += 1
            elif p["metric"] == "dropped_iterations":
                dropped[tags.get("scenario", "?")] += p["data"]["value"]
    return durations, counts, dropped


def phase_factor(name):
    for stage, _, _, f in STAGES:
        if stage == name:
            return f
    return 0.0


def stage_rows(durations, counts, dropped, shed_at):
    rows = []
    for stage, start, length, f in STAGES:
        for cls in CLASSES:
            statuses = {st: n for (ph, c, st), n in counts.items() if ph == stage and c == cls}
            total = sum(statuses.values())
            if total == 0 and BASE[cls] == 0:
                continue
            accepted = sum(n for st, n in statuses.items() if st in ACCEPTED)
            shed = max(0, shed_at(start + length, cls) - shed_at(start, cls))
            n503 = statuses.get("503", 0)
            acc_values = sorted(v for (ph, c, st), vals in durations.items()
                                if ph == stage and c == cls and st in ACCEPTED for v in vals)
            rows.append({
                "phase": stage,
                "class": cls,
                "target_per_s": round(BASE[cls] + f * (PEAK[cls] - BASE[cls])),
                "achieved_per_s": round(total / length, 1),
                "requests": total,
                "accepted_share_pct": round(100 * accepted / total, 2) if total else None,
                "shed_503_share_pct": round(100 * min(shed, total) / total, 2) if total else None,
                "shed": shed,
                "other_503": max(0, n503 - shed),
                "other_errors": total - accepted - n503 - statuses.get("429", 0),
                "p50_ms": round(percentile(acc_values, 50), 2) if acc_values else None,
                "p95_ms": round(percentile(acc_values, 95), 2) if acc_values else None,
                "p99_ms": round(percentile(acc_values, 99), 2) if acc_values else None,
            })
    return rows


def load_shed(path):
    if not os.path.exists(path):
        return []
    with open(path) as f:
        header = f.readline().strip().split(",")
        rows = []
        for line in f:
            values = dict(zip(header, line.strip().split(",")))
            if values.get("epoch"):
                rows.append(values)
    return rows


def shed_lookup(rows, t0):
    """Returns shed_at(t, cls): the cumulative shed counter for class at t seconds from t0."""
    series = [(float(r["epoch"]) - t0, r) for r in rows if r.get("shed_critical")]

    def shed_at(t, cls):
        key = {"CRITICAL": "shed_critical", "STANDARD": "shed_standard", "LOW": "shed_low"}[cls]
        best = 0.0
        for when, r in series:
            if when <= t:
                best = float(r[key])
            else:
                break
        return best

    return shed_at


def level_timeline(rows, t0):
    changes = []
    previous = None
    for r in rows:
        if r.get("level", "") == "":
            continue
        if r["level"] != previous:
            changes.append({"t_s": round(float(r["epoch"]) - t0, 1), "level": int(r["level"])})
            previous = r["level"]
    return changes


def limiter_summary(rows):
    modes = [r["limiter_mode"] for r in rows if r.get("limiter_mode", "") != ""]
    local_samples = sum(1 for m in modes if m == "1")
    opens = [float(r["circuit_opens"]) for r in rows if r.get("circuit_opens", "") != ""]
    return {
        "samples": len(modes),
        "local_mode_samples": local_samples,
        "circuit_opens_during_run": (opens[-1] - opens[0]) if len(opens) > 1 else None,
    }


def menu_pending(rows, t0):
    values = [float(r["menu_hikari_pending"]) for r in rows
              if r.get("menu_hikari_pending", "") != "" and 0 <= float(r["epoch"]) - t0 <= 300]
    return {"peak": max(values) if values else None,
            "mean": round(sum(values) / len(values), 2) if values else None}


def stack_series(path, t0):
    """Per 2 s sample: seconds from t0, outbox backlog, outbox oldest age, order pending, CPU per container."""
    out = []
    if not os.path.exists(path):
        return out
    with open(path) as f:
        for line in f:
            s = json.loads(line)
            elapsed = datetime.fromisoformat(s["ts"]).timestamp() - t0
            row = {"t_s": round(elapsed, 1)}
            outbox = s.get("outbox") or {}
            row["outbox_backlog"] = outbox.get("outbox_backlog")
            row["outbox_age_s"] = outbox.get("outbox_oldest_unpublished_age_seconds")
            hikari = s.get("hikari") or {}
            row["order_hikari_pending"] = hikari.get("hikaricp_connections_pending")
            for name, st in (s.get("docker_stats") or {}).items():
                key = next((k for k in ("admission", "order", "postgres") if k in name), None)
                if key and st and isinstance(st.get("cpu_pct"), (int, float)):
                    row[f"cpu_{key}"] = st["cpu_pct"]
            out.append(row)
    return out


def outbox_summary(series):
    backlog = [(r["t_s"], r["outbox_backlog"]) for r in series if r.get("outbox_backlog") is not None]
    if not backlog:
        return {"peak_backlog": None}
    peak_t, peak_v = max(backlog, key=lambda x: x[1])
    after_peak = [(t, v) for t, v in backlog if t >= PEAK_END]
    zero = next((t for t, v in after_peak if v == 0), None)
    return {
        "peak_backlog": peak_v,
        "peak_at_s": peak_t,
        "backlog_at_peak_end": next((v for t, v in backlog if t >= PEAK_END), None),
        "zero_after_peak_s": (zero - PEAK_END) if zero is not None else None,
        "backlog_at_end": backlog[-1][1],
    }


def cpu_summary(series):
    out = {}
    window = [r for r in series if 0 <= r["t_s"] <= 300]
    for key in ("admission", "order", "postgres"):
        values = [r[f"cpu_{key}"] for r in window if f"cpu_{key}" in r]
        if values:
            out[key] = {"mean": round(sum(values) / len(values), 1), "peak": round(max(values), 1)}
    return out


def level_seconds(path):
    out = {}
    if not os.path.exists(path):
        return out
    with open(path) as f:
        for line in f:
            if line.startswith("admission_load_level_seconds_total{"):
                level = line.split('level="')[1].split('"')[0]
                out[level] = round(float(line.rsplit(" ", 1)[1]), 2)
    return out


def checks(path):
    out = {}
    if not os.path.exists(path):
        return out
    with open(path) as f:
        for line in f:
            if "=" in line:
                k, v = line.strip().split("=", 1)
                out[k] = v
    return out


def analyse(run):
    t0 = float(open(os.path.join(run, "events.txt")).read().split("t0=")[1].split()[0])
    durations, counts, dropped = read_points(os.path.join(run, "run", "points.json.gz"))
    shed_rows = load_shed(os.path.join(run, "shed.csv"))
    shed_at = shed_lookup(shed_rows, t0)
    series = stack_series(os.path.join(run, "stack.jsonl"), t0)
    seeds = 400  # SEEDS in nye-curve.js (setup creates them before the curve, after the reset)
    crit_201 = sum(n for (ph, c, st), n in counts.items() if c == "CRITICAL" and st == "201")
    db_orders = int(checks(os.path.join(run, "checks.txt")).get("orders", -1))
    created = {"crit_201": crit_201, "seed_orders": seeds, "expected_orders": crit_201 + seeds,
               "db_orders": db_orders, "match": crit_201 + seeds == db_orders}
    return {
        "created_check": created,
        "run": os.path.basename(run.rstrip("/")),
        "phases": stage_rows(durations, counts, dropped, shed_at),
        "dropped_iterations_total": sum(dropped.values()),
        "dropped_by_scenario": dict(sorted(dropped.items())),
        "level_timeline": level_timeline(shed_rows, t0),
        "level_seconds": level_seconds(os.path.join(run, "admission-metrics-final.txt")),
        "limiter": limiter_summary(shed_rows),
        "circuit_opens_final_scrape": None,
        "menu_hikari_pending_curve": menu_pending(shed_rows, t0),
        "outbox": outbox_summary(series),
        "cpu_curve_window": cpu_summary(series),
        "checks": checks(os.path.join(run, "checks.txt")),
    }


def print_run(r):
    print(f"### {r['run']}")
    print("| Phase | Class | Target req/s | Achieved req/s | Accepted % | Shed (503) % | Other 503 | Other errors | p50 ms | p95 ms | p99 ms |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for p in r["phases"]:
        print(f"| {p['phase']} | {p['class']} | {p['target_per_s']} | {p['achieved_per_s']} | {p['accepted_share_pct']} | "
              f"{p['shed_503_share_pct']} | {p['other_503']} | {p['other_errors']} | {p['p50_ms']} | {p['p95_ms']} | {p['p99_ms']} |")
    print()
    o = r["outbox"]
    print(f"- k6 dropped iterations: {r['dropped_iterations_total']} {r['dropped_by_scenario']}")
    print(f"- outbox: peak backlog {o.get('peak_backlog')} at t={o.get('peak_at_s')} s; backlog at peak end {o.get('backlog_at_peak_end')}; "
          f"zero after peak end: {o.get('zero_after_peak_s')} s; backlog at end {o.get('backlog_at_end')}")
    print(f"- limiter: {r['limiter']}")
    print(f"- admission menu Hikari pending (curve window): {r['menu_hikari_pending_curve']}")
    print(f"- CPU %, curve window: {r['cpu_curve_window']}")
    print(f"- level seconds: {r['level_seconds']}")
    print(f"- level changes: {r['level_timeline']}")
    print(f"- created check (201s + seeds = orders in Postgres): {r['created_check']}")
    print(f"- checks: {r['checks']}")
    print()


def across(runs, condition):
    print(f"## {condition}: range across {len(runs)} runs (min to max; no averages)")
    print("| Metric | Min | Max | Per run |")
    print("|---|---|---|---|")

    def row(label, values):
        vals = [v for v in values if v is not None]
        if not vals:
            print(f"| {label} | - | - | - |")
            return
        print(f"| {label} | {min(vals)} | {max(vals)} | {', '.join(str(v) for v in values)} |")

    peak = [next((p for p in r["phases"] if p["phase"] == "peak" and p["class"] == "CRITICAL"), None) for r in runs]
    row("CRITICAL peak p99 ms", [p and p["p99_ms"] for p in peak])
    row("CRITICAL peak accepted %", [p and p["accepted_share_pct"] for p in peak])
    row("CRITICAL peak achieved req/s", [p and p["achieved_per_s"] for p in peak])
    row("Outbox peak backlog", [r["outbox"].get("peak_backlog") for r in runs])
    row("Outbox zero after peak end (s)", [r["outbox"].get("zero_after_peak_s") for r in runs])
    row("k6 dropped iterations", [r["dropped_iterations_total"] for r in runs])
    row("Menu Hikari pending peak (curve)", [r["menu_hikari_pending_curve"]["peak"] for r in runs])
    print()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("runs", nargs="+")
    parser.add_argument("--across", action="store_true")
    args = parser.parse_args()
    results = [analyse(run) for run in args.runs]
    if args.across:
        across(results, "condition")
        return
    for r, run in zip(results, args.runs):
        with open(os.path.join(run, "report.json"), "w") as f:
            json.dump(r, f, indent=2)
        print_run(r)


if __name__ == "__main__":
    main()
