### week3s5-nye-shedoff-run3-20261122-0006
| Phase | Class | Target req/s | Achieved req/s | Accepted % | Shed (503) % | Other 503 | Other errors | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| calm | CRITICAL | 200 | 200.0 | 100.0 | 0.0 | 0 | 0 | 3.45 | 13.07 | 46.99 |
| calm | STANDARD | 20 | 24.1 | 100.0 | 0.0 | 0 | 0 | 1.75 | 6.29 | 25.9 |
| calm | LOW | 400 | 400.0 | 100.0 | 0.0 | 0 | 0 | 1.02 | 4.56 | 28.24 |
| ramp1 | CRITICAL | 400 | 400.1 | 100.0 | 0.0 | 0 | 0 | 3.24 | 34.31 | 99.57 |
| ramp1 | STANDARD | 40 | 47.3 | 100.0 | 0.0 | 0 | 0 | 2.38 | 29.7 | 87.06 |
| ramp1 | LOW | 900 | 900.1 | 100.0 | 0.0 | 0 | 0 | 1.37 | 30.41 | 126.25 |
| ramp2 | CRITICAL | 600 | 488.1 | 99.67 | 0.0 | 24 | 0 | 393.8 | 1131.9 | 1423.78 |
| ramp2 | STANDARD | 60 | 67.6 | 99.41 | 0.0 | 4 | 2 | 413.49 | 1098.21 | 1342.13 |
| ramp2 | LOW | 1400 | 643.8 | 72.87 | 0.0 | 2620 | 0 | 635.26 | 2068.17 | 2772.18 |
| ramp3 | CRITICAL | 800 | 705.3 | 97.21 | 0.0 | 295 | 0 | 504.43 | 1721.27 | 2433.49 |
| ramp3 | STANDARD | 80 | 97.1 | 96.15 | 0.0 | 53 | 3 | 506.86 | 1607.98 | 2146.39 |
| ramp3 | LOW | 1900 | 746.7 | 37.15 | 0.0 | 7039 | 0 | 1719.46 | 2947.09 | 3428.24 |
| ramp4 | CRITICAL | 1000 | 286.7 | 91.56 | 0.0 | 363 | 0 | 1587.92 | 4183.73 | 4914.35 |
| ramp4 | STANDARD | 100 | 64.0 | 89.17 | 0.0 | 93 | 11 | 1503.39 | 4099.36 | 5023.52 |
| ramp4 | LOW | 2400 | 346.9 | 28.86 | 0.0 | 3702 | 0 | 2901.39 | 5250.65 | 6492.78 |
| peak | CRITICAL | 1000 | 456.6 | 83.35 | 0.0 | 2281 | 0 | 1215.68 | 4429.79 | 6354.29 |
| peak | STANDARD | 100 | 81.5 | 84.91 | 0.0 | 358 | 11 | 1231.84 | 4590.53 | 6196.35 |
| peak | LOW | 2400 | 448.1 | 34.85 | 0.0 | 8757 | 0 | 2099.6 | 5853.49 | 9122.72 |
| decline1 | CRITICAL | 800 | 121.2 | 66.17 | 0.0 | 820 | 0 | 2182.49 | 6725.39 | 10089.94 |
| decline1 | STANDARD | 80 | 23.1 | 67.39 | 0.0 | 151 | 0 | 1560.48 | 7139.69 | 9928.05 |
| decline1 | LOW | 1900 | 139.8 | 51.06 | 0.0 | 1368 | 0 | 3024.19 | 9663.82 | 12393.54 |
| decline2 | CRITICAL | 600 | 88.3 | 100.0 | 0.0 | 0 | 0 | 679.19 | 2911.48 | 5690.88 |
| decline2 | STANDARD | 60 | 22.6 | 99.78 | 0.0 | 0 | 1 | 410.98 | 2819.08 | 6346.8 |
| decline2 | LOW | 1400 | 138.8 | 100.0 | 0.0 | 0 | 0 | 1148.96 | 3717.0 | 4974.59 |
| decline3 | CRITICAL | 400 | 0.0 | None | None | 0 | 0 | None | None | None |
| decline3 | STANDARD | 40 | 1.8 | 100.0 | 0.0 | 0 | 0 | 666.74 | 3750.98 | 7087.35 |
| decline3 | LOW | 900 | 0.1 | 100.0 | 0.0 | 0 | 0 | 685.29 | 685.29 | 685.29 |
| tail | CRITICAL | 200 | 12.7 | 99.74 | 0.0 | 0 | 3 | 716.61 | 55999.61 | 57549.39 |
| tail | STANDARD | 20 | 3.2 | 100.0 | 0.0 | 0 | 0 | 533.65 | 35087.15 | 57392.97 |
| tail | LOW | 400 | 21.1 | 96.95 | 0.0 | 58 | 0 | 379.6 | 56786.06 | 57837.37 |

