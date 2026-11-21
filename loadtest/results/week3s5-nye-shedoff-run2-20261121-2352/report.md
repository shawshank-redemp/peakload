### week3s5-nye-shedoff-run2-20261121-2352
| Phase | Class | Target req/s | Achieved req/s | Accepted % | Shed (503) % | Other 503 | Other errors | p50 ms | p95 ms | p99 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| calm | CRITICAL | 200 | 200.0 | 100.0 | 0.0 | 0 | 0 | 1.44 | 3.79 | 7.58 |
| calm | STANDARD | 20 | 23.7 | 100.0 | 0.0 | 0 | 0 | 1.09 | 2.73 | 6.1 |
| calm | LOW | 400 | 400.0 | 100.0 | 0.0 | 0 | 0 | 0.59 | 1.72 | 4.36 |
| ramp1 | CRITICAL | 400 | 400.0 | 100.0 | 0.0 | 0 | 0 | 2.01 | 6.13 | 15.37 |
| ramp1 | STANDARD | 40 | 48.2 | 100.0 | 0.0 | 0 | 0 | 1.38 | 5.32 | 11.74 |
| ramp1 | LOW | 900 | 900.1 | 100.0 | 0.0 | 0 | 0 | 0.86 | 3.54 | 12.99 |
| ramp2 | CRITICAL | 600 | 540.2 | 100.0 | 0.0 | 0 | 0 | 89.6 | 949.05 | 1247.18 |
| ramp2 | STANDARD | 60 | 68.2 | 100.0 | 0.0 | 0 | 0 | 98.19 | 941.53 | 1248.2 |
| ramp2 | LOW | 1400 | 1009.1 | 92.84 | 0.0 | 1084 | 0 | 304.24 | 1841.43 | 2332.82 |
| ramp3 | CRITICAL | 800 | 675.5 | 100.0 | 0.0 | 0 | 0 | 326.33 | 1161.39 | 2089.46 |
| ramp3 | STANDARD | 80 | 89.8 | 99.7 | 0.0 | 0 | 4 | 304.33 | 1101.74 | 1948.97 |
| ramp3 | LOW | 1900 | 820.7 | 60.89 | 0.0 | 4815 | 0 | 751.73 | 2568.34 | 3118.9 |
| ramp4 | CRITICAL | 1000 | 668.6 | 97.26 | 0.0 | 275 | 0 | 517.04 | 1908.95 | 3150.18 |
| ramp4 | STANDARD | 100 | 98.9 | 96.63 | 0.0 | 46 | 4 | 589.34 | 1793.08 | 2642.82 |
| ramp4 | LOW | 2400 | 691.7 | 35.78 | 0.0 | 6663 | 0 | 1784.7 | 3015.33 | 3787.28 |
| peak | CRITICAL | 1000 | 405.9 | 89.16 | 0.0 | 1320 | 0 | 1327.93 | 6466.32 | 7219.19 |
| peak | STANDARD | 100 | 67.5 | 89.92 | 0.0 | 190 | 14 | 1477.46 | 6494.4 | 7118.87 |
| peak | LOW | 2400 | 383.1 | 37.83 | 0.0 | 7145 | 0 | 2406.54 | 7077.87 | 8874.49 |
| decline1 | CRITICAL | 800 | 151.8 | 98.32 | 0.0 | 51 | 0 | 1032.69 | 2345.8 | 3896.85 |
| decline1 | STANDARD | 80 | 26.6 | 98.69 | 0.0 | 4 | 3 | 977.29 | 2314.2 | 4216.56 |
| decline1 | LOW | 1900 | 114.0 | 50.18 | 0.0 | 1136 | 0 | 2179.61 | 2843.48 | 3133.3 |
| decline2 | CRITICAL | 600 | 178.9 | 100.0 | 0.0 | 0 | 0 | 627.63 | 1597.05 | 12740.89 |
| decline2 | STANDARD | 60 | 26.7 | 99.81 | 0.0 | 0 | 1 | 654.29 | 1367.08 | 2541.18 |
| decline2 | LOW | 1400 | 177.9 | 72.68 | 0.0 | 972 | 0 | 1351.11 | 9493.75 | 16999.09 |
| decline3 | CRITICAL | 400 | 0.0 | None | None | 0 | 0 | None | None | None |
| decline3 | STANDARD | 40 | 4.5 | 98.89 | 0.0 | 0 | 1 | 877.67 | 2766.87 | 2942.45 |
| decline3 | LOW | 900 | 0.0 | None | None | 0 | 0 | None | None | None |
| tail | CRITICAL | 200 | 77.0 | 100.0 | 0.0 | 0 | 0 | 126.88 | 816.73 | 2719.77 |
| tail | STANDARD | 20 | 11.7 | 99.91 | 0.0 | 0 | 1 | 164.32 | 1555.9 | 3285.29 |
| tail | LOW | 400 | 151.5 | 100.0 | 0.0 | 0 | 0 | 69.0 | 908.95 | 2404.15 |

