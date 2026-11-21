### week3s5-nye-shedon-run1-20261121-2346
| Phase | Class | Target req/s | Achieved req/s | Accepted % | Shed (503) % | Other 503 | Other errors | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| calm | CRITICAL | 200 | 200.0 | 100.0 | 0.0 | 0 | 0 | 1.49 | 3.72 | 12.85 |
| calm | STANDARD | 20 | 23.8 | 100.0 | 0.0 | 0 | 0 | 1.1 | 3.17 | 9.42 |
| calm | LOW | 400 | 400.0 | 100.0 | 0.0 | 0 | 0 | 0.6 | 1.71 | 6.7 |
| ramp1 | CRITICAL | 400 | 400.1 | 100.0 | 0.0 | 0 | 0 | 1.44 | 2.56 | 4.28 |
| ramp1 | STANDARD | 40 | 48.1 | 100.0 | 0.0 | 0 | 0 | 1.06 | 2.22 | 3.33 |
| ramp1 | LOW | 900 | 900.1 | 100.0 | 0.0 | 0 | 0 | 0.64 | 1.17 | 2.58 |
| ramp2 | CRITICAL | 600 | 600.0 | 100.0 | 0.0 | 0 | 0 | 2.02 | 5.73 | 11.33 |
| ramp2 | STANDARD | 60 | 70.8 | 100.0 | 0.0 | 0 | 0 | 1.39 | 4.7 | 10.25 |
| ramp2 | LOW | 1400 | 1400.1 | 100.0 | 0.0 | 0 | 0 | 0.85 | 3.24 | 8.49 |
| ramp3 | CRITICAL | 800 | 775.9 | 92.99 | 6.96 | 6.0 | 0 | 4.54 | 72.45 | 177.69 |
| ramp3 | STANDARD | 80 | 92.0 | 91.67 | 7.9 | 6.0 | 0 | 3.45 | 50.92 | 168.53 |
| ramp3 | LOW | 1900 | 1837.5 | 86.79 | 11.71 | 413.0 | 0 | 2.13 | 35.15 | 109.58 |
| ramp4 | CRITICAL | 1000 | 865.9 | 74.35 | 16.65 | 1169.0 | 0 | 29.17 | 480.92 | 842.83 |
| ramp4 | STANDARD | 100 | 100.2 | 65.87 | 24.28 | 148.0 | 0 | 19.04 | 479.28 | 893.29 |
| ramp4 | LOW | 2400 | 2005.3 | 50.47 | 34.46 | 4534.0 | 0 | 11.73 | 202.36 | 916.8 |
| peak | CRITICAL | 1000 | 1000.0 | 96.43 | 7.38 | 0 | 0 | 7.74 | 57.72 | 116.9 |
| peak | STANDARD | 100 | 115.5 | 94.57 | 9.35 | 0 | 1 | 5.88 | 39.12 | 75.83 |
| peak | LOW | 2400 | 2400.1 | 86.88 | 18.17 | 0 | 0 | 4.34 | 39.24 | 83.58 |
| decline1 | CRITICAL | 800 | 797.0 | 87.76 | 12.23 | 1.0 | 0 | 17.33 | 171.43 | 302.91 |
| decline1 | STANDARD | 80 | 89.7 | 85.28 | 14.61 | 2.0 | 0 | 12.09 | 144.47 | 258.53 |
| decline1 | LOW | 1900 | 1900.0 | 71.76 | 28.88 | 0 | 0 | 7.54 | 108.93 | 251.14 |
| decline2 | CRITICAL | 600 | 529.4 | 89.77 | 8.43 | 190.0 | 0 | 43.49 | 637.26 | 1157.24 |
| decline2 | STANDARD | 60 | 60.4 | 82.02 | 15.91 | 25.0 | 0 | 30.11 | 731.15 | 1014.18 |
| decline2 | LOW | 1400 | 1209.8 | 60.0 | 33.83 | 1492.0 | 0 | 13.62 | 245.53 | 494.1 |
| decline3 | CRITICAL | 400 | 400.0 | 100.0 | 2.8 | 0 | 0 | 2.91 | 26.71 | 71.57 |
| decline3 | STANDARD | 40 | 43.4 | 100.0 | 5.07 | 0 | 0 | 2.17 | 23.27 | 54.74 |
| decline3 | LOW | 900 | 900.1 | 99.27 | 14.55 | 0 | 0 | 1.19 | 22.03 | 65.98 |
| tail | CRITICAL | 200 | 200.0 | 100.0 | 0.0 | 0 | 0 | 1.63 | 5.3 | 51.21 |
| tail | STANDARD | 20 | 21.4 | 100.0 | 0.0 | 0 | 0 | 1.16 | 3.82 | 46.63 |
| tail | LOW | 400 | 400.0 | 99.86 | 0.35 | 0 | 0 | 0.67 | 2.28 | 35.0 |

