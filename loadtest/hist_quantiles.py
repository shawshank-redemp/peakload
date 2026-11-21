#!/usr/bin/env python3
"""Percentiles of a Prometheus histogram over one run, from two snapshots (before and after the run).

    python3 loadtest/hist_quantiles.py <metric-name> <before.txt> <after.txt>

Prints p50, p95, p99 in seconds, by linear interpolation inside the bucket, and the sample count.
Buckets are the cumulative *_bucket{le="..."} series; the difference of the two snapshots is this run only.
"""
import re
import sys


def buckets(path, metric):
    counts = {}
    pattern = re.compile(r'^' + re.escape(metric) + r'_bucket\{[^}]*le="([^"]+)"[^}]*\}\s+([0-9.eE+-]+)$')
    for line in open(path):
        m = pattern.match(line.strip())
        if m:
            le = float('inf') if m.group(1) == '+Inf' else float(m.group(1))
            counts[le] = counts.get(le, 0.0) + float(m.group(2))
    return counts


def quantile(q, cumulative):
    total = cumulative[float('inf')]
    if total <= 0:
        return None
    target = q * total
    prev_le, prev_count = 0.0, 0.0
    for le in sorted(cumulative):
        count = cumulative[le]
        if count >= target:
            if le == float('inf'):
                return prev_le
            span = count - prev_count
            frac = 0.0 if span <= 0 else (target - prev_count) / span
            return prev_le + (le - prev_le) * frac
        prev_le, prev_count = le, count
    return prev_le


def main():
    metric, before, after = sys.argv[1], sys.argv[2], sys.argv[3]
    b, a = buckets(before, metric), buckets(after, metric)
    diff = {le: a.get(le, 0.0) - b.get(le, 0.0) for le in a}
    n = diff.get(float('inf'), 0.0)
    out = {'count': int(n)}
    for q in (0.5, 0.95, 0.99):
        v = quantile(q, diff) if n > 0 else None
        out[f'p{int(q*100)}'] = v
    print(out)


if __name__ == '__main__':
    main()
