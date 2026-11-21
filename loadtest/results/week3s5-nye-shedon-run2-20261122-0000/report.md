### week3s5-nye-shedon-run2-20261122-0000
| Phase | Class | Target req/s | Achieved req/s | Accepted % | Shed (503) % | Other 503 | Other errors | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| calm | CRITICAL | 200 | 200.0 | 100.0 | 0.0 | 0 | 0 | 2.18 | 24.63 | 162.62 |
| calm | STANDARD | 20 | 23.9 | 100.0 | 0.0 | 0 | 0 | 1.54 | 16.49 | 78.85 |
| calm | LOW | 400 | 400.0 | 99.48 | 0.52 | 0 | 0 | 0.92 | 11.03 | 79.25 |
| ramp1 | CRITICAL | 400 | 400.1 | 100.0 | 0.0 | 0 | 0 | 3.52 | 56.14 | 123.22 |
| ramp1 | STANDARD | 40 | 48.5 | 99.86 | 0.14 | 0 | 0 | 2.69 | 48.26 | 115.01 |
| ramp1 | LOW | 900 | 900.1 | 97.59 | 2.41 | 0 | 0 | 1.58 | 46.21 | 99.79 |
| ramp2 | CRITICAL | 600 | 590.7 | 89.45 | 10.15 | 36.0 | 0 | 106.13 | 466.41 | 691.11 |
| ramp2 | STANDARD | 60 | 68.5 | 75.88 | 22.18 | 20.0 | 0 | 70.46 | 357.54 | 521.02 |
| ramp2 | LOW | 1400 | 1356.1 | 43.89 | 49.71 | 1301.0 | 0 | 47.92 | 347.75 | 626.25 |
| ramp3 | CRITICAL | 800 | 599.8 | 64.41 | 27.86 | 695.0 | 0 | 184.68 | 798.71 | 979.96 |
| ramp3 | STANDARD | 80 | 78.1 | 48.59 | 41.33 | 118.0 | 0 | 146.9 | 730.97 | 883.94 |
| ramp3 | LOW | 1900 | 1329.0 | 25.24 | 64.32 | 2081.0 | 0 | 89.59 | 661.66 | 1014.76 |
| ramp4 | CRITICAL | 1000 | 667.0 | 49.8 | 45.35 | 486.0 | 0 | 387.44 | 1137.24 | 1371.87 |
| ramp4 | STANDARD | 100 | 90.7 | 38.94 | 53.27 | 106.0 | 0 | 352.26 | 1049.84 | 1197.42 |
| ramp4 | LOW | 2400 | 1403.7 | 15.1 | 78.02 | 1449.0 | 0 | 359.13 | 947.64 | 1282.61 |
| peak | CRITICAL | 1000 | 751.3 | 57.57 | 46.42 | 0 | 0 | 260.77 | 949.59 | 1643.1 |
| peak | STANDARD | 100 | 94.6 | 39.96 | 62.26 | 0 | 1 | 230.13 | 909.02 | 1702.74 |
| peak | LOW | 2400 | 1652.8 | 14.52 | 88.14 | 0 | 0 | 174.7 | 652.41 | 978.71 |
| decline1 | CRITICAL | 800 | 768.4 | 80.44 | 17.48 | 320.0 | 0 | 78.47 | 517.25 | 752.75 |
| decline1 | STANDARD | 80 | 87.5 | 74.9 | 34.36 | 0 | 0 | 48.32 | 465.42 | 681.21 |
| decline1 | LOW | 1900 | 1740.5 | 49.16 | 53.48 | 0 | 0 | 25.83 | 388.52 | 754.05 |
| decline2 | CRITICAL | 600 | 500.2 | 80.71 | 19.65 | 0 | 0 | 25.28 | 438.08 | 644.9 |
| decline2 | STANDARD | 60 | 58.6 | 77.75 | 15.69 | 76.0 | 1 | 16.87 | 420.75 | 594.47 |
| decline2 | LOW | 1400 | 1126.0 | 64.65 | 35.59 | 0 | 0 | 9.99 | 249.68 | 587.14 |
| decline3 | CRITICAL | 400 | 380.9 | 95.93 | 11.09 | 0 | 0 | 20.0 | 422.55 | 708.31 |
| decline3 | STANDARD | 40 | 46.0 | 92.83 | 15.0 | 0 | 0 | 14.43 | 315.11 | 793.43 |
| decline3 | LOW | 900 | 857.0 | 83.9 | 25.26 | 0 | 0 | 9.04 | 152.17 | 565.06 |
| tail | CRITICAL | 200 | 197.6 | 99.68 | 0.69 | 0 | 0 | 4.82 | 114.55 | 412.74 |
| tail | STANDARD | 20 | 22.7 | 99.46 | 1.62 | 0 | 0 | 3.35 | 95.89 | 366.92 |
| tail | LOW | 400 | 396.0 | 97.59 | 5.14 | 0 | 0 | 1.85 | 70.49 | 176.09 |

