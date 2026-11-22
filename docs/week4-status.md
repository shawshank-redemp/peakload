# Week 4 status

Branch `week4-db`. Nothing pushed. `main` untouched.

## Step 1: database baseline at 1M orders
- **Done.** Seed, workload, diagnostics and EXPLAIN in `loadtest/db/`, `loadtest/run-db-baseline.sh`, `loadtest/db-baseline.js`. Results in `loadtest/results/week4s1-*`. README section "Week 4: database baseline". Committed locally as `f92f124`.
- **Result.** Week 1's 4,400 req/s median (0.71 ms) was not reproduced (about 680 ms, on big and empty tables alike). Hot queries use indexes.
- **Not verified.** Why. See Step 2.

## Step 2: reproducing Week 1 and database fixes
- **Done: bisect.** `loadtest/step2/run-bisect.sh`, six conditions run twice each in alternating order, results in `loadtest/results/week4s2-bisect-20261122-0826/`. README section "Step 2 results".
- **Measured.**
  - The same condition varies by an order of magnitude between runs (p99 21 ms to 640 ms).
  - The Week 1 binary is slower today than the current binary under identical conditions (median 36–53 ms against 1.6–2.4 ms). So the environment has changed since Week 1, and the code is not the main factor.
  - Dispatch, `pg_stat_statements` and the 50/s read mix show no effect larger than the spread.
  - Admission in the path: about 50% of creates failed the 201 check, cause not determined.
- **Not done.** Part (c), database fixes. No factor was shown to matter beyond noise, so no fix was applied. Nothing was tuned.
- **Discarded.** One first attempt (folder moved to the session scratchpad, not in the repo) was stopped because `pg_stat_statements` was not reset before the measured run. Its one data row is not used.
- **Not done.** The README section "Week 4: reproducing Week 1 and database fixes" is written as the bisect results, not with the Week 1 capacity claim the plan asked for. The measured full-stack capacity was not established.
- **Decision for you.** Whether to run more repetitions per condition (about 5 each would take roughly 75 minutes), or to accept that Week 1's number is not reproducible on this VM today and drop the comparison.

## Step 3: partitioning experiment
- **Not started.** The plan asks for a pgbench replay of the order-create transaction on the big dataset. That harness does not exist yet. The partitioning would also be an experimental variant on a separate database, not a Flyway migration in the app, because adoption is your decision.
- **Constraint to keep.** `idempotency_keys` and `processed_events` must not be partitioned; a unique rule would have to include the partition key.

## Step 4: retention
- **Not started.** Depends on Step 3's variant.

## Decisions the owner must make
1. Whether to spend about 75 minutes on more Step 2 repetitions, or to stop chasing the Week 1 gap.
2. Whether Step 3 may use an experimental database and standalone SQL instead of a Flyway migration.
3. Whether to push `week4-db` (contains raw k6 summaries and samples in `loadtest/results/`, under 10 MB per run folder; `points.json.gz` is git-ignored).

## Finish checklist
- `mvn -B verify` on JDK 21: run at the end of Step 1 (exit 0). Re-run after Step 2 as well: exit 0 (Step 2 changed no Java). Re-run before any push.
- Stack: running with default settings (`dispatch`, `order`, `admission` started; `w1-order` removed; `pg_stat_statements.track` restored to its default).
