#!/usr/bin/env python3
"""Week 5, step 2: the drain check. Exit 0 only when ALL of these hold, otherwise exit 1 (the caller keeps waiting):
  (a) dispatch processed count == number of published order events (outbox rows with published_at set);
  (b) orders created - assignments - pending_orders == 0;
  (c) for each consumer group (dispatch-order-events and order-events-consumer), every partition's committed offset
      equals its log end offset. The group's total lag is NOT used: it read 0 right after a dispatch restart while
      events were still unprocessed.
Every check's values are appended to <OUT>/drain-check.jsonl with a timestamp.
    python3 loadtest/chaos/drain.py <OUT>
"""
import json
import subprocess
import sys
import time

GROUPS = ["dispatch-order-events", "order-events-consumer"]


def psql(query):
    return subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "peakload", "-d", "peakload",
                           "-At", "-c", query], capture_output=True, text=True, check=True).stdout.strip()


def group_offsets(group):
    """(all partitions in sync, detail) from rpk's per-partition rows of the order-events topic."""
    out = subprocess.run(["docker", "compose", "exec", "-T", "redpanda", "rpk", "group", "describe", group],
                         capture_output=True, text=True).stdout
    rows = []
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 5 and parts[0] == "order-events" and parts[1].isdigit():
            rows.append((parts[1], parts[2], parts[4]))
    if not rows:
        return False, "no partition rows"
    in_sync = all(cur.isdigit() and end.isdigit() and cur == end for _, cur, end in rows)
    detail = ",".join(f"p{p}:{cur}/{end}" for p, cur, end in rows)
    return in_sync, detail


def main():
    out = sys.argv[1]
    published = int(psql("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL") or 0)
    processed = int(psql("SELECT count(*) FROM dispatch.processed_events") or 0)
    orders = int(psql("SELECT count(*) FROM orders") or 0)
    assigned = int(psql("SELECT count(*) FROM dispatch.assignments") or 0)
    pending = int(psql("SELECT count(*) FROM dispatch.pending_orders") or 0)
    a = processed == published
    b = orders - assigned - pending == 0
    dispatch_sync, dispatch_detail = group_offsets(GROUPS[0])
    order_sync, order_detail = group_offsets(GROUPS[1])
    c = dispatch_sync and order_sync
    record = {"t": int(time.time() * 1000), "published": published, "processed": processed,
              "a_processed_equals_published": a, "orders": orders, "assignments": assigned, "pending": pending,
              "b_orders_balance_zero": b, "dispatch_offsets": dispatch_detail, "order_offsets": order_detail,
              "c_offsets_in_sync": bool(c), "drained": bool(a and b and c)}
    with open(f"{out}/drain-check.jsonl", "a") as fh:
        fh.write(json.dumps(record) + "\n")
    print(json.dumps(record))
    sys.exit(0 if record["drained"] else 1)


if __name__ == "__main__":
    main()