- k6 dropped iterations: 14781 {'decline1_CRITICAL': 65, 'decline2_CRITICAL': 1413, 'decline2_LOW': 3812, 'decline2_STANDARD': 90, 'peak_LOW': 1, 'ramp3_CRITICAL': 362, 'ramp3_LOW': 938, 'ramp3_STANDARD': 13, 'ramp4_CRITICAL': 2013, 'ramp4_LOW': 5922, 'ramp4_STANDARD': 152}
- outbox: peak backlog 1187.0 at t=117.1 s; backlog at peak end 1104.0; zero after peak end: 155.2 s; backlog at end 0.0
- limiter: {'samples': 554, 'local_mode_samples': 24, 'circuit_opens_during_run': 9.0}
- admission menu Hikari pending (curve window): {'peak': 69.0, 'mean': 2.13}
- CPU %, curve window: {'admission': {'mean': 70.9, 'peak': 170.5}, 'order': {'mean': 35.7, 'peak': 92.3}, 'postgres': {'mean': 54.5, 'peak': 153.7}}
- level seconds: {'ELEVATED': 9.26, 'HARD_CAP': 9.07, 'HIGH': 4.71, 'NORMAL': 325.11}
- level changes: [{'t_s': 0.1, 'level': 0}, {'t_s': 95.3, 'level': 3}, {'t_s': 95.9, 'level': 0}, {'t_s': 101.1, 'level': 1}, {'t_s': 101.7, 'level': 0}, {'t_s': 114.3, 'level': 1}, {'t_s': 115.2, 'level': 3}, {'t_s': 116.8, 'level': 2}, {'t_s': 117.4, 'level': 1}, {'t_s': 118.0, 'level': 0}, {'t_s': 121.6, 'level': 3}, {'t_s': 122.7, 'level': 1}, {'t_s': 123.3, 'level': 0}, {'t_s': 125.0, 'level': 3}, {'t_s': 125.6, 'level': 0}, {'t_s': 126.7, 'level': 1}, {'t_s': 127.9, 'level': 0}, {'t_s': 133.8, 'level': 3}, {'t_s': 134.4, 'level': 0}, {'t_s': 134.9, 'level': 1}, {'t_s': 135.5, 'level': 0}, {'t_s': 138.2, 'level': 1}, {'t_s': 138.8, 'level': 0}, {'t_s': 151.0, 'level': 1}, {'t_s': 151.6, 'level': 0}, {'t_s': 157.9, 'level': 1}, {'t_s': 158.6, 'level': 3}, {'t_s': 159.2, 'level': 1}, {'t_s': 159.8, 'level': 0}, {'t_s': 161.7, 'level': 3}, {'t_s': 162.3, 'level': 0}, {'t_s': 163.4, 'level': 3}, {'t_s': 164.1, 'level': 0}, {'t_s': 167.0, 'level': 2}, {'t_s': 167.5, 'level': 3}, {'t_s': 169.3, 'level': 2}, {'t_s': 170.0, 'level': 0}, {'t_s': 173.5, 'level': 1}, {'t_s': 174.0, 'level': 0}, {'t_s': 175.4, 'level': 1}, {'t_s': 176.0, 'level': 0}, {'t_s': 179.6, 'level': 1}, {'t_s': 180.2, 'level': 0}, {'t_s': 181.6, 'level': 3}, {'t_s': 182.5, 'level': 1}, {'t_s': 183.1, 'level': 3}, {'t_s': 184.6, 'level': 2}, {'t_s': 185.6, 'level': 3}, {'t_s': 186.2, 'level': 0}, {'t_s': 187.5, 'level': 3}, {'t_s': 188.3, 'level': 1}, {'t_s': 188.8, 'level': 0}, {'t_s': 190.3, 'level': 1}, {'t_s': 191.3, 'level': 0}, {'t_s': 194.0, 'level': 1}, {'t_s': 194.5, 'level': 0}, {'t_s': 195.6, 'level': 1}, {'t_s': 196.2, 'level': 0}]
- created check (201s + seeds = orders in Postgres): {'crit_201': 125904, 'seed_orders': 400, 'expected_orders': 126304, 'db_orders': 126304, 'match': True}
- checks: {'orders': '126304', 'idempotency_keys': '126304', 'orders_without_key': '0', 'order_ids_on_two_keys': '0', 'duplicate_client_key': '0', 'order_outbox_unpublished_at_end': '0', 'dispatch_outbox_unpublished_at_end': '0', 'dispatch_pending_orders_at_end': '126304', 'dispatch_assignments_at_end': '0', 'inv_order_with_more_than_one_assignment': '0', 'inv_rider_with_more_than_one_active': '0', 'inv_busy_rider_without_one_active': '0', 'inv_active_assignment_rider_not_busy': '0', 'outbox_drain_s': '0'}

