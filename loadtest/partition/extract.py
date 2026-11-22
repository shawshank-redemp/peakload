#!/usr/bin/env python3
"""Parses the psql output of 02-measure-deterministic.sql (one file per repeat) into a markdown table.

    python3 loadtest/partition/extract.py <dir with measure-*.txt>

Reports only what the plans and queries printed: planning time, execution time, the top node's buffers (shared hit,
read; the top node includes its children), and the number of leaf scan nodes (partitions probed). Nothing is computed
beyond the sum of buffers.
"""
import glob
import os
import re
import sys


def sections(text):
    out, name, buf = {}, None, []
    for line in text.splitlines():
        if line.startswith("== "):
            if name:
                out[name] = buf
            name, buf = line[3:].strip(), []
        elif name:
            buf.append(line)
    if name:
        out[name] = buf
    return out


def explain_stats(lines):
    plan_time = exec_time = None
    top_buf = None
    plan_buf = None
    in_planning = False
    tables = set()
    for l in lines:
        stripped = l.strip()
        m = re.search(r"Planning Time: ([0-9.]+) ms", l)
        if m:
            plan_time = float(m.group(1))
        m = re.search(r"Execution Time: ([0-9.]+) ms", l)
        if m:
            exec_time = float(m.group(1))
        if stripped.startswith("Planning:"):
            in_planning = True
            continue
        m = re.search(r"Buffers: shared hit=(\d+)(?: read=(\d+))?", l)
        if m and not stripped.startswith("->") and top_buf is None and not in_planning:
            top_buf = (int(m.group(1)), int(m.group(2) or 0))
        if m and in_planning and plan_buf is None:
            plan_buf = int(m.group(1))
        if stripped.startswith("Planning Time"):
            in_planning = False
        for t in re.findall(r"(?:Scan|Scan Backward|Only Scan)(?: using \S+)? on (\w+)", l):
            if t.startswith(("orders", "outbox")):
                tables.add(t)
    # Distinct leaf tables scanned: each partition probed counts once; a flat table counts as one.
    leaves = len(tables)
    return plan_time, exec_time, top_buf, leaves, plan_buf


def main():
    d = sys.argv[1]
    files = sorted(glob.glob(os.path.join(d, "measure-*.txt")))
    rows = {}
    for f in files:
        secs = sections(open(f).read())
        for name, lines in secs.items():
            if name.startswith("sizes") or name.startswith("WAL"):
                continue
            rows.setdefault(name, []).append(explain_stats(lines))
    print("| Measure | Repeats | Planning ms | Execution ms | Buffers hit/read, top node (per repeat) | Planning buffers hit | Relations scanned |")
    print("|---|---|---|---|---|---|---|")
    for name, runs in rows.items():
        def rng(vals):
            vals = [v for v in vals if v is not None]
            if not vals:
                return "n/a"
            return f"{min(vals):.3f}-{max(vals):.3f}" if max(vals) != min(vals) else f"{vals[0]:.3f}"
        bufs = [r[2] for r in runs if r[2]]
        buf_txt = "; ".join(f"{h}/{rd}" for h, rd in bufs) if bufs else "n/a"
        pb = [r[4] for r in runs if r[4] is not None]
        plan_buf_txt = "; ".join(str(x) for x in pb) if pb else "n/a"
        leaves = sorted({r[3] for r in runs})
        print(f"| {name} | {len(runs)} | {rng([r[0] for r in runs])} | {rng([r[1] for r in runs])} | {buf_txt} | {plan_buf_txt} | {leaves} |")


if __name__ == "__main__":
    main()
