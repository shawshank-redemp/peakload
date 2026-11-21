| Run | Target req/s | Achieved req/s | Dropped iter. | p50 ms | p95 ms | p99 ms | Error rate | p99<200 | Hikari active peak | Hikari pending peak | PG CPU mean/peak % | Order CPU mean/peak % | Outbox backlog end | Outbox backlog peak | Relay drain s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| r0200 | 200 | 199.96 | 0 | 1.21 | 2.37 | 4.04 | 0% | pass | 1 | 0 | 10.84/12.14 | 11.24/18.04 | 5 | 159 | 0 |
| r0800 | 800 | 800.01 | 0 | 0.81 | 2.41 | 35.12 | 0% | pass | 2 | 0 | 27.02/34.71 | 25.39/30.85 | 32 | 689 | 1 |
| r1600 | 1600 | 1599.97 | 0 | 0.84 | 2.80 | 16.93 | 0% | pass | 3 | 0 | 70.74/90.65 | 60.23/86.62 | 1235 | 1235 | 1 |
