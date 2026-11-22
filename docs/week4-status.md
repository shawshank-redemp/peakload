# Week 4 status

Branch `week4-db`. Nothing pushed. `main` untouched. Commits are local.

## Step 1: database baseline at 1M orders
- **Done.** Seed, workload, diagnostics, EXPLAIN: `loadtest/db/`, `loadtest/run-db-baseline.sh`, `loadtest/db-baseline.js`. Results: `loadtest/results/week4s1-*`. README "Week 4: database baseline". Commit `f92f124`.
- **Result.** Week 1's median at 4,400 req/s (0.71 ms) was not reproduced (about 680 ms, big and empty tables alike).

## Step 2: reproducing Week 1 and database fixes
- **Done: bisect.** Six conditions, two runs each, alternating. Results: `loadtest/results/week4s2-bisect-20261122-0826/`. Commits `8413b5e`, `3a5526a`.
- **Finding.** The Week 1 binary is slower today than the current binary under identical conditions (median 36–53 ms vs 1.6–2.4 ms). The environment changed since Week 1; the code did not get slower.
- **No factor beyond run-to-run spread:** dispatch on/off, `pg_stat_statements` on/off, 50/s reads.
- **Corrected in this round (A).** The admission-path runs failed half their creates because admission had just been recreated (cold start): 503s with `pool_timeout` and `other` upstream errors, no 429, shed or 504. A warm admission had zero errors. Those two runs measured cold start, not steady state, and are not valid for the comparison. Not re-run.
- **Not determined.** Why cold admission hits pool timeouts (the 200-connection pool is a guess), and what the `other` error type is.
- **Not done.** Part (c), database fixes: no factor was shown to matter beyond the spread, so none was applied.

## Pre-run check (B)
- `loadtest/precheck.sh <run folder>`: thermal and CPU speed limits, Docker idle CPU (limit 100 of 600), autovacuum and checkpointer activity. Writes `precheck.txt` into the run folder; refuses on a failed reading. Wired into `loadtest/step2/run-cond.sh`, `loadtest/partition/run-measure.sh`, `loadtest/partition/bench-create.sh` and `loadtest/partition/run-retention.sh`.
- The thresholds are starting values chosen before any run. They are not measured results.
- It refused runs several times in Steps 3 and 4 while autovacuum processed tables the previous step had loaded. That is the check working, but it lengthens the schedule.

## Step 3: partitioning experiment (`peakload_exp`, standalone SQL, not Flyway)
- **Done.** Partitioned `outbox_p` and `orders_p` (monthly, 2025-11 to 2026-12, plus default). Data moved with `INSERT ... SELECT`. Scripts `loadtest/partition/01-setup-variants.sql`, `02-measure-deterministic.sql`, `bench-create.sh`.
- **Deterministic measures (three repeats each).** GET by id without `created_at` probes 15 partitions (planning buffers 513 vs 47; execution 0.10–0.36 ms vs 0.016–0.025 ms). The relay query probes 15 partitions (buffers 158–171 vs 226–240, execution similar). A 7-day range is 5–7 ms partitioned vs 57 ms flat, but the flat table has no `created_at` index, so this is confounded. WAL per 10,000-row insert: 2.78–2.99 MB partitioned vs 2.78–2.83 MB flat (no difference beyond spread). Sizes in the README.
- **Order-create replay (pgbench, 16 clients, 60 s, alternating, three repeats).** Partitioned was slower in all three pairs (9,499 vs 10,327; 9,042 vs 9,539; 6,852 vs 8,038 tps), but the flat repeats span 8,038–10,327 tps. No throughput difference is claimed.
- **Constraint found.** `idempotency_keys` references `orders(id)`; Postgres cannot reference a partitioned table on `id` alone. Partitioning `orders` needs a composite foreign key and a new column on `idempotency_keys`. Not tested, not applied. The `orders_p` copy has no foreign key.
- **Not partitioned, by design:** `idempotency_keys` and `processed_events`.
- **Not done.** A flat-plus-`created_at`-index variant, to separate partitioning from the index in the range query. Flat-variant ranges are therefore not a partitioning result.
- **Decision for you.** Partition `outbox`? Gains are small in these measures and the relay probes all partitions. The default recommendation is to keep the unpartitioned design.

## Step 4: retention (`peakload_exp`)
- **Done.** Batched DELETE (10,000-row commits, index on `created_at`) vs `DETACH` + `DROP` of six monthly partitions. Three alternating repeats each, under concurrent pgbench inserts into the same table. Scripts `03-retention-setup.sql`, `04-op-batch.sql`, `04-op-drop.sql`, `run-retention.sh`.
- **Measured.** Batch: 10.9–27.7 s, about 2.4 GB WAL, 2.55 M dead tuples, no space returned. Drop: 45–166 ms, under 0.7 MB WAL, zero dead tuples, about 0.7 GB returned at once.
- **Concurrent inserts.** The drop option had higher tps and lower latency in all three pairs. The mean difference (about 21%) is about the spread, so it is not claimed as an established effect.
- **Not measured.** Lock waits (`pg_locks`), `DETACH ... CONCURRENTLY`, and the operation's own window in the latency log.
- **Decision for you.** If retention is needed, dropping partitions is far cheaper. That only matters if partitioning is adopted.

## Decisions the owner must make
1. Whether to adopt `outbox` partitioning, given the small measured gains and the all-partition relay probe.
2. Whether `orders` partitioning is worth a composite foreign key and a schema change; not tested.
3. Whether to re-run the admission-path bisect with a warm-up at full rate. The current runs are invalid as steady state.
4. Whether to measure the flat-plus-index variant before deciding.
5. Push `week4-db` (you said you will do it).

## Stack state
- Default settings. `dispatch`, `order`, `admission` running. `w1-order` removed. `pg_stat_statements.track` restored to `all`. Database `week1` left in the project volume from the Step 2 Week 1 run; `peakload_exp` holds the experiment data.

## Verification
- `mvn -B verify` on JDK 21: exit 0 after Step 1 and after Step 2. No Java changes since. Re-run before pushing.
- Re-run this round: see the commit log; no Java files changed in these steps.

## Incidents to know about
- The first `CREATE DATABASE ... TEMPLATE` crashed the Postgres backend (exit code 2, no logged reason), and the server restarted. Data checked after recovery (1,000,000 orders). A second template copy crashed too. I used an empty database plus schema dump and reseed instead. Cause not determined.
