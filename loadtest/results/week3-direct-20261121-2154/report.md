| Run | Target req/s | Achieved req/s | Dropped iter. | p50 ms | p95 ms | p99 ms | Error rate | p99<200 | Hikari active peak | Hikari pending peak | PG CPU mean/peak % | Order CPU mean/peak % | Outbox backlog end | Outbox backlog peak | Relay drain s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| r0200 | 200 | 200.03 | 0 | 0.83 | 2.24 | 4.23 | 0% | pass | 0 | 0 | 10.08/15.45 | 31.03/41.17 | 36 | 195 | 0 |
| r0800 | 800 | 799.95 | 0 | 0.62 | 1.16 | 3.78 | 0% | pass | 3 | 0 | 34.38/41.53 | 36.31/54.78 | 428 | 818 | 0 |
| r1600 | 1600 | 1600.01 | 0 | 0.58 | 1.27 | 6.21 | 0% | pass | 5 | 0 | 58.50/69.07 | 56.31/65.63 | 382 | 1583 | 1 |