- k6 dropped iterations: 225207 {'decline1_CRITICAL': 12876, 'decline1_LOW': 35676, 'decline1_STANDARD': 1140, 'decline2_CRITICAL': 2666, 'decline2_LOW': 10421, 'decline2_STANDARD': 716, 'decline3_STANDARD': 726, 'peak_CRITICAL': 22001, 'peak_LOW': 60302, 'peak_STANDARD': 1253, 'ramp2_CRITICAL': 897, 'ramp2_LOW': 5863, 'ramp2_STANDARD': 41, 'ramp3_CRITICAL': 1791, 'ramp3_LOW': 15264, 'ramp3_STANDARD': 75, 'ramp4_CRITICAL': 4972, 'ramp4_LOW': 25648, 'ramp4_STANDARD': 264, 'tail_CRITICAL': 7338, 'tail_LOW': 14018, 'tail_STANDARD': 1259}
- outbox: peak backlog 11919.0 at t=339.4 s; backlog at peak end 586.0; zero after peak end: 250.5 s; backlog at end 0.0
- limiter: {'samples': 185, 'local_mode_samples': 15, 'circuit_opens_during_run': 7.0}
- admission menu Hikari pending (curve window): {'peak': 1409.0, 'mean': 134.97}
- CPU %, curve window: {'admission': {'mean': 80.8, 'peak': 204.3}, 'order': {'mean': 39.8, 'peak': 104.7}, 'postgres': {'mean': 52.2, 'peak': 124.4}}
- level seconds: {'ELEVATED': 51.24, 'HARD_CAP': 95.7, 'HIGH': 13.69, 'NORMAL': 208.21}
- level changes: [{'t_s': 0.1, 'level': 0}, {'t_s': 81.1, 'level': 3}, {'t_s': 84.6, 'level': 0}, {'t_s': 85.3, 'level': 2}, {'t_s': 86.1, 'level': 3}, {'t_s': 87.9, 'level': 0}, {'t_s': 89.0, 'level': 3}]
- created check (201s + seeds = orders in Postgres): {'crit_201': 70344, 'seed_orders': 400, 'expected_orders': 70744, 'db_orders': 70744, 'match': True}
- checks: {'orders': '70744', 'idempotency_keys': '70744', 'orders_without_key': '0', 'order_ids_on_two_keys': '0', 'duplicate_client_key': '0', 'order_outbox_unpublished_at_end': '0', 'dispatch_outbox_unpublished_at_end': '0', 'dispatch_pending_orders_at_end': '66714', 'dispatch_assignments_at_end': '0', 'inv_order_with_more_than_one_assignment': '0', 'inv_rider_with_more_than_one_active': '0', 'inv_busy_rider_without_one_active': '0', 'inv_active_assignment_rider_not_busy': '0', 'outbox_drain_s': '4'}

