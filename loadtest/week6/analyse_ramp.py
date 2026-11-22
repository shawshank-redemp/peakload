#!/usr/bin/env python3
"""Week 6, step 1: numbers for one capacity run, from the files the run wrote. Computes only from those files.
    python3 loadtest/week6/analyse_ramp.py <run folder>
Status shares use largest-remainder rounding, so each table adds to exactly 100.00%. "Other" causes come from the
admission log: the WARN line is rate-limited, so each logged line counts itself plus the lines suppressed before it
(the "[N similar lines suppressed]" prefix); the total of those is the count of "other" failures the log covers.
"""
import collections
import gzip
import json
import os
import re
import sys


def largest_remainder(counts, total):
    raw = [100.0 * c / total for c in counts]
    cents = [int(r * 100) for r in raw]
    short = 10000 - sum(cents)
    for i in sorted(range(len(raw)), key=lambda i: -(raw[i] * 100 - cents[i]))[:short]:
        cents[i] += 1
    return [c / 100.0 for c in cents]


def pct(values, q):
    if not values:
        return None
    values = sorted(values)
    return values[min(len(values) - 1, int(round(q * (len(values) - 1))))]


def label(status, err):
    if status.startswith("2"):
        return "201" if status == "201" else f"{status}"
    if status == "503":
        return {"shed": "503 shed", "pool_timeout": "503 pool_timeout", "order_unavailable": "503 circuit_open",
                "other": "503 other"}.get(err, f"503 {err}")
    if status == "504":
        return "504 read_timeout" if err == "read_timeout" else f"504 {err}"
    return f"{status} {err}"


def main():
    run = sys.argv[1]
    counts = collections.Counter()
    accepted = []
    for line in gzip.open(os.path.join(run, "k6", "points.json.gz"), "rt"):
        try:
            p = json.loads(line)
        except ValueError:
            continue
        if p.get("type") != "Point":
            continue
        if p["metric"] == "outcome":
            t = p["data"]["tags"]
            counts[label(t.get("status", ""), t.get("err", ""))] += int(p["data"]["value"])
        elif p["metric"] == "accepted_latency_ms":
            accepted.append(float(p["data"]["value"]))
    total = sum(counts.values())
    summary = json.load(open(os.path.join(run, "k6", "k6-summary.json"))) if os.path.exists(
        os.path.join(run, "k6", "k6-summary.json")) else {"metrics": {}}
    m = summary["metrics"]
    print(f"# run: {run}")
    print(f"requests: {total}; achieved req/s: {m.get('http_reqs', {}).get('rate', 0):.1f}; "
          f"dropped iterations: {m.get('dropped_iterations', {}).get('count', 0)}")
    keys = sorted(counts, key=lambda k: (k != "201", k))
    shares = largest_remainder([counts[k] for k in keys], total) if total else []
    print("\n## status breakdown (adds to 100.00%)")
    for k, s in zip(keys, shares):
        print(f"  {k:24} {counts[k]:8}  {s:6.2f}%")
    print(f"  {'TOTAL':24} {total:8}  {sum(shares):6.2f}%")
    non2xx = 100.0 - sum(s for k, s in zip(keys, shares) if k.startswith("2") or k == "201")
    print(f"\nnon-2xx share: {non2xx:.2f}%  (stop rule: more than 5%)")
    print(f"\naccepted (201) latency ms: n={len(accepted)} p50={pct(accepted, .5)} p95={pct(accepted, .95)} "
          f"p99={pct(accepted, .99)}")

    samples = [json.loads(l) for l in open(os.path.join(run, "samples.jsonl")) if l.strip()]
    print("\n## resources during the timed run (docker stats, % of one core; sampled every 2 s)")
    for name in ("peakload-admission-1", "peakload-order-1", "peakload-postgres-1", "peakload-redis-1"):
        v = [s["cpu"][name] for s in samples if name in s.get("cpu", {})]
        if v:
            print(f"  {name:22} mean {sum(v)/len(v):6.0f}%  peak {max(v):6.0f}%")
    k6v = [val for s in samples for n, val in s.get("cpu", {}).items() if "k6" in n]
    if k6v:
        print(f"  {'k6':22} mean {sum(k6v)/len(k6v):6.0f}%  peak {max(k6v):6.0f}%")
    for label_, key in (("admission Hikari pending (menu pool)", ("admission", "hikari_pending")),
                        ("admission Hikari active", ("admission", "hikari_active")),
                        ("order Hikari pending", ("order", "hikari_pending")),
                        ("order Hikari active", ("order", "hikari_active")),
                        ("admission in-flight", ("admission", "in_flight"))):
        v = [s[key[0]][key[1]] for s in samples if s.get(key[0], {}).get(key[1]) is not None]
        if v:
            print(f"  {label_:38} peak {max(v):.0f}  mean {sum(v)/len(v):.1f}")
    print("  admission upstream pool in-use: not exposed as a metric (not measured)")

    lv = [s["admission"]["levels_seconds"] for s in samples if s.get("admission", {}).get("levels_seconds")]
    if len(lv) >= 2:
        first, last = lv[0], lv[-1]
        print("\n## load level seconds during the timed run (admission_load_level_seconds_total, delta)")
        for level in ("NORMAL", "ELEVATED", "HIGH", "HARD_CAP"):
            print(f"  {level:10} {last.get(level, 0) - first.get(level, 0):7.1f} s")

    log_path = os.path.join(run, "admission-log.txt")
    print("\n## causes behind 'other' (from the admission log; rate-limited, so counts cover what the log covers)")
    causes = collections.Counter()
    covered = 0
    if os.path.exists(log_path):
        pat = re.compile(r"order service call failed for \S+ \S+: ([\w.$]+)(?::| )")
        for line in open(log_path, errors="replace"):
            if "order service call failed" not in line:
                continue
            n = 1
            s = re.search(r"\[(\d+) similar lines suppressed", line)
            if s:
                n += int(s.group(1))
            m2 = pat.search(line)
            causes[m2.group(1) if m2 else "unparsed"] += n
            covered += n
    for c, n in causes.most_common():
        print(f"  {n:7}  {c}")
    print(f"  total covered by the log: {covered}; 503 other in the k6 counts: {counts.get('503 other', 0)}")

    print("\n## orders")
    c = open(os.path.join(run, "counts.txt")).read() if os.path.exists(os.path.join(run, "counts.txt")) else ""
    print("  " + c.replace("\n", " | "))
    print(f"  201 answers in the timed run: {counts.get('201', 0)}")


if __name__ == "__main__":
    main()
