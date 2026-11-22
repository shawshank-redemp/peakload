# Week 4 status

Branch `week4-db`. Nothing pushed. `main` untouched. All work is in local commits.

## Commits on `week4-db` (since `origin/main`)
- Steps 1–4 (earlier): `f92f124`, `8413b5e`, `3a5526a`, `e9f4e49`, `7ffb575`, `68526e2`, `cbaab14`.
- This round: `b048ce6` (order outbox partitioning), `a5070b5` (dispatch outbox partitioning), plus the result commits listed under "Task 1" and "Cold/warm" below.

## Task 1: flat `orders` with a `created_at` index vs `orders_p`
- **Data.** Clean experimental database (schema dump, reseeded: 1,000,000 orders, 5,844,872 outbox rows in both variants). The first attempt was invalid: the earlier pgbench replay had left 3.2 M `bench` rows in the flat table only, so it was discarded. Its results are not used.
- **Method.** `loadtest/partition/05-range-compare.sql`, `run-range.sh`, three repeats, EXPLAIN (ANALYZE, BUFFERS). Results: `loadtest/results/week4s5-range-clean/`.
- **Result** (range: orders created in the last 7 days, about 21,800 rows):

| Variant | Plan | Buffers (hit/read) | Execution |
|---|---|---|---|
| Flat, `orders_created_idx` on `created_at` | Index Only Scan on `created_at` | 59 (about 55 read on the first repeat) | 2.0–3.4 ms |
| Partitioned `orders_p` (no `created_at` index) | Index Only Scan on `(client_id, created_at)` in 1 partition | 314 | 5.0–26.7 ms |

- **Conclusion.** For this range query, the flat table with a plain `created_at` index is better than the partitioned table. Partitioning does not help the range query here. The earlier "57 ms vs 5 ms" result came from the flat table having no index at all, so it was a missing-index effect, not a partitioning effect.
- **Index.** `orders_created_idx` was added to the experimental database only, as a measurement index.

## Outbox partitioning (adopted, order and dispatch)
- **Design.** V6 migration in each service: monthly RANGE partitions on `created_at`, DEFAULT partition, primary key `(id, created_at)`, partial index on unpublished rows per partition, rows moved in the migration's transaction. `OutboxPartitions` creates the next three months and detaches and drops a month only when the month is older than `peakload.outbox.partitions.retention` (30 days) **and** has no unpublished row. Relay per-row update names `created_at`. Not partitioned: `orders`, `idempotency_keys`, `processed_events`.
- **Safety tests** (`OutboxPartitionsIT`, both services):
  - `partitionWithAnUnpublishedRowIsNeverDropped`: an old partition with an unpublished row is kept; after the row is published, the partition is dropped. **Pass.**
  - `maintenanceCreatesTheNextMonthsAndIsRepeatable`: the next months are created, and a second call is safe. **Pass.**
- **Existing tests.** Order: 10 unit + 14 integration, all pass (`RelayIT`, `RelayRecoveryIT`, `BrokerOutageIT`, `IdempotencyIT`, `AtomicityIT`, `StatusIT`). Dispatch: 16 pass. `mvn -B -pl <service> -am verify` on JDK 21.
- **Fixed on the way.** `RelayRecoveryIT` failed once: my new test class kept its Spring context cached, and that context's Kafka consumer took messages from the shared consumer group. Fixed with `@DirtiesContext(AFTER_CLASS)`, as `RelayRecoveryIT` already uses. Passes after the fix.
- **Before/after measurements** (clean database; `loadtest/partition/09-relay-explain.sql`, `after-measure.sh`; results `loadtest/results/week4s5-relay-explain/` and `week4s5-after-measure-20261122-1053/`):

| Measure | Before (flat) | After (partitioned) |
|---|---|---|
| Relay batch select, 100 rows (EXPLAIN buffers) | 311 buffers, execution 0.51 ms, planning 1.3 ms | 1,018 buffers, execution 0.38 ms, planning 4.5 ms (15 partitions probed) |
| Per-row update, `WHERE id` only (EXPLAIN) | 19 buffers, 1.8 ms | 58 buffers, 8.7 ms (every partition probed) |
| Per-row update, `WHERE id AND created_at` (the new statement) | n/a | 14 buffers, 0.08 ms (one partition) |
| Order-create transaction, pgbench, 16 clients, 60 s, 3 alternating repeats | 9,742 / 9,867 / 9,686 tps (latency 1.62–1.65 ms) | 9,476 / 8,985 / 9,168 tps (latency 1.69–1.78 ms) |
| Relay claim-and-mark, one row per transaction, pgbench, 16 clients, 60 s, 3 alternating repeats | 5,065 / 5,832 / 5,726 tps (latency 2.74–3.16 ms) | 4,021 / 3,915 / 3,972 tps (latency 3.98–4.09 ms) |

- **Reading.** The order-create transaction is about 6% slower after partitioning (the two ranges do not overlap, so the difference is larger than the run-to-run spread). The relay claim step is about 28% slower. The relay claim replay is one row per transaction, which is a worst case: the real relay claims 100 rows per batch, so its per-row planning cost is spread over a batch. Both numbers are on this VM and this dataset only.
- **Cost, stated plainly.** The relay's claim probes all partitions, so its planning cost is higher. The per-row update is only fast with `created_at` in the statement, and the relay now does that. Partitioning buys retention by dropping a month, not query speed. Retention itself is measured below in Step 4 (earlier round).

