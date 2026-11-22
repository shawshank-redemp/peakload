#!/usr/bin/env python3
"""Week 6, step 2: numbers for one run, computed only from the files the run wrote, and the invariant checks.
    python3 loadtest/week6/analyse_step2.py <run folder>   (writes analysis.txt; exit 1 if an invariant fails)

Create outcomes are the POST /orders answers of the timed run (k6 http_reqs points). Shares use largest-remainder
rounding, so each breakdown adds to exactly 100.00%. Classes:
  201 accepted; 429 rate limited; 503 shed or circuit open; other = any other POST answer (500, 504, 409, status 0).
Other requests (GET, PATCH; the NYE curve's reads and status updates) are counted by status in their own table, so a
200 on a GET is never counted as a create "other".

Invariants, checked for every run:
  a. no duplicate (client_id, idempotency_key) pair in idempotency_keys (the table's primary key already enforces
     this; the count is recorded at the end of the run)
  b. 201 answers <= orders created (no accepted POST without its order)
  c. orders - 201 answers <= POST answers that were not 201 (an order can exist without a 201 only when the client
     got an error: a timeout, 500 or 504, whose outcome is unknown; see the README)
  d. no unpublished order-outbox row at the end
A failing invariant exits 1. (a), (b) and (d) are the stop rules; (c) is also treated as a stop.
Runs made before the duplicate count was recorded report (a) as "not recorded".
"""
import gzip
import json
import os
import re
import sys
from collections import Counter


def largest_remainder(counts, total):
    if total == 0:
        return [0.0 for _ in counts]
    raw = [100.0 * c / total for c in counts]
    cents = [int(r * 100) for r in raw]
    short = 10000 - sum(cents)
    for i in sorted(range(len(raw)), key=lambda i: -(raw[i] * 100 - cents[i]))[:short]:
        cents[i] += 1
    return [c / 100.0 for c in cents]