- k6 dropped iterations: 83438 {'decline1_CRITICAL': 3045, 'decline1_LOW': 8923, 'decline1_STANDARD': 66, 'decline2_CRITICAL': 2019, 'decline2_LOW': 5574, 'decline2_STANDARD': 183, 'decline3_CRITICAL': 373, 'decline3_LOW': 861, 'decline3_STANDARD': 18, 'peak_CRITICAL': 7380, 'peak_LOW': 21576, 'peak_STANDARD': 376, 'ramp2_CRITICAL': 140, 'ramp2_LOW': 661, 'ramp3_CRITICAL': 2888, 'ramp3_LOW': 8560, 'ramp3_STANDARD': 130, 'ramp4_CRITICAL': 4900, 'ramp4_LOW': 14945, 'ramp4_STANDARD': 230, 'tail_CRITICAL': 221, 'tail_LOW': 357, 'tail_STANDARD': 12}
- outbox: peak backlog 895.0 at t=124.2 s; backlog at peak end 827.0; zero after peak end: 26.900000000000006 s; backlog at end 0.0
- limiter: {'samples': 470, 'local_mode_samples': 110, 'circuit_opens_during_run': 39.0}
- admission menu Hikari pending (curve window): {'peak': 76.0, 'mean': 5.53}
- CPU %, curve window: {'admission': {'mean': 86.3, 'peak': 302.6}, 'order': {'mean': 42.0, 'peak': 92.3}, 'postgres': {'mean': 61.7, 'peak': 148.2}}
- level seconds: {'ELEVATED': 26.38, 'HARD_CAP': 29.73, 'HIGH': 14.65, 'NORMAL': 277.06}
- level changes: [{'t_s': 0.1, 'level': 0}, {'t_s': 63.0, 'level': 1}, {'t_s': 63.6, 'level': 2}, {'t_s': 64.2, 'level': 0}, {'t_s': 78.7, 'level': 2}, {'t_s': 79.3, 'level': 0}, {'t_s': 79.9, 'level': 1}, {'t_s': 80.5, 'level': 0}, {'t_s': 82.3, 'level': 3}, {'t_s': 84.4, 'level': 1}, {'t_s': 85.0, 'level': 0}, {'t_s': 85.6, 'level': 2}, {'t_s': 86.3, 'level': 0}, {'t_s': 87.0, 'level': 1}, {'t_s': 88.5, 'level': 2}, {'t_s': 89.2, 'level': 1}, {'t_s': 89.8, 'level': 2}, {'t_s': 90.4, 'level': 0}, {'t_s': 92.5, 'level': 1}, {'t_s': 93.1, 'level': 0}, {'t_s': 93.7, 'level': 1}, {'t_s': 94.4, 'level': 2}, {'t_s': 95.1, 'level': 1}, {'t_s': 98.2, 'level': 3}, {'t_s': 98.9, 'level': 0}, {'t_s': 101.1, 'level': 3}, {'t_s': 101.7, 'level': 0}, {'t_s': 104.2, 'level': 2}, {'t_s': 104.9, 'level': 1}, {'t_s': 108.6, 'level': 0}, {'t_s': 110.6, 'level': 1}, {'t_s': 113.7, 'level': 0}, {'t_s': 114.5, 'level': 3}, {'t_s': 119.4, 'level': 2}, {'t_s': 121.1, 'level': 3}, {'t_s': 125.1, 'level': 0}, {'t_s': 128.9, 'level': 3}, {'t_s': 129.6, 'level': 0}, {'t_s': 131.7, 'level': 3}, {'t_s': 132.3, 'level': 0}, {'t_s': 133.4, 'level': 3}, {'t_s': 134.3, 'level': 0}, {'t_s': 135.0, 'level': 2}, {'t_s': 136.6, 'level': 0}, {'t_s': 137.2, 'level': 1}, {'t_s': 137.8, 'level': 0}, {'t_s': 138.4, 'level': 1}, {'t_s': 139.0, 'level': 0}, {'t_s': 139.7, 'level': 3}, {'t_s': 140.3, 'level': 0}, {'t_s': 141.1, 'level': 3}, {'t_s': 141.9, 'level': 1}, {'t_s': 142.5, 'level': 3}, {'t_s': 143.2, 'level': 2}, {'t_s': 144.5, 'level': 3}, {'t_s': 145.2, 'level': 2}, {'t_s': 146.0, 'level': 3}, {'t_s': 147.7, 'level': 2}, {'t_s': 149.9, 'level': 0}, {'t_s': 151.1, 'level': 1}, {'t_s': 152.1, 'level': 2}, {'t_s': 152.9, 'level': 1}, {'t_s': 154.9, 'level': 2}, {'t_s': 155.6, 'level': 0}, {'t_s': 156.6, 'level': 1}, {'t_s': 157.3, 'level': 2}, {'t_s': 158.0, 'level': 0}, {'t_s': 158.6, 'level': 3}, {'t_s': 162.0, 'level': 2}, {'t_s': 162.7, 'level': 0}, {'t_s': 163.8, 'level': 3}, {'t_s': 164.3, 'level': 1}, {'t_s': 164.9, 'level': 0}, {'t_s': 165.5, 'level': 1}, {'t_s': 166.2, 'level': 3}, {'t_s': 166.8, 'level': 0}, {'t_s': 172.2, 'level': 3}, {'t_s': 172.8, 'level': 0}, {'t_s': 174.1, 'level': 3}, {'t_s': 177.7, 'level': 1}, {'t_s': 178.3, 'level': 0}, {'t_s': 184.2, 'level': 1}, {'t_s': 184.8, 'level': 0}, {'t_s': 189.5, 'level': 2}, {'t_s': 190.1, 'level': 0}, {'t_s': 191.0, 'level': 2}, {'t_s': 191.9, 'level': 3}, {'t_s': 193.9, 'level': 0}, {'t_s': 208.9, 'level': 1}, {'t_s': 209.5, 'level': 0}, {'t_s': 210.2, 'level': 1}, {'t_s': 210.8, 'level': 0}, {'t_s': 214.0, 'level': 1}, {'t_s': 214.9, 'level': 0}, {'t_s': 217.1, 'level': 1}, {'t_s': 217.8, 'level': 0}, {'t_s': 220.2, 'level': 1}, {'t_s': 220.8, 'level': 0}]
- created check (201s + seeds = orders in Postgres): {'crit_201': 95147, 'seed_orders': 400, 'expected_orders': 95547, 'db_orders': 95547, 'match': True}
- checks: {'orders': '95547', 'idempotency_keys': '95547', 'orders_without_key': '0', 'order_ids_on_two_keys': '0', 'duplicate_client_key': '0', 'order_outbox_unpublished_at_end': '0', 'dispatch_outbox_unpublished_at_end': '0', 'dispatch_pending_orders_at_end': '95547', 'dispatch_assignments_at_end': '0', 'inv_order_with_more_than_one_assignment': '0', 'inv_rider_with_more_than_one_active': '0', 'inv_busy_rider_without_one_active': '0', 'inv_active_assignment_rider_not_busy': '0', 'outbox_drain_s': '0'}

