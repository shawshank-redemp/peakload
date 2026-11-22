#!/usr/bin/env python3
"""Week 5 measurement E: numbers for one run, from the files the run wrote. Computes only from those files.

    python3 loadtest/week5/analyse.py <run folder>

Phases (k6 tag): before 0-30 s, during 30-60 s, after 60-90 s, relative to T0 of the timed run.
Breaker: 'order_unavailable' is the 503 body for circuit_open. Time to open = first such answer after the order stop.
Time to close (proxy) = first create answered 201 after the order start.
Stall windows: consecutive seconds where a request failed or the p99 of that second's latency exceeded 250 ms;
only windows longer than 1 s are listed, each with its timestamps and phase.
"""
import collections
import datetime
import gzip
import json
import os
import re
import sys

SPIKE_MS = 250.0


def ms(iso):
    return int(datetime.datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp() * 1000)


def pct(values, q):
    if not values:
        return None
    values = sorted(values)
    return values[min(len(values) - 1, int(round(q * (len(values) - 1))))]


def read_events(run):
    events = {}
    with open(os.path.join(run, "events.txt")) as fh:
        for line in fh:
            if "=" in line:
                k, v = line.strip().split("=", 1)
                events[k] = int(v)
    return events


def main():
    run = sys.argv[1]
    ev = read_events(run)
    t0 = ev["T0"]
    outcomes = collections.Counter()           # (kind, phase, status, err) -> n
    lat = collections.defaultdict(list)        # (kind, phase, ok|failed) -> [ms]
    per_sec = collections.defaultdict(lambda: {"fail": 0, "lat": []})
    first_open = None
    first_201_after_start = None
    for line in gzip.open(os.path.join(run, "k6", "points.json.gz"), "rt"):
        try:
            p = json.loads(line)
        except ValueError:
            continue
        if p.get("type") != "Point":
            continue
        metric, d = p["metric"], p["data"]
        tags = d.get("tags", {})
        t = ms(d["time"])
        sec = int((t - t0) / 1000)
        if metric == "outcome":
            n = int(d["value"])
            status, err, kind, phase = tags.get("status"), tags.get("err"), tags.get("kind"), tags.get("phase")
            outcomes[(kind, phase, status, err)] += n
            if status and int(status) >= 400:
                per_sec[sec]["fail"] += n
            if err == "order_unavailable" and "stop_order" in ev and t >= ev["stop_order"] and first_open is None:
                first_open = t
            if kind == "create" and status == "201" and "start_order" in ev and t >= ev["start_order"] \
                    and first_201_after_start is None:
                first_201_after_start = t
        elif metric in ("ok_latency_ms", "fail_latency_ms"):
            kind, phase = tags.get("kind"), tags.get("phase")
            bucket = "ok" if metric == "ok_latency_ms" else "failed"
            lat[(kind, phase, bucket)].append(float(d["value"]))
            per_sec[sec]["lat"].append(float(d["value"]))

    print(f"# run: {run}")
    print("\n## outcomes by kind, phase, status, error")
    print("kind    phase   status err                 count")
    for (kind, phase, status, err), n in sorted(outcomes.items(), key=lambda x: tuple(str(i) for i in x[0])):
        print(f"{str(kind):7} {str(phase):7} {str(status):6} {str(err):19} {n}")

    print("\n## full status breakdown per kind and phase (every category; the total must be 100%)")
    for kind in ("create", "read", "menu"):
        for phase in ("before", "during", "after"):
            cats = {(s, e): n for (k, ph, s, e), n in outcomes.items() if k == kind and ph == phase}
            total = sum(cats.values())
            if not total:
                continue
            print(f"  {kind} {phase} (n={total}):")
            acc = 0.0
            for (st, er), n in sorted(cats.items(), key=lambda x: -x[1]):
                pct_ = 100.0 * n / total
                acc += pct_
                label = "2xx" if st and st.startswith("2") else f"{st} {er}"
                print(f"      {label:32} {n:7}  {pct_:6.2f}%")
            print(f"      {'TOTAL':32} {total:7}  {acc:6.2f}%")

    print("\n## latency ms (k6 request duration)")
    for key in sorted(lat, key=str):
        v = lat[key]
        print(f"  {key[0]:6} {key[1]:7} {key[2]:6} n={len(v):6} p50={pct(v,0.5):.1f} p99={pct(v,0.99):.1f}")

    print("\n## breaker")
    if "stop_order" in ev:
        if first_open:
            print(f"  time to open: {(first_open - ev['stop_order'])/1000:.2f} s after the order stop")
        else:
            print("  time to open: no circuit_open answer after the stop")
    if "start_order" in ev:
        if first_201_after_start:
            print(f"  time to close (proxy: first create 201 after the order start): "
                  f"{(first_201_after_start - ev['start_order'])/1000:.2f} s")
        else:
            print("  time to close (proxy): no create answered 201 after the order start")

    print(f"\n## windows longer than 1 s with failures or p99 > {SPIKE_MS:.0f} ms (seconds from T0)")
    flagged = []
    for sec in sorted(per_sec):
        v = per_sec[sec]
        p99 = pct(v["lat"], 0.99)
        if v["fail"] > 0 or (p99 is not None and p99 > SPIKE_MS):
            flagged.append((sec, v["fail"], p99))
    windows, cur = [], []
    for item in flagged:
        if cur and item[0] == cur[-1][0] + 1:
            cur.append(item)
        else:
            if cur:
                windows.append(cur)
            cur = [item]
    if cur:
        windows.append(cur)
    listed = [w for w in windows if len(w) > 1]
    for w in listed:
        start, end = w[0][0], w[-1][0]
        phase = "before" if start < 30 else "during" if start < 60 else "after"
        failed = sum(x[1] for x in w)
        worst = max((x[2] or 0) for x in w)
        print(f"  t={start:3d}s..{end:3d}s ({len(w)} s, phase starts {phase}): failed={failed} worst p99={worst:.0f} ms")
    if not listed:
        print("  none")

    print("\n## admission CPU during the timed run (docker stats, from samples.jsonl)")
    cpu = []
    with open(os.path.join(run, "samples.jsonl")) as fh:
        for line in fh:
            try:
                r = json.loads(line)
            except ValueError:
                continue
            ts = ms(r["ts"])
            a = (r.get("docker_stats") or {}).get("peakload-admission-1")
            if a and t0 <= ts <= t0 + 90_000:
                cpu.append(a["cpu_pct"])
    if cpu:
        print(f"  mean {sum(cpu)/len(cpu):.0f}%  peak {max(cpu):.0f}%  ({len(cpu)} samples; 100% = one core)")

    print("\n## Postgres counters and row counts")
    for name in ("pg-before.txt", "pg-after.txt"):
        path = os.path.join(run, name)
        if os.path.exists(path):
            print(f"  {name}: " + " | ".join(l.strip() for l in open(path) if l.strip()))
    created = sum(n for (k, ph, s, e), n in outcomes.items() if k == "create" and s == "201")
    print(f"  creates answered 201 in the timed run: {created}")

    print("\n## admission retry and breaker counters (end of run; a counter absent here was never incremented)")
    path = os.path.join(run, "admission-metrics.txt")
    if os.path.exists(path):
        found = False
        for l in open(path):
            if re.search(r"retries|circuit|budget", l):
                print("  " + l.strip())
                found = True
        if not found:
            print("  none recorded")


if __name__ == "__main__":
    main()
