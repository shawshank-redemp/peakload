### week3s5-nye-shedon-run3-20261122-0014
| Phase | Class | Target req/s | Achieved req/s | Accepted % | Shed (503) % | Other 503 | Other errors | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| calm | CRITICAL | 200 | 200.0 | 100.0 | 0.0 | 0 | 0 | 1.3 | 2.44 | 5.13 |
| calm | STANDARD | 20 | 24.1 | 100.0 | 0.0 | 0 | 0 | 0.97 | 1.92 | 4.1 |
| calm | LOW | 400 | 400.1 | 100.0 | 0.0 | 0 | 0 | 0.53 | 1.14 | 3.13 |
| ramp1 | CRITICAL | 400 | 400.1 | 100.0 | 0.0 | 0 | 0 | 1.6 | 3.62 | 7.93 |
| ramp1 | STANDARD | 40 | 47.6 | 100.0 | 0.0 | 0 | 0 | 1.1 | 2.78 | 7.43 |
| ramp1 | LOW | 900 | 900.0 | 100.0 | 0.0 | 0 | 0 | 0.72 | 1.96 | 5.15 |
| ramp2 | CRITICAL | 600 | 600.0 | 98.87 | 1.13 | 0 | 0 | 2.32 | 14.79 | 89.81 |
| ramp2 | STANDARD | 60 | 71.4 | 98.69 | 1.31 | 0 | 0 | 1.71 | 10.7 | 49.68 |
| ramp2 | LOW | 1400 | 1400.3 | 97.57 | 2.23 | 41.0 | 0 | 1.1 | 8.96 | 27.64 |
| ramp3 | CRITICAL | 800 | 800.0 | 96.13 | 0.81 | 367.0 | 0 | 5.06 | 69.18 | 197.38 |
| ramp3 | STANDARD | 80 | 94.6 | 95.84 | 1.48 | 38.0 | 0 | 4.0 | 57.13 | 192.7 |
| ramp3 | LOW | 1900 | 1900.1 | 89.82 | 5.77 | 1255.0 | 0 | 2.76 | 37.0 | 93.85 |
| ramp4 | CRITICAL | 1000 | 957.2 | 87.57 | 7.68 | 681.0 | 0 | 17.01 | 200.87 | 490.9 |
| ramp4 | STANDARD | 100 | 115.5 | 85.98 | 9.81 | 72.0 | 1 | 13.17 | 233.12 | 496.71 |
| ramp4 | LOW | 2400 | 2321.3 | 68.45 | 23.28 | 2879.0 | 0 | 9.0 | 180.85 | 519.78 |
| peak | CRITICAL | 1000 | 927.6 | 65.48 | 32.6 | 533.0 | 0 | 109.44 | 581.18 | 745.77 |
| peak | STANDARD | 100 | 105.4 | 53.65 | 43.5 | 89.0 | 1 | 71.88 | 505.8 | 671.39 |
| peak | LOW | 2400 | 2156.6 | 27.48 | 69.3 | 2084.0 | 0 | 39.89 | 397.03 | 707.79 |
| decline1 | CRITICAL | 800 | 578.2 | 56.87 | 45.53 | 0 | 0 | 226.5 | 846.37 | 2008.15 |
| decline1 | STANDARD | 80 | 74.3 | 40.95 | 57.9 | 15.0 | 2 | 204.35 | 781.03 | 2123.63 |
| decline1 | LOW | 1900 | 1268.9 | 16.76 | 86.0 | 0 | 0 | 94.04 | 1047.27 | 1832.82 |
| decline2 | CRITICAL | 600 | 594.9 | 90.58 | 18.36 | 0 | 0 | 22.89 | 295.64 | 487.08 |
| decline2 | STANDARD | 60 | 67.2 | 87.14 | 25.87 | 0 | 1 | 17.16 | 202.99 | 379.39 |
| decline2 | LOW | 1400 | 1341.8 | 71.96 | 43.04 | 0 | 0 | 10.58 | 130.22 | 282.88 |
| decline3 | CRITICAL | 400 | 400.0 | 98.03 | 4.99 | 0 | 0 | 5.06 | 110.9 | 363.46 |
| decline3 | STANDARD | 40 | 44.9 | 96.88 | 7.35 | 0 | 0 | 3.82 | 77.08 | 334.83 |
| decline3 | LOW | 900 | 896.9 | 92.47 | 16.08 | 0 | 0 | 2.33 | 49.69 | 101.75 |
| tail | CRITICAL | 200 | 200.0 | 100.0 | 0.0 | 0 | 0 | 1.39 | 4.71 | 14.29 |
| tail | STANDARD | 20 | 22.1 | 100.0 | 0.0 | 0 | 0 | 0.98 | 3.87 | 14.77 |
| tail | LOW | 400 | 400.0 | 99.96 | 0.04 | 0 | 0 | 0.57 | 2.2 | 8.61 |

