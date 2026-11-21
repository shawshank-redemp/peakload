#!/usr/bin/env python3
"""Per-phase, per-class report for one load-shedding run.

    python3 loadtest/shed_report.py <run-dir> [--phase-len 30]

Reads <run-dir>/run/points.json.gz (k6 points), <run-dir>/shed.csv (admission sampler), <run-dir>/stack.jsonl
(docker stats and order Hikari from sample.py) and <run-dir>/events.txt (t0). Prints markdown tables and writes
<run-dir>/report.json. Nothing here is estimated: every number is a count, a percentile of recorded durations, or a
value read from a sample. A "503" is any 503 from admission; the shed counter is the count of shed requests.
"""
import argparse
import gzip
import json
import os
from collections import defaultdict
from datetime import datetime

ACCEPTED = {"200", "201"}


def percentile(sorted_values, p):
    if not sorted_values:
        return None
    k = max(0, min(len(sorted_values) - 1, int(round(p / 100 * (len(sorted_values) - 1)))))
    return sorted_values[k]


def read_points(path):
    """Returns (durations by (phase, class, status), request counts, dropped by scenario)."""
    durations = defaultdict(list)
    counts = defaultdict(int)
    dropped = defaultdict(int)
    with gzip.open(path, "rt") as f:
        for line in f:
            if '"Point"' not in line:
                continue
            point = json.loads(line)
            metric = point["metric"]
            tags = point["data"].get("tags", {})
            if metric == "http_req_duration":
                key = (tags.get("phase", "?"), tags.get("class", "?"), tags.get("status", "0"))
                durations[key].append(point["data"]["value"])
            elif metric == "http_reqs":
                counts[(tags.get("phase", "?"), tags.get("class", "?"), tags.get("status", "0"))] += 1
            elif metric == "dropped_iterations":
                dropped[tags.get("scenario", "?")] += point["data"]["value"]
    return durations, counts, dropped


def class_phase_rows(durations, counts, phase_len, dropped):
    rows = []
    groups = sorted({(ph, cls) for (ph, cls, _) in counts})
    for phase, cls in groups:
        statuses = {st: n for (ph, c, st), n in counts.items() if ph == phase and c == cls}
        total = sum(statuses.values())
        accepted = sum(n for st, n in statuses.items() if st in ACCEPTED)
        shed_503 = statuses.get("503", 0)
        other = total - accepted - shed_503 - statuses.get("429", 0)
        acc_values = sorted(v for (ph, c, st), vals in durations.items()
                            if ph == phase and c == cls and st in ACCEPTED for v in vals)
        rows.append({
            "phase": phase,
            "class": cls,
            "requests": total,
            "achieved_per_s": round(total / phase_len, 1),
            "accepted": accepted,
            "accepted_share_pct": round(100 * accepted / total, 2) if total else None,
            "http_503_share_pct": round(100 * shed_503 / total, 2) if total else None,
            "other_errors": other,
            "p50_ms": round(percentile(acc_values, 50), 2) if acc_values else None,
            "p95_ms": round(percentile(acc_values, 95), 2) if acc_values else None,
            "p99_ms": round(percentile(acc_values, 99), 2) if acc_values else None,
        })
    return rows


def shed_counters(path):
    """Shed counter values at the first and last sample, per class. Admission restarts before each run, so these
    are the run's own counts."""
    if not os.path.exists(path):
        return None
    with open(path) as f:
        lines = f.read().strip().splitlines()
    if len(lines) < 2:
        return None
    header = lines[0].split(",")
    first = dict(zip(header, lines[1].split(",")))
    last = dict(zip(header, lines[-1].split(",")))
    return {cls: float(last[f"shed_{cls.lower()}"]) - float(first[f"shed_{cls.lower()}"])
            for cls in ("critical", "standard", "low")}


def level_changes(path, t0):
    if not os.path.exists(path):
        return []
    changes = []
    previous = None
    with open(path) as f:
        next(f)
        for line in f:
            epoch, level, in_flight, ewma, *_ = line.strip().split(",")
            if level == "":
                continue
            if level != previous:
                changes.append({"t_s": round(float(epoch) - t0, 1), "level": int(level),
                                "in_flight_total": in_flight, "upstream_ewma_ms": ewma})
                previous = level
    return changes


def stack_summary(path, t0, phase_len):
    """Mean and peak per phase of order-service CPU and Hikari pending, from sample.py's lines."""
    if not os.path.exists(path):
        return {}
    phases = defaultdict(lambda: defaultdict(list))
    with open(path) as f:
        for line in f:
            sample = json.loads(line)
            # sample.py writes "ts" as an ISO 8601 time with an offset.
            elapsed = datetime.fromisoformat(sample["ts"]).timestamp() - t0
            phase = int(elapsed // phase_len) + 1
            for name, stats in (sample.get("docker_stats") or {}).items():
                if "order" in name and stats and isinstance(stats.get("cpu_pct"), (int, float)):
                    phases[phase]["order_cpu_pct"].append(stats["cpu_pct"])
            for name, value in (sample.get("hikari") or {}).items():
                if "pending" in name and isinstance(value, (int, float)):
                    phases[phase]["order_hikari_pending"].append(value)
    out = {}
    for phase, metrics in sorted(phases.items()):
        out[phase] = {name: {"mean": round(sum(v) / len(v), 2), "peak": round(max(v), 2)}
                      for name, v in metrics.items() if v}
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run_dir")
    parser.add_argument("--phase-len", type=float, default=30)
    args = parser.parse_args()
    run = args.run_dir

    with open(os.path.join(run, "events.txt")) as f:
        t0 = float(f.read().strip().split("=")[1])

    durations, counts, dropped = read_points(os.path.join(run, "run", "points.json.gz"))
    rows = class_phase_rows(durations, counts, args.phase_len, dropped)
    report = {
        "rows": rows,
        "dropped_iterations_by_scenario": dict(sorted(dropped.items())),
        "dropped_iterations_total": sum(dropped.values()),
        "shed_counter_delta": shed_counters(os.path.join(run, "shed.csv")),
        "level_changes": level_changes(os.path.join(run, "shed.csv"), t0),
        "stack_by_phase": stack_summary(os.path.join(run, "stack.jsonl"), t0, args.phase_len),
    }
    with open(os.path.join(run, "report.json"), "w") as f:
        json.dump(report, f, indent=2)

    print("| Phase | Class | Requests | Achieved req/s | Accepted | Accepted % | 503 % | Other errors | p50 ms | p95 ms | p99 ms |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        print(f"| {r['phase']} | {r['class']} | {r['requests']} | {r['achieved_per_s']} | {r['accepted']} | "
              f"{r['accepted_share_pct']} | {r['http_503_share_pct']} | {r['other_errors']} | {r['p50_ms']} | "
              f"{r['p95_ms']} | {r['p99_ms']} |")
    print()
    print(f"dropped iterations (k6, total): {report['dropped_iterations_total']}")
    print("dropped by scenario:", report["dropped_iterations_by_scenario"])
    print("shed counter delta (admission):", report["shed_counter_delta"])
    print("level changes (t from t0, s):")
    for change in report["level_changes"]:
        print("  ", change)
    print("stack by phase:", json.dumps(report["stack_by_phase"]))


if __name__ == "__main__":
    main()
