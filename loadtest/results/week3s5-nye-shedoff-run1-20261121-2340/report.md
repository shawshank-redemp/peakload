### week3s5-nye-shedoff-run1-20261121-2340
| Phase | Class | Target req/s | Achieved req/s | Accepted % | Shed (503) % | Other 503 | Other errors | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| calm | CRITICAL | 200 | 200.0 | 100.0 | 0.0 | 0 | 0 | 1.13 | 2.14 | 5.73 |
| calm | STANDARD | 20 | 23.7 | 100.0 | 0.0 | 0 | 0 | 0.83 | 1.99 | 4.57 |
| calm | LOW | 400 | 400.0 | 100.0 | 0.0 | 0 | 0 | 0.47 | 0.97 | 3.41 |
| ramp1 | CRITICAL | 400 | 400.1 | 100.0 | 0.0 | 0 | 0 | 1.23 | 2.15 | 4.16 |
| ramp1 | STANDARD | 40 | 47.3 | 100.0 | 0.0 | 0 | 0 | 0.88 | 1.8 | 5.45 |
| ramp1 | LOW | 900 | 900.1 | 100.0 | 0.0 | 0 | 0 | 0.56 | 1.03 | 2.98 |
| ramp2 | CRITICAL | 600 | 600.0 | 100.0 | 0.0 | 0 | 0 | 1.42 | 2.69 | 5.21 |
| ramp2 | STANDARD | 60 | 72.3 | 100.0 | 0.0 | 0 | 0 | 1.01 | 2.19 | 4.73 |
| ramp2 | LOW | 1400 | 1400.1 | 100.0 | 0.0 | 0 | 0 | 0.64 | 1.41 | 3.56 |
| ramp3 | CRITICAL | 800 | 800.1 | 100.0 | 0.0 | 0 | 0 | 1.71 | 6.43 | 23.08 |
| ramp3 | STANDARD | 80 | 94.6 | 100.0 | 0.0 | 0 | 0 | 1.22 | 5.29 | 19.11 |
| ramp3 | LOW | 1900 | 1900.1 | 100.0 | 0.0 | 0 | 0 | 0.8 | 4.37 | 33.33 |
| ramp4 | CRITICAL | 1000 | 1000.1 | 100.0 | 0.0 | 0 | 0 | 2.45 | 14.4 | 40.36 |
| ramp4 | STANDARD | 100 | 118.5 | 100.0 | 0.0 | 0 | 0 | 1.85 | 12.57 | 43.53 |
| ramp4 | LOW | 2400 | 2400.1 | 100.0 | 0.0 | 0 | 0 | 1.29 | 16.29 | 95.71 |
| peak | CRITICAL | 1000 | 963.7 | 99.04 | 0.0 | 277 | 0 | 4.1 | 700.96 | 1248.99 |
| peak | STANDARD | 100 | 112.8 | 98.76 | 0.0 | 40 | 2 | 3.17 | 721.87 | 1398.44 |
| peak | LOW | 2400 | 2017.7 | 93.79 | 0.0 | 3759 | 0 | 1.74 | 211.58 | 2042.2 |
| decline1 | CRITICAL | 800 | 761.9 | 99.73 | 0.0 | 41 | 0 | 52.18 | 586.69 | 1066.83 |
| decline1 | STANDARD | 80 | 87.5 | 99.66 | 0.0 | 5 | 1 | 48.64 | 657.69 | 1141.69 |
| decline1 | LOW | 1900 | 1458.7 | 94.69 | 0.0 | 1548 | 0 | 178.88 | 1664.39 | 2117.79 |
| decline2 | CRITICAL | 600 | 600.0 | 100.0 | 0.0 | 0 | 0 | 2.57 | 63.3 | 162.65 |
| decline2 | STANDARD | 60 | 65.7 | 100.0 | 0.0 | 0 | 0 | 1.8 | 49.61 | 151.39 |
| decline2 | LOW | 1400 | 1400.3 | 100.0 | 0.0 | 0 | 0 | 1.1 | 140.32 | 412.65 |
| decline3 | CRITICAL | 400 | 400.1 | 100.0 | 0.0 | 0 | 0 | 2.93 | 22.65 | 79.31 |
| decline3 | STANDARD | 40 | 42.9 | 100.0 | 0.0 | 0 | 0 | 2.08 | 14.66 | 71.35 |
| decline3 | LOW | 900 | 900.0 | 100.0 | 0.0 | 0 | 0 | 1.23 | 15.27 | 64.28 |
| tail | CRITICAL | 200 | 200.0 | 100.0 | 0.0 | 0 | 0 | 1.35 | 3.43 | 14.14 |
| tail | STANDARD | 20 | 20.9 | 100.0 | 0.0 | 0 | 0 | 0.93 | 2.29 | 9.72 |
| tail | LOW | 400 | 400.0 | 100.0 | 0.0 | 0 | 0 | 0.57 | 1.49 | 8.84 |

- k6 dropped iterations: 21932 {'decline1_CRITICAL': 763, 'decline1_LOW': 8833, 'decline1_STANDARD': 24, 'peak_CRITICAL': 1087, 'peak_LOW': 11173, 'peak_STANDARD': 52}
- outbox: peak backlog 1019.0 at t=139.6 s; backlog at peak end 594.0; zero after peak end: 4.199999999999989 s; backlog at end 0.0
- limiter: {'samples': 564, 'local_mode_samples': 28, 'circuit_opens_during_run': 9.0}
- admission menu Hikari pending (curve window): {'peak': 2096.0, 'mean': 58.62}
- CPU %, curve window: {'admission': {'mean': 68.9, 'peak': 208.6}, 'order': {'mean': 32.3, 'peak': 99.3}, 'postgres': {'mean': 49.4, 'peak': 122.6}}
- level seconds: {'ELEVATED': 0.52, 'HARD_CAP': 23.09, 'HIGH': 0.54, 'NORMAL': 322.27}
- level changes: [{'t_s': 0.1, 'level': 0}, {'t_s': 43.8, 'level': 2}, {'t_s': 44.3, 'level': 0}, {'t_s': 124.9, 'level': 3}, {'t_s': 125.5, 'level': 0}, {'t_s': 126.1, 'level': 3}, {'t_s': 126.6, 'level': 0}, {'t_s': 145.2, 'level': 3}, {'t_s': 159.6, 'level': 0}, {'t_s': 164.9, 'level': 3}, {'t_s': 171.3, 'level': 0}, {'t_s': 172.4, 'level': 3}, {'t_s': 172.9, 'level': 0}, {'t_s': 175.1, 'level': 3}, {'t_s': 175.7, 'level': 0}, {'t_s': 189.4, 'level': 3}, {'t_s': 190.0, 'level': 0}]
- created check (201s + seeds = orders in Postgres): {'crit_201': 135837, 'seed_orders': 400, 'expected_orders': 136237, 'db_orders': 136237, 'match': True}
- checks: {'orders': '136237', 'idempotency_keys': '136237', 'orders_without_key': '0', 'order_ids_on_two_keys': '0', 'duplicate_client_key': '0', 'order_outbox_unpublished_at_end': '0', 'dispatch_outbox_unpublished_at_end': '0', 'dispatch_pending_orders_at_end': '136237', 'dispatch_assignments_at_end': '0', 'inv_order_with_more_than_one_assignment': '0', 'inv_rider_with_more_than_one_active': '0', 'inv_busy_rider_without_one_active': '0', 'inv_active_assignment_rider_not_busy': '0', 'outbox_drain_s': '0'}

