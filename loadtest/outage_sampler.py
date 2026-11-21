#!/usr/bin/env python3
"""Samples the rate limiter's mode and counters from admission every ~0.5 s, until SIGTERM.
    python3 loadtest/outage_sampler.py <out.csv>
Columns: epoch, mode (0 = Redis, 1 = local), fallback_allowed, fallback_limited, circuit_opens, limiter_errors.
"""
import re
import signal
import subprocess
import sys
import time

URL = "http://localhost:8080/actuator/prometheus"
stop = False


def on_term(signum, frame):
    global stop
    stop = True


def read_metrics():
    out = subprocess.run(["curl", "-s", "--max-time", "2", URL], capture_output=True, text=True).stdout
    values = {"mode": None, "allowed": 0.0, "limited": 0.0, "opens": 0.0, "errors": 0.0}
    for line in out.splitlines():
        if line.startswith("admission_limiter_mode{") or line.startswith("admission_limiter_mode "):
            values["mode"] = float(line.rsplit(" ", 1)[1])
        elif line.startswith("admission_limiter_fallback_total{") and 'outcome="allowed"' in line:
            values["allowed"] = float(line.rsplit(" ", 1)[1])
        elif line.startswith("admission_limiter_fallback_total{") and 'outcome="limited"' in line:
            values["limited"] = float(line.rsplit(" ", 1)[1])
        elif line.startswith("admission_limiter_circuit_opens_total"):
            values["opens"] = float(line.rsplit(" ", 1)[1])
        elif line.startswith("admission_limiter_errors_total"):
            values["errors"] = float(line.rsplit(" ", 1)[1])
    return values


def main():
    signal.signal(signal.SIGTERM, on_term)
    signal.signal(signal.SIGINT, on_term)
    with open(sys.argv[1], "w", buffering=1) as f:
        f.write("epoch,mode,fallback_allowed,fallback_limited,circuit_opens,limiter_errors\n")
        while not stop:
            v = read_metrics()
            mode = "" if v["mode"] is None else int(v["mode"])
            f.write(f"{time.time():.3f},{mode},{v['allowed']},{v['limited']},{v['opens']},{v['errors']}\n")
            time.sleep(0.5)


if __name__ == "__main__":
    main()
