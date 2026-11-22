| Run | Orders | Assignments | Pending | Balance | Redeliveries rejected | Riders BUSY without active | Assignment lag p50 / p95 (s) | Restart to partitions assigned, per kill (s) | Restart to first event processed, per kill (s) | Drain after load end (s) | Invariants total |
|---|---:|---:|---:|---:|---:|---:|---|---|---|---:|---:|
| static-kill-1 | 3001 | 2994 | 7 | 0 | 0 | 0 | 1.008 / 19.378 | 3.3, 3.1, 3.1 | 3.4, 3.1, 3.2 | 0 | 0 |
| static-control-1 | 3001 | 2996 | 5 | 0 | 0 | 0 | 0.607 / 0.99 | n/a (no kill) | n/a (no kill) | 0 | 0 |
| static-kill-2 | 3000 | 2996 | 4 | 0 | 0 | 0 | 1.389 / 20.299 | 4.4, 3.1, 3.1 | 4.4, 3.2, 3.1 | 0 | 0 |
| static-control-2 | 3001 | 2990 | 11 | 0 | 0 | 0 | 0.615 / 0.989 | n/a (no kill) | n/a (no kill) | 0 | 0 |
