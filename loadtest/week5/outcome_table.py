#!/usr/bin/env python3
"""Markdown table of the outcome shares for runs, one row per run, kind and phase, one column per outcome category.
Every row's percentages add to exactly 100.00 (largest-remainder rounding).

    python3 loadtest/week5/outcome_table.py <run folder> [<run folder> ...]
"""
import collections
import gzip
import json
import os
import sys


def largest_remainder(counts, total):
    """Percentages to 2 decimals that add to exactly 100.00 (the standard rounding that keeps the total)."""
    raw = [100.0 * c / total for c in counts]
    cents = [int(r * 100) for r in raw]
    short = 10000 - sum(cents)
    order = sorted(range(len(raw)), key=lambda i: -(raw[i] * 100 - cents[i]))
    for i in order[:short]:
        cents[i] += 1
    return [c / 100.0 for c in cents]


def outcomes(run):
    counts = collections.Counter()
    for line in gzip.open(os.path.join(run, "k6", "points.json.gz"), "rt"):
        try:
            p = json.loads(line)
        except ValueError:
            continue
        if p.get("type") == "Point" and p.get("metric") == "outcome":
            t = p["data"]["tags"]
            st = t.get("status", "")
            label = "2xx" if st.startswith("2") else f"{st} {t.get('err', '')}".strip()
            counts[(t.get("kind"), t.get("phase"), label)] += int(p["data"]["value"])
    return counts


def main():
    runs = sys.argv[1:]
    data = {os.path.basename(r.rstrip("/")): outcomes(r) for r in runs}
    cats = sorted({c for d in data.values() for (_, _, c) in d})
    print("| Run | Kind | Phase | n | " + " | ".join(cats) + " |")
    print("|---|---|---|---:|" + "---:|" * len(cats))
    for run, d in data.items():
        for kind in ("create", "read", "menu"):
            for phase in ("before", "during", "after"):
                row = {c: n for (k, ph, c), n in d.items() if k == kind and ph == phase}
                total = sum(row.values())
                if not total:
                    continue
                shares = largest_remainder([row.get(c, 0) for c in cats], total)
                cells = [f"{v:.2f}%" if v else "0%" for v in shares]
                print(f"| {run} | {kind} | {phase} | {total} | " + " | ".join(cells) + " |")


if __name__ == "__main__":
    main()
