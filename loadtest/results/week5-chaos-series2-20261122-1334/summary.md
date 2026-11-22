| Run | Orders | Assignments | Pending | Balance (orders - assign - pending) | Processed = published | Redeliveries rejected | Assignment lag p50 / p95 (s) | Restart to first event processed (s) | Drain (s) | Invariants total |
|---|---:|---:|---:|---:|---|---:|---|---|---:|---:|
| kill-1 | 3000 | 2993 | 7 | 0 | 3000 = 3000 | 0 | 72.784 / 137.166 | 118.9, 78.8, 38.7 | 30 | 0 |
| control-1 | 3001 | 2994 | 7 | 0 | 3001 = 3001 | 0 | 0.61 / 0.983 | n/a (no kill) | 0 | 0 |
| kill-2 | 3001 | 3000 | 1 | 0 | 3001 = 3001 | 3 | 75.431 / 139.609 | 119.1, 79.3, 39.3 | 31 | 0 |
| control-2 | 3001 | 2990 | 11 | 0 | 3001 = 3001 | 0 | 0.61 / 0.986 | n/a (no kill) | 0 | 0 |