## Cold/warm admission start (`loadtest/step2/cold-start.sh`, results `loadtest/results/week4s5-cold-start-20261122-1044/`)
- **Setup.** Creates at 4,400 req/s through admission; limiter and shedding off; dispatch stopped; empty tables; pre-run check before each run. Cold: admission freshly recreated, measured run starts at once. Warm: admission freshly recreated, then a 30 s unmeasured run at 4,400 req/s, then the measured run. Two alternating repeats each.

| Run | Mode | Creates 201 | 503 | pool_timeout | other | Failure rate | Achieved req/s |
|---|---|---|---|---|---|---|---|
| r1 | cold | 10,077 | 13,929 | 10,367 | 3,562 | 58.0% | 769 |
| r1 | warm | 10,689 | 16,854 | 12,638 | 4,216 | 61.2% | 875 |
| r2 | cold | 11,711 | 17,601 | 11,343 | 6,258 | 60.0% | 940 |
| r2 | warm | 10,198 | 15,147 | 12,077 | 3,070 | 59.8% | 827 |

- **Result.** Cold and warm fail at the same rate (58–61%). **The warm-up did not remove the failure, so the "cold start" explanation does not hold in this setup.** No 429, shed or 504 in any run; every failure was a 503.
- **The "other" error type.** The body is `{"error":"other","message":"the order service call failed"}`. That is the catch-all branch in `ProxyService.classify`: the exception was not a connect timeout, connection refused, read timeout or pool timeout. The underlying exception is not logged, so **its real cause is undetermined**. Admission logs do not record it.
- **Earlier clean probe.** One probe (`week4s2-status-probe-20261122-0904`, 130,947 of 130,947 creates succeeded at 4,365 req/s) was not reproduced by any run in this round. Unexplained.
- **Not measured.** Order-side pool and CPU during these runs (no sampler in this runner). The earlier Step 1 evidence (order Hikari saturated at 4,400 req/s) points at the order side as the likely limit.
- **Options to fix, with trade-offs** (none is implemented; the evidence does not yet pick one):
  1. **Pre-open the upstream pool at startup** (create the connections before traffic). Trade-off: slower startup and more open connections at idle. It would not help if the failures are steady-state, which these runs suggest.
  2. **Readiness gate** (report not ready until the pool is warm, and let the balancer wait). Trade-off: deployment and scale-out take longer, and it only helps the first seconds after a start. It would not fix the steady-state 58–61% here.
  3. **Raise `max-connections` or the pool wait** (currently 200 and 0.5 s). Trade-off: more queueing latency, and more order-side load, which is the likely bottleneck. The failures move rather than go away.
  4. **Fix the order-side limit first** (the Step 1 Hikari pool of 10 saturates at 4,400 req/s). This is the option the evidence supports most, but it is an application change, which needs your decision.

## Memory check (crashes)
- **OOM kills: none.** `docker inspect` `OOMKilled=false` and `RestartCount=0` for postgres, order, admission, dispatch, redpanda and redis. The Postgres log shows the backend exits as `exited with exit code 2`, not `terminated by signal 9`, which is what a kernel OOM kill would show. The VM kernel log is not available from macOS.
- **Memory during measured runs** (from the sampler, `docker stats`): Step 1 run B peaks were postgres 999 MiB, order 652 MiB, admission 303 MiB, dispatch 308 MiB, redpanda 329 MiB. The VM has 7.75 GiB. The bisect runs peaked lower (postgres 309 MiB, empty tables). The cold/warm runs did not use the sampler, so no memory readings exist for them.
- **Crash cause: undetermined.** Two backend exits on `CREATE DATABASE ... TEMPLATE` (03:39), both restored cleanly, data verified. Exit code 2 is not a signal, so I have no evidence of memory pressure. The cause is not determined.

## Cleanup
- Dropped `peakload_exp` (as asked) and `peakload_exp2` (my clean copy from this round).
- **Left in place, your call:** database `week1` (from the Step 2 Week 1 comparison). It is experimental and can be dropped with `DROP DATABASE week1`.
- **Not run and removed:** `08-clean-bench-fk-dropped.sql`, which dropped the foreign key. Its permission was denied and it was never executed.
- Scripts and schema SQL remain in `loadtest/partition/`, `loadtest/step2/` and the migrations.

## Earlier steps (unchanged)
- Step 1 (database baseline at 1M orders): Week 1's 0.71 ms median was not reproduced (about 680 ms on big and empty tables alike).
- Step 2 (bisect): the Week 1 binary is slower today than the current binary under identical conditions; dispatch, `pg_stat_statements` and reads show no effect beyond the run-to-run spread. Admission-path runs in the bisect were cold-start numbers and are not valid for steady state.
- Step 3 and 4 (earlier round): partitioned copies of `outbox` and `orders` measured on `peakload_exp`; retention by drop was far cheaper than batched delete (45–166 ms vs 10–28 s; under 0.7 MB WAL vs about 2.4 GB), with the concurrent-insert difference inside the spread.

## Decisions for you
1. Accept the measured order-create cost (about 6%) and relay-claim cost (about 28% in the one-row-per-transaction proxy) of outbox partitioning, given that it buys retention by dropping months.
2. Pick an option for the admission failures (above), or accept the 58–61% failure as the current limit until the order-side limit is addressed.
3. Whether to drop the `week1` database.
4. Push `week4-db` yourself, once the commits are reviewed.
