#!/usr/bin/env python3
"""Samples admission's load-shedding state every 0.5 s until SIGTERM.
    python3 loadtest/shed_sampler.py <out.csv>
Columns: epoch, level (0 NORMAL, 1 ELEVATED, 2 HIGH, 3 HARD_CAP), in_flight_total, upstream_ewma_ms,
shed_critical, shed_standard, shed_low (cumulative counters from admission).
"""
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
    v = {"level": "", "in_flight": "", "ewma": "", "CRITICAL": 0.0, "STANDARD": 0.0, "LOW": 0.0}
    # Lines look like: name{application="admission",...} value, or name value when there are no labels.
    # Micrometer exports the total in-flight gauge (admission.in.flight.total) as admission_in_flight with no class
    # label; the per-class gauges have a class label. The total is the one without class.
    for line in out.splitlines():
        if not line or line.startswith("#"):
            continue
        head, value = line.rsplit(" ", 1)
        name, _, labels = head.partition("{")
        if name == "admission_load_level":
            v["level"] = int(float(value))
        elif name == "admission_in_flight" and 'class="' not in labels:
            v["in_flight"] = int(float(value))
        elif name == "admission_upstream_latency_ewma_ms":
            v["ewma"] = round(float(value), 3)
        elif name == "admission_shed_total":
            for cls in ("CRITICAL", "STANDARD", "LOW"):
                if f'class="{cls}"' in labels:
                    v[cls] = float(value)
    return v


def main():
    signal.signal(signal.SIGTERM, on_term)
    signal.signal(signal.SIGINT, on_term)
    with open(sys.argv[1], "w", buffering=1) as f:
        f.write("epoch,level,in_flight_total,upstream_ewma_ms,shed_critical,shed_standard,shed_low\n")
        while not stop:
            v = read_metrics()
            f.write(f"{time.time():.3f},{v['level']},{v['in_flight']},{v['ewma']},"
                    f"{v['CRITICAL']},{v['STANDARD']},{v['LOW']}\n")
            time.sleep(0.5)


if __name__ == "__main__":
    main()