- k6 dropped iterations: 227086 {'decline1_CRITICAL': 5050, 'decline1_LOW': 15458, 'decline1_STANDARD': 358, 'decline2_CRITICAL': 8498, 'decline2_LOW': 11968, 'decline2_STANDARD': 769, 'decline3_STANDARD': 655, 'peak_CRITICAL': 16307, 'peak_LOW': 58538, 'peak_STANDARD': 862, 'ramp2_CRITICAL': 1685, 'ramp2_LOW': 11343, 'ramp2_STANDARD': 63, 'ramp3_CRITICAL': 3195, 'ramp3_LOW': 21469, 'ramp3_STANDARD': 150, 'ramp4_CRITICAL': 9592, 'ramp4_LOW': 30571, 'ramp4_STANDARD': 710, 'tail_CRITICAL': 9557, 'tail_LOW': 19456, 'tail_STANDARD': 832}
- outbox: peak backlog 3923.0 at t=331.2 s; backlog at peak end 896.0; zero after peak end: 217.39999999999998 s; backlog at end 0.0
- limiter: {'samples': 385, 'local_mode_samples': 222, 'circuit_opens_during_run': 74.0}
- admission menu Hikari pending (curve window): {'peak': 2319.0, 'mean': 163.42}
- CPU %, curve window: {'admission': {'mean': 97.5, 'peak': 209.8}, 'order': {'mean': 41.6, 'peak': 89.6}, 'postgres': {'mean': 62.6, 'peak': 114.0}}
- level seconds: {'ELEVATED': 26.0, 'HARD_CAP': 149.46, 'HIGH': 2.8, 'NORMAL': 197.65}
- level changes: [{'t_s': 0.2, 'level': 0}, {'t_s': 81.1, 'level': 3}, {'t_s': 132.9, 'level': 1}, {'t_s': 134.4, 'level': 3}, {'t_s': 164.8, 'level': 1}, {'t_s': 166.3, 'level': 3}, {'t_s': 167.8, 'level': 0}, {'t_s': 172.5, 'level': 1}, {'t_s': 178.0, 'level': 0}, {'t_s': 179.3, 'level': 3}, {'t_s': 180.7, 'level': 0}, {'t_s': 181.4, 'level': 1}, {'t_s': 183.6, 'level': 0}, {'t_s': 188.9, 'level': 2}, {'t_s': 189.8, 'level': 1}, {'t_s': 191.0, 'level': 3}, {'t_s': 191.6, 'level': 0}, {'t_s': 196.1, 'level': 3}, {'t_s': 197.0, 'level': 0}, {'t_s': 198.4, 'level': 3}, {'t_s': 200.5, 'level': 1}, {'t_s': 203.2, 'level': 0}, {'t_s': 234.9, 'level': 1}, {'t_s': 240.4, 'level': 0}, {'t_s': 251.7, 'level': 3}, {'t_s': 252.5, 'level': 0}, {'t_s': 254.1, 'level': 1}, {'t_s': 256.1, 'level': 0}, {'t_s': 259.1, 'level': 2}, {'t_s': 260.3, 'level': 1}, {'t_s': 263.4, 'level': 0}, {'t_s': 264.8, 'level': 1}, {'t_s': 266.2, 'level': 0}, {'t_s': 267.1, 'level': 1}, {'t_s': 267.9, 'level': 0}, {'t_s': 269.1, 'level': 3}, {'t_s': 327.3, 'level': 0}]
- created check (201s + seeds = orders in Postgres): {'crit_201': 55450, 'seed_orders': 400, 'expected_orders': 55850, 'db_orders': 55851, 'match': False}
- checks: {'orders': '55851', 'idempotency_keys': '55851', 'orders_without_key': '0', 'order_ids_on_two_keys': '0', 'duplicate_client_key': '0', 'order_outbox_unpublished_at_end': '0', 'dispatch_outbox_unpublished_at_end': '0', 'dispatch_pending_orders_at_end': '52681', 'dispatch_assignments_at_end': '0', 'inv_order_with_more_than_one_assignment': '0', 'inv_rider_with_more_than_one_active': '0', 'inv_busy_rider_without_one_active': '0', 'inv_active_assignment_rider_not_busy': '0', 'outbox_drain_s': '0'}