def pct(values, q):
    if not values:
        return float("nan")
    s = sorted(values)
    k = (len(s) - 1) * q
    lo = int(k)
    hi = min(lo + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def read_points(path):
    reqs, latencies, dropped = [], [], 0
    with gzip.open(path, "rt") as f:
        for line in f:
            o = json.loads(line)
            if o.get("type") != "Point":
                continue
            m, d = o["metric"], o["data"]
            if m == "http_reqs":
                reqs.append(d["tags"])
            elif m == "http_req_duration" and d["tags"].get("status") == "201" and d["tags"].get("method") == "POST":
                latencies.append(d["value"])
            elif m == "dropped_iterations":
                dropped += int(d["value"])
    return reqs, latencies, dropped


def create_class(status):
    if status in ("201", "429", "503"):
        return status
    return "other"


def read_kv(path):
    out = {}
    if not os.path.exists(path):
        return out
    for line in open(path):
        for part in line.split():
            if "=" in part and not part.startswith("#"):
                k, v = part.split("=", 1)
                try:
                    out[k] = float(v)
                except ValueError:
                    pass
    return out


def samples_cpu(path):
    per = {}
    if not os.path.exists(path):
        return per
    for line in open(path):
        try:
            o = json.loads(line)
        except ValueError:
            continue
        for name, v in o.get("cpu", {}).items():
            if re.search(r"peakload-(postgres|order|admission|redis)-1$", name) or "k6-run" in name:
                key = "k6" if "k6-run" in name else name.replace("peakload-", "").replace("-1", "")
                per.setdefault(key, []).append(v)
    return per


def main(folder):
    lines = []

    def say(s=""):
        lines.append(s)

    reqs, latencies, dropped = read_points(os.path.join(folder, "k6", "points.json.gz"))
    posts = [t for t in reqs if t.get("method") == "POST"]
    others = [t for t in reqs if t.get("method") != "POST"]
    cls_order = ["201", "429", "503", "other"]
    post_classes = [create_class(t.get("status", "")) for t in posts]
    counts = [post_classes.count(c) for c in cls_order]
    shares = largest_remainder(counts, len(posts))
    non_201_posts = len(posts) - counts[0]

    say(f"run: {folder}")
    say(f"requests (timed run): {len(reqs)} ({len(posts)} POST, {len(others)} other methods); "
        f"dropped iterations: {dropped}")
    say("POST /orders outcomes (adds to 100.00%):")
    for c, n, s in zip(cls_order, counts, shares):
        say(f"  {c:<6} {n:>8}  {s:6.2f}%")
    say(f"  sum    {sum(counts):>8}  {sum(shares):6.2f}%")
    say(f"accepted (201) latency ms: p50={pct(latencies, .5):.1f} p95={pct(latencies, .95):.1f} "
        f"p99={pct(latencies, .99):.1f} (n={len(latencies)})")

    if others:
        say("other methods, by status (GET and PATCH answers; not create outcomes):")
        per = Counter((t.get("method"), t.get("status")) for t in others)
        for (m, st), n in sorted(per.items(), key=lambda x: (-x[1], str(x[0]))):
            say(f"  {m} {st}: {n}")

    groups = Counter(t.get("group", "") for t in posts)
    if set(groups) - {""}:
        say("POST outcomes by group (k6 tag):")
        for g in sorted(k for k in groups if k):
            per = Counter(create_class(t.get("status", "")) for t in posts if t.get("group") == g)
            tot = sum(per.values())
            sh = largest_remainder([per[c] for c in cls_order], tot)
            say(f"  {g}: n={tot} " + ", ".join(f"{c} {s:.2f}%" for c, s in zip(cls_order, sh)))

    cpu = samples_cpu(os.path.join(folder, "samples.jsonl"))
    if cpu:
        say("CPU per container, % (mean / peak over the timed run, sampled every 2 s):")
        for k in ("admission", "order", "postgres", "redis", "k6"):
            if k in cpu:
                v = cpu[k]
                say(f"  {k:<10} mean {sum(v)/len(v):6.1f}  peak {max(v):6.1f}")

    before = read_kv(os.path.join(folder, "pg-before.txt"))
    after = read_kv(os.path.join(folder, "pg-after.txt"))
    if before and after:
        say("postgres counters, before -> after:")
        for k in ("checkpoints_timed", "checkpoints_req", "autovacuum_count", "autoanalyze_count"):
            if k in before and k in after:
                say(f"  {k}: {before[k]:.0f} -> {after[k]:.0f} (delta {after[k]-before[k]:.0f})")

    kv = read_kv(os.path.join(folder, "counts.txt"))
    orders = kv.get("orders")
    unpub = kv.get("outbox_unpublished")
    dups = kv.get("idempotency_duplicate_pairs")
    failures = []
    if orders is None or unpub is None:
        failures.append("counts.txt missing")
    else:
        unknown = int(orders) - counts[0]
        say(f"orders={orders:.0f}; 201 answers={counts[0]}; orders without a 201 answer={unknown}; "
            f"non-201 POST answers={non_201_posts}")
        say("a. duplicate (client_id, idempotency_key) pairs: "
            + ("not recorded (run predates the check)" if dups is None else f"{dups:.0f}"))
        if dups is not None and int(dups) != 0:
            failures.append(f"duplicate idempotency pairs = {dups:.0f}")
        if counts[0] > int(orders):
            failures.append(f"b. 201 answers ({counts[0]}) > orders ({orders:.0f}): accepted without an order")
        if unknown > non_201_posts:
            failures.append(f"c. orders without a 201 ({unknown}) > non-201 POST answers ({non_201_posts})")
        if int(unpub) != 0:
            failures.append(f"d. outbox_unpublished = {unpub:.0f}")
        say("invariants: " + ("all hold" if not failures else "FAILED: " + "; ".join(failures)))
    if orders is None:
        say("invariants: cannot check (counts.txt missing)")
        failures.append("counts.txt missing")

    text = "\n".join(lines) + "\n"
    with open(os.path.join(folder, "analysis.txt"), "w") as f:
        f.write(text)
    print(text, end="")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
