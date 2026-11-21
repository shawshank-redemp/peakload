#!/usr/bin/env python3
"""Per-phase results of one run-outage.sh run: Redis up / Redis down / after recovery.
    python3 loadtest/outage_report.py <run-dir>
Phases use the exact times the driver recorded (events.txt). Switch times come from the limiter's mode samples
and from the admission log lines ("switched to local", "switched to Redis mode").
"""
import csv
import gzip
import json
import sys
from datetime import datetime, timezone


def parse_time(s):
    # k6 writes RFC3339 with up to nanoseconds; Python keeps microseconds.
    s = s.rstrip('Z')
    if '.' in s:
        head, frac = s.split('.', 1)
        s = head + '.' + frac[:6]
        return datetime.fromisoformat(s).replace(tzinfo=timezone.utc).timestamp()
    return datetime.fromisoformat(s).replace(tzinfo=timezone.utc).timestamp()


def pct(values, q):
    if not values:
        return None
    v = sorted(values)
    return v[min(len(v) - 1, int(round(q * (len(v) - 1))))]


def main():
    d = sys.argv[1]
    ev = dict(line.strip().split('=', 1) for line in open(f'{d}/events.txt') if '=' in line)
    t0, t_down, t_up = float(ev['t0']), float(ev['t_down']), float(ev['t_up'])
    phases = [('Redis up', t0, t_down), ('Redis down', t_down, t_up), ('After recovery', t_up, float('inf'))]

    acc = {}
    for name, a, b in phases:
        acc[name] = {'normal': {'n': 0, '201': 0, '429': 0, 'err': 0, 'd201': []},
                     'abusive': {'n': 0, '201': 0, '429': 0, 'err': 0, 'd201': []}}
    with gzip.open(f'{d}/points.json.gz', 'rt') as f:
        for line in f:
            o = json.loads(line)
            if o.get('type') != 'Point' or o.get('metric') not in ('http_reqs', 'http_req_duration'):
                continue
            tags = o['data'].get('tags') or {}
            group = tags.get('group')
            if group not in ('normal', 'abusive'):
                continue
            t = parse_time(o['data']['time'])
            phase = next((name for name, a, b in phases if a <= t < b), None)
            if phase is None:
                continue
            status = str(tags.get('status'))
            if o['metric'] == 'http_reqs':
                s = acc[phase][group]
                s['n'] += 1
                if status == '201':
                    s['201'] += 1
                elif status == '429':
                    s['429'] += 1
                else:
                    s['err'] += 1
            elif status == '201':
                acc[phase][group]['d201'].append(o['data']['value'])

    rows = []
    for name, a, b in phases:
        for group in ('normal', 'abusive'):
            s = acc[name][group]
            n = s['n']
            rows.append({
                'phase': name, 'group': group, 'requests': n, 'accepted_201': s['201'], 'limited_429': s['429'],
                'share_429_pct': round(100 * s['429'] / n, 2) if n else None,
                'errors': s['err'],
                'p99_accepted_ms': round(pct(s['d201'], 0.99), 2) if s['d201'] else None,
                'p50_accepted_ms': round(pct(s['d201'], 0.5), 2) if s['d201'] else None,
            })

    # Mode samples and switch times.
    samples = list(csv.DictReader(open(f'{d}/limiter.csv')))
    def first_after(t, mode):
        for s in samples:
            if float(s['epoch']) >= t and s['mode'] == str(mode):
                return float(s['epoch'])
        return None
    switch_to_local = first_after(t_down, 1)
    switch_back = first_after(t_up, 0)
    # Counters are cumulative in the admission process, which can outlive one run: report this run's deltas.
    first = samples[0] if samples else None
    last = samples[-1] if samples else None
    def delta(key):
        if first is None or last is None:
            return None
        return float(last[key]) - float(first[key])
    switches = {
        'to_local_seconds_after_outage_start': None if switch_to_local is None else round(switch_to_local - t_down, 2),
        'back_to_redis_seconds_after_recovery': None if switch_back is None else round(switch_back - t_up, 2),
        'circuit_opens_this_run': delta('circuit_opens'),
        'fallback_allowed_this_run': delta('fallback_allowed'),
        'fallback_limited_this_run': delta('fallback_limited'),
        'limiter_errors_this_run': delta('limiter_errors'),
    }

    # Log lines, with the time Docker stamped on them.
    log_times = []
    import re
    stamp = re.compile(r'(\d{4}-\d{2}-\d{2}T[0-9:.]+Z)')
    for line in open(f'{d}/limiter-log.txt'):
        m = stamp.search(line)
        if not m:
            continue
        message = line.split(' : ', 1)[-1].strip()[:140]
        t = parse_time(m.group(1))
        if t0 - 5 <= t <= t_up + 60:  # this run's window only
            log_times.append((t, message))
    switch_logs = [(round(t - t_down, 2) if 'local' in m and t >= t_down else round(t - t_up, 2), m)
                   for t, m in log_times if 'switched' in m]

    out = {'rows': rows, 'switches': switches, 'switch_log_lines': switch_logs}
    json.dump(out, open(f'{d}/outage-report.json', 'w'), indent=2)
    print('| Phase | Group | Requests | Accepted (201) | 429 | Share 429 % | Errors | p50 accepted ms | p99 accepted ms |')
    print('|---|---|---|---|---|---|---|---|---|')
    for r in rows:
        print(f"| {r['phase']} | {r['group']} | {r['requests']} | {r['accepted_201']} | {r['limited_429']} | "
              f"{r['share_429_pct']} | {r['errors']} | {r['p50_accepted_ms']} | {r['p99_accepted_ms']} |")
    print(json.dumps(switches, indent=1))
    print('switch log lines (seconds from outage start / recovery):')
    for s, m in switch_logs:
        print(f'  {s:>8}  {m}')


if __name__ == '__main__':
    main()
