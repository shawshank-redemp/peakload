#!/usr/bin/env python3
"""Turns raw run output into verdicts and per-run tables. Reads only what the runs wrote; computes nothing
that was not measured.

    python3 loadtest/summarise.py verdict <k6-summary.json>      -> prints PASS or FAIL
    python3 loadtest/summarise.py report <results-dir>           -> writes report.md and report.json

Each run directory holds: k6-summary.json (k6 --summary-export), samples.jsonl (sample.py output) and
run.json (timestamps written by run-order-create.sh).
"""
import glob
import json
import os
import statistics
import sys
from datetime import datetime


def crossed(summary):
    """k6's summary export maps each threshold to a boolean: true means CROSSED (failed), false means passed.
    Checked with a throwaway k6 script that had one threshold pass and one fail."""
    out = []
    for metric, body in summary["metrics"].items():
        for expr, res in (body.get("thresholds") or {}).items():
            if res is True:
                out.append((metric, expr))
    return out


def verdict(path):
    failed = [f"{metric} {expr}" for metric, expr in crossed(json.load(open(path)))]
    print("FAIL" if failed else "PASS")
    if failed:
        print("failed thresholds: " + ", ".join(failed), file=sys.stderr)


def parse_ts(s):
    return datetime.fromisoformat(s)


def metric_value(data, name, key):
    """k6 summary export: counters and trends hold their stats directly on the metric (count, rate, med,
    p(95), p(99)). Rate metrics such as http_req_failed hold their ratio under 'value'."""
    m = data["metrics"].get(name, {})
    if name == "http_req_failed" and key == "rate":
        return m.get("value")
    return m.get(key)


def peak(samples, getter):
    vals = [v for v in (getter(s) for s in samples) if v is not None]
    return max(vals) if vals else None


def mean(samples, getter):
    vals = [v for v in (getter(s) for s in samples) if v is not None]
    return round(statistics.mean(vals), 2) if vals else None


def run_report(run_dir):
    summary = json.load(open(os.path.join(run_dir, "k6-summary.json")))
    run = json.load(open(os.path.join(run_dir, "run.json")))
    samples = [json.loads(line) for line in open(os.path.join(run_dir, "samples.jsonl"))]
    for s in samples:
        s["_t"] = parse_ts(s["ts"])

    k6_start, k6_end = parse_ts(run["k6_start"]), parse_ts(run["k6_end"])
    during = [s for s in samples if k6_start <= s["_t"] <= k6_end]

    # Hikari usage: average connection hold time between consecutive samples, from the sum/count counters.
    usage_ms = []
    prev = None
    for s in during:
        h = s.get("hikari") or {}
        if prev and "hikaricp_connections_usage_seconds_sum" in h and "hikaricp_connections_usage_seconds_sum" in prev:
            dsum = h["hikaricp_connections_usage_seconds_sum"] - prev["hikaricp_connections_usage_seconds_sum"]
            dcnt = h["hikaricp_connections_usage_seconds_count"] - prev["hikaricp_connections_usage_seconds_count"]
            if dcnt > 0:
                usage_ms.append(1000 * dsum / dcnt)
        prev = h or prev

    def cpu(name):
        return lambda s: ((s.get("docker_stats") or {}).get(name) or {}).get("cpu_pct")

    def k6_cpu(s):
        for n, v in (s.get("docker_stats") or {}).items():
            if "k6" in n:
                return v["cpu_pct"]
        return None

    def order_cpu(s):
        return cpu("peakload-order-1")(s)

    def admission_cpu(s):
        return cpu("peakload-admission-1")(s)

    def outbox(s, key):
        return (s.get("outbox") or {}).get(key)

    # Outbox backlog at the end of the k6 run: the last sample taken at or before k6 ended.
    at_end = [s for s in samples if s["_t"] <= k6_end]
    backlog_end = outbox(at_end[-1], "outbox_backlog") if at_end else None

    result = {
        "run": os.path.basename(run_dir.rstrip("/")),
        "target_rate": run["rate"],
        "duration": run["duration"],
        "requests": metric_value(summary, "http_reqs", "count"),
        "achieved_rate": metric_value(summary, "http_reqs", "rate"),
        "dropped_iterations": metric_value(summary, "dropped_iterations", "count") or 0,
        "p50_ms": metric_value(summary, "http_req_duration", "med"),
        "p95_ms": metric_value(summary, "http_req_duration", "p(95)"),
        "p99_ms": metric_value(summary, "http_req_duration", "p(99)"),
        "error_rate": metric_value(summary, "http_req_failed", "rate"),
        "p99_under_200": verdict_bool(summary),
        "hikari_active_peak": peak(during, lambda s: (s.get("hikari") or {}).get("hikaricp_connections_active")),
        "hikari_pending_peak": peak(during, lambda s: (s.get("hikari") or {}).get("hikaricp_connections_pending")),
        "hikari_timeouts_total_end": (at_end[-1].get("hikari") or {}).get("hikaricp_connections_timeout_total")
        if at_end else None,
        "hikari_usage_mean_ms": round(statistics.mean(usage_ms), 3) if usage_ms else None,
        "pg_cpu_mean_pct": mean(during, cpu("peakload-postgres-1")),
        "pg_cpu_peak_pct": peak(during, cpu("peakload-postgres-1")),
        "order_cpu_mean_pct": mean(during, order_cpu),
        "order_cpu_peak_pct": peak(during, order_cpu),
        "admission_cpu_mean_pct": mean(during, admission_cpu),
        "admission_cpu_peak_pct": peak(during, admission_cpu),
        "k6_cpu_peak_pct": peak(during, k6_cpu),
        "pg_active_peak": peak(during, lambda s: (s.get("postgres") or {}).get("active")),
        "pg_lock_waiters_peak": peak(during, lambda s: (s.get("postgres") or {}).get("lock_waiters")),
        "pg_ungranted_locks_peak": peak(during, lambda s: (s.get("postgres") or {}).get("ungranted_locks")),
        "outbox_backlog_end": backlog_end,
        "outbox_backlog_peak": peak(during + [s for s in samples if s["_t"] > k6_end],
                                    lambda s: outbox(s, "outbox_backlog")),
        "outbox_oldest_age_peak_s": peak(during, lambda s: outbox(s, "outbox_oldest_unpublished_age_seconds")),
        "relay_drain_seconds": run.get("drain_seconds"),
        "sample_errors": sorted({e for s in samples for e in s.get("errors", [])})[:5],
    }
    return result


