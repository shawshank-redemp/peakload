| Run | Target req/s | Achieved req/s | Dropped iter. | p50 ms | p95 ms | p99 ms | Error rate | p99<200 | Hikari active peak | Hikari pending peak | PG CPU mean/peak % | Order CPU mean/peak % | Outbox backlog end | Outbox backlog peak | Relay drain s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| r0200 | 200 | 200.00 | 0 | 1.06 | 2.30 | 2.74 | 0% | pass | 0 | 0 | 13.72/15.80 | 15.19/18.47 | 64 | 190 | 1 |
| r0800 | 800 | 799.99 | 0 | 0.63 | 1.06 | 1.67 | 0% | pass | 3 | 0 | 30.39/37.17 | 26.84/33.86 | 671 | 671 | 1 |
| r1600 | 1600 | 1599.99 | 0 | 0.56 | 0.97 | 1.82 | 0% | pass | 3 | 0 | 62.05/74.68 | 53.82/62.49 | 435 | 1399 | 1 |
