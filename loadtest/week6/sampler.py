#!/usr/bin/env python3
"""Week 6, step 1: samples every 2 s until SIGTERM: CPU and memory per container (docker stats), and from the
admission and order actuators: Hikari active and pending, admission in-flight, and the seconds spent at each load level.
    python3 loadtest/week6/sampler.py --out <file.jsonl>
"""
import argparse
import json
import re
import signal
import subprocess
import time
import urllib.request

stop = False


def on_term(*_):
    global stop
    stop = True


def prom(url):
    try:
        with urllib.request.urlopen(url, timeout=3) as r:
            return r.read().decode()
    except Exception:
        return ""


def gauge(text, name, label=None):
    total = 0.0
    seen = False
    for line in text.splitlines():
        if line.startswith(name) and (label is None or label in line):
            if line[len(name)] not in " {":
                continue
            try:
                total += float(line.rsplit(" ", 1)[1])
                seen = True
            except ValueError:
                pass
    return total if seen else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--interval", type=float, default=2.0)
    args = ap.parse_args()
    signal.signal(signal.SIGTERM, on_term)
    signal.signal(signal.SIGINT, on_term)
    with open(args.out, "a", buffering=1) as fh:
        while not stop:
            t = int(time.time() * 1000)
            stats = {}
            try:
                out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}} {{.CPUPerc}} {{.MemUsage}}"],
                                     capture_output=True, text=True, timeout=20).stdout
                for line in out.splitlines():
                    parts = line.split()
                    if len(parts) >= 2:
                        stats[parts[0]] = float(parts[1].rstrip("%"))
            except Exception:
                pass
            adm = prom("http://localhost:8080/actuator/prometheus")
            order = prom("http://localhost:8081/actuator/prometheus")
            levels = {}
            for line in adm.splitlines():
                m = re.match(r'admission_load_level_seconds_total\{[^}]*level="([A-Z_]+)"[^}]*\} (\S+)', line)
                if m:
                    levels[m.group(1)] = float(m.group(2))
            fh.write(json.dumps({
                "t": t, "cpu": stats,
                "admission": {"hikari_pending": gauge(adm, "hikaricp_connections_pending"),
                              "hikari_active": gauge(adm, "hikaricp_connections_active"),
                              "in_flight": gauge(adm, "admission_in_flight"),
                              "load_level": gauge(adm, "admission_load_level"),
                              "levels_seconds": levels},
                "order": {"hikari_pending": gauge(order, "hikaricp_connections_pending"),
                          "hikari_active": gauge(order, "hikaricp_connections_active")},
            }) + "\n")
            time.sleep(args.interval)


if __name__ == "__main__":
    main()