- k6 dropped iterations: 29423 {'decline1_CRITICAL': 4263, 'decline1_LOW': 12215, 'decline1_STANDARD': 206, 'decline2_CRITICAL': 104, 'decline2_LOW': 1164, 'decline3_LOW': 69, 'peak_CRITICAL': 2170, 'peak_LOW': 7300, 'peak_STANDARD': 63, 'ramp4_CRITICAL': 636, 'ramp4_LOW': 1201, 'ramp4_STANDARD': 32}
- outbox: peak backlog 1278.0 at t=131.4 s; backlog at peak end 403.0; zero after peak end: 153.8 s; backlog at end 0.0
- limiter: {'samples': 533, 'local_mode_samples': 46, 'circuit_opens_during_run': 17.0}
- admission menu Hikari pending (curve window): {'peak': 74.0, 'mean': 2.92}
- CPU %, curve window: {'admission': {'mean': 69.9, 'peak': 181.5}, 'order': {'mean': 34.2, 'peak': 100.2}, 'postgres': {'mean': 51.8, 'peak': 162.0}}
- level seconds: {'ELEVATED': 15.62, 'HARD_CAP': 20.37, 'HIGH': 8.82, 'NORMAL': 302.3}
- level changes: [{'t_s': 0.1, 'level': 0}, {'t_s': 80.1, 'level': 3}, {'t_s': 80.7, 'level': 0}, {'t_s': 94.8, 'level': 1}, {'t_s': 95.5, 'level': 0}, {'t_s': 101.5, 'level': 2}, {'t_s': 102.1, 'level': 0}, {'t_s': 105.5, 'level': 3}, {'t_s': 106.1, 'level': 0}, {'t_s': 108.8, 'level': 2}, {'t_s': 109.4, 'level': 0}, {'t_s': 110.6, 'level': 1}, {'t_s': 111.2, 'level': 0}, {'t_s': 114.7, 'level': 3}, {'t_s': 115.3, 'level': 0}, {'t_s': 120.5, 'level': 2}, {'t_s': 121.1, 'level': 0}, {'t_s': 122.6, 'level': 1}, {'t_s': 123.3, 'level': 2}, {'t_s': 124.3, 'level': 3}, {'t_s': 125.0, 'level': 2}, {'t_s': 126.8, 'level': 3}, {'t_s': 127.4, 'level': 1}, {'t_s': 128.0, 'level': 0}, {'t_s': 128.6, 'level': 2}, {'t_s': 129.4, 'level': 3}, {'t_s': 130.0, 'level': 2}, {'t_s': 130.6, 'level': 0}, {'t_s': 133.2, 'level': 1}, {'t_s': 134.1, 'level': 0}, {'t_s': 135.0, 'level': 1}, {'t_s': 135.6, 'level': 0}, {'t_s': 137.5, 'level': 3}, {'t_s': 138.1, 'level': 1}, {'t_s': 139.8, 'level': 3}, {'t_s': 140.4, 'level': 2}, {'t_s': 141.0, 'level': 0}, {'t_s': 143.9, 'level': 2}, {'t_s': 144.6, 'level': 0}, {'t_s': 145.3, 'level': 1}, {'t_s': 147.3, 'level': 3}, {'t_s': 148.3, 'level': 2}, {'t_s': 149.0, 'level': 3}, {'t_s': 149.5, 'level': 1}, {'t_s': 150.2, 'level': 0}, {'t_s': 152.6, 'level': 3}, {'t_s': 155.4, 'level': 1}, {'t_s': 157.0, 'level': 3}, {'t_s': 157.8, 'level': 0}, {'t_s': 158.5, 'level': 2}, {'t_s': 159.1, 'level': 1}, {'t_s': 159.8, 'level': 0}, {'t_s': 161.6, 'level': 3}, {'t_s': 163.8, 'level': 0}, {'t_s': 164.5, 'level': 3}, {'t_s': 165.2, 'level': 0}, {'t_s': 167.6, 'level': 3}, {'t_s': 168.4, 'level': 0}, {'t_s': 169.6, 'level': 3}, {'t_s': 172.2, 'level': 1}, {'t_s': 173.8, 'level': 3}, {'t_s': 174.5, 'level': 0}, {'t_s': 175.9, 'level': 1}, {'t_s': 177.1, 'level': 0}, {'t_s': 189.8, 'level': 1}, {'t_s': 190.4, 'level': 3}, {'t_s': 191.1, 'level': 0}]
- created check (201s + seeds = orders in Postgres): {'crit_201': 112426, 'seed_orders': 400, 'expected_orders': 112826, 'db_orders': 112826, 'match': True}
- checks: {'orders': '112826', 'idempotency_keys': '112826', 'orders_without_key': '0', 'order_ids_on_two_keys': '0', 'duplicate_client_key': '0', 'order_outbox_unpublished_at_end': '0', 'dispatch_outbox_unpublished_at_end': '0', 'dispatch_pending_orders_at_end': '112826', 'dispatch_assignments_at_end': '0', 'inv_order_with_more_than_one_assignment': '0', 'inv_rider_with_more_than_one_active': '0', 'inv_busy_rider_without_one_active': '0', 'inv_active_assignment_rider_not_busy': '0', 'outbox_drain_s': '0'}

