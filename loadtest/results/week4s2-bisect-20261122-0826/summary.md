| Run | Condition | Achieved req/s | Dropped iter. | p50 ms | p95 ms | p99 ms | Fail rate | Order CPU mean/peak % | PG CPU mean/peak % | Hikari pending peak | Outbox backlog peak | Drain s | Host CPU sum mean/peak (100 = 1 core) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| r1-01-cur-disp-on-pg-on-direct | current / dispatch on / pg_stat on / direct | 4323.02 | 2294 | 1.74 | 52.53 | 138.11 | 0.0000 | 111/161 | 131/175 | 191 | 41217 | 12 | 448/662 |
| r1-02-cur-disp-on-pg-on-direct-reads50 | current / dispatch on / pg_stat on / direct | 4446.40 | 100 | 1.58 | 12.70 | 39.12 | 0.0113 | 110/155 | 133/191 | 108 | 38301 | 11 | 433/522 |
| r1-03-cur-disp-off-pg-on-direct | current / dispatch off / pg_stat on / direct | 4286.83 | 3153 | 2.35 | 139.81 | 248.89 | 0.0000 | 119/168 | 123/191 | 191 | 54249 | 15 | 454/657 |
| r1-04-cur-disp-off-pg-off-direct | current / dispatch off / pg_stat off / direct | 4350.56 | 1476 | 1.84 | 67.44 | 148.47 | 0.0000 | 118/170 | 123/177 | 190 | 52324 | 15 | 423/552 |
| r1-05-cur-disp-off-pg-off-admission | current / dispatch off / pg_stat off / admission | 902.08 | 103752 | 2051.32 | 4159.38 | 6441.43 | 0.5012 | 25/85 | 25/82 | 26 | 709 | 0 | 264/669 |
| r1-06-week1-disp-off-pg-off-direct | week1 / dispatch off / pg_stat off / direct | 4228.53 | 4837 | 36.19 | 346.88 | 450.80 | 0.0000 | 155/351 | 112/183 | 191 | 67855 | 18 | 452/634 |
| r2-01-week1-disp-off-pg-off-direct | week1 / dispatch off / pg_stat off / direct | 4032.92 | 10996 | 53.45 | 649.82 | 787.68 | 0.0000 | 156/345 | 108/178 | 191 | 62406 | 16 | 465/646 |
| r2-02-cur-disp-off-pg-off-admission | current / dispatch off / pg_stat off / admission | 812.66 | 106282 | 2185.27 | 4240.22 | 5604.22 | 0.4974 | 62/229 | 30/51 | 0 | 1668 | 0 | 276/608 |
| r2-03-cur-disp-off-pg-off-direct | current / dispatch off / pg_stat off / direct | 4399.80 | n/a | 1.59 | 9.50 | 21.24 | 0.0000 | 129/181 | 126/205 | 57 | 44902 | 10 | 406/546 |
| r2-04-cur-disp-off-pg-on-direct | current / dispatch off / pg_stat on / direct | 4396.11 | 109 | 1.81 | 12.14 | 35.14 | 0.0000 | 128/189 | 124/177 | 168 | 45193 | 12 | 400/522 |
| r2-05-cur-disp-on-pg-on-direct-reads50 | current / dispatch on / pg_stat on / direct | 4299.94 | 3585 | 92.14 | 265.78 | 339.34 | 0.0116 | 119/228 | 136/218 | 192 | 60534 | 19 | 449/609 |
| r2-06-cur-disp-on-pg-on-direct | current / dispatch on / pg_stat on / direct | 4072.05 | 7933 | 375.77 | 551.27 | 639.99 | 0.0000 | 107/167 | 127/183 | 191 | 61687 | 21 | 433/552 |
