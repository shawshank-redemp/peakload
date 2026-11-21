| Run | Target req/s | Achieved req/s | Dropped iter. | p50 ms | p95 ms | p99 ms | Error rate | p99<200 | Hikari active peak | Hikari pending peak | PG CPU mean/peak % | Order CPU mean/peak % | Outbox backlog end | Outbox backlog peak | Relay drain s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| r0200 | 200 | 200.01 | 0 | 1.09 | 2.47 | 4.41 | 0% | pass | 0 | 0 | 13.93/16.33 | 11.38/18.39 | 8 | 185 | 0 |
| r0800 | 800 | 799.99 | 0 | 0.70 | 1.20 | 2.36 | 0% | pass | 3 | 0 | 30.31/34.06 | 27.89/34.55 | 443 | 666 | 0 |
| r1600 | 1600 | 1599.94 | 0 | 0.69 | 1.58 | 5.10 | 0% | pass | 3 | 0 | 64.67/84.96 | 52.22/63.56 | 462 | 1550 | 0 |
