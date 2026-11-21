| Run | Target req/s | Achieved req/s | Dropped iter. | p50 ms | p95 ms | p99 ms | Error rate | p99<200 | Hikari active peak | Hikari pending peak | PG CPU mean/peak % | Order CPU mean/peak % | Outbox backlog end | Outbox backlog peak | Relay drain s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| r0200 | 200 | 200.02 | 0 | 1.13 | 2.69 | 6.18 | 0% | pass | 1 | 0 | 10.08/12.06 | 26.89/42.70 | 137 | 195 | 1 |
| r0800 | 800 | 799.99 | 0 | 0.72 | 1.55 | 4.85 | 0% | pass | 3 | 0 | 30.90/36.84 | 39.69/63.32 | 591 | 844 | 1 |
| r1600 | 1600 | 1599.72 | 8 | 0.70 | 2.69 | 30.02 | 0% | pass | 6 | 0 | 64.55/96.32 | 64.57/111.97 | 401 | 1514 | 1 |