def verdict_bool(summary):
    # True if no p(99) threshold was crossed
    return not any("p(99)" in expr for _, expr in crossed(summary))


def fmt(v, digits=2):
    if v is None:
        return "n/a"
    if isinstance(v, float):
        return f"{v:.{digits}f}"
    return str(v)


def report(results_dir):
    runs = sorted(d for d in glob.glob(os.path.join(results_dir, "*"))
                  if os.path.exists(os.path.join(d, "run.json")) and os.path.exists(os.path.join(d, "k6-summary.json")))
    # warmup-* runs are discarded from the results and are not reported
    rows = [run_report(d) for d in runs if not os.path.basename(d).startswith("warmup")]
    rows.sort(key=lambda r: (r["target_rate"], r["run"]))
    json.dump(rows, open(os.path.join(results_dir, "report.json"), "w"), indent=2)

    header = ("| Run | Target req/s | Achieved req/s | Dropped iter. | p50 ms | p95 ms | p99 ms | Error rate | p99<200 |"
              " Hikari active peak | Hikari pending peak | PG CPU mean/peak % | Order CPU mean/peak % |"
              " Outbox backlog end | Outbox backlog peak | Relay drain s |")
    lines = [header, "|" + "---|" * 16]
    for r in rows:
        lines.append(
            f"| {r['run']} | {r['target_rate']} | {fmt(r['achieved_rate'])} | {fmt(r['dropped_iterations'], 0)} | "
            f"{fmt(r['p50_ms'])} | {fmt(r['p95_ms'])} | {fmt(r['p99_ms'])} | "
            f"{fmt(r['error_rate'] * 100 if r['error_rate'] is not None else None, 3)}% | "
            f"{'pass' if r['p99_under_200'] else 'FAIL'} | {fmt(r['hikari_active_peak'], 0)} | "
            f"{fmt(r['hikari_pending_peak'], 0)} | {fmt(r['pg_cpu_mean_pct'])}/{fmt(r['pg_cpu_peak_pct'])} | "
            f"{fmt(r['order_cpu_mean_pct'])}/{fmt(r['order_cpu_peak_pct'])} | {fmt(r['outbox_backlog_end'], 0)} | "
            f"{fmt(r['outbox_backlog_peak'], 0)} | {fmt(r['relay_drain_seconds'], 1)} |"
        )
    open(os.path.join(results_dir, "report.md"), "w").write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    if sys.argv[1] == "verdict":
        verdict(sys.argv[2])
    elif sys.argv[1] == "report":
        report(sys.argv[2])
    else:
        sys.exit("usage: summarise.py verdict <summary.json> | report <dir>")
