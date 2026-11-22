#!/usr/bin/env python3
"""Week 5, step 2: recovery per dispatch kill, from the dispatch logs and the database.
For each restart: the time the consumer joined the group and the time its partitions were assigned (from the
dispatch container's Kafka client log lines, with timestamps), and the first event processed after the restart
(processed_events.processed_at). Also the time until the backlog was fully drained (the drain check passed).
    python3 loadtest/chaos/recovery.py <run folder>
"""
import datetime
import json
import os
import re
import subprocess
import sys


def iso_ms(s):
    return int(datetime.datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp() * 1000)


def main():
    run = sys.argv[1]
    ev = dict(l.strip().split("=", 1) for l in open(os.path.join(run, "events.txt")) if "=" in l)
    logs = subprocess.run(["docker", "compose", "logs", "--timestamps", "--no-color", "dispatch"],
                          capture_output=True, text=True).stdout.splitlines()
    parsed = []
    for line in logs:
        m = re.search(r"(\d{4}-\d{2}-\d{2}T[\d:.]+Z)", line)
        if not m:
            continue
        parsed.append((iso_ms(m.group(1)), line[m.end():]))
    result = {}
    kills = sorted(int(k.split("_")[1]) for k in ev if k.startswith("kill_"))
    for n in kills:
        restart = int(ev[f"restart_{n}"])
        kill = int(ev[f"kill_{n}"])
        joined = next((t for t, m in parsed if t >= restart and "Successfully joined group" in m), None)
        assigned = next((t for t, m in parsed if t >= restart and "partitions assigned:" in m), None)
        first_processed = None
        q = ("SELECT extract(epoch FROM min(processed_at))*1000 FROM dispatch.processed_events "
             f"WHERE processed_at > to_timestamp({restart} / 1000.0)")
        out = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "peakload", "-d", "peakload",
                              "-At", "-c", q], capture_output=True, text=True).stdout.strip()
        if out and out != "":
            first_processed = int(float(out))
        rec = {
            "kill_to_restart_s": round((restart - kill) / 1000, 1),
            "restart_to_joined_s": None if joined is None else round((joined - restart) / 1000, 1),
            "restart_to_partitions_assigned_s": None if assigned is None else round((assigned - restart) / 1000, 1),
            "restart_to_first_event_processed_s": None if first_processed is None else round((first_processed - restart) / 1000, 1),
        }
        result[f"kill_{n}"] = rec
    if "T0" in ev and "drain_seconds" in ev:
        result["drain_seconds_after_load_end"] = int(ev["drain_seconds"])
    with open(os.path.join(run, "recovery.json"), "w") as fh:
        json.dump(result, fh, indent=2)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
