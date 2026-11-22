# Peakload

A surge-resilient order and dispatch backend, load-tested on a New Year's Eve-shaped traffic curve.
The point of the project is the engineering trade-offs, each written up as an ADR with numbers.

> Status: Weeks 1–6 measured (Week 6 step 2 in progress, see its section). Each result below cites the folder its numbers come from; each milestone's section explains its setup.

## Results

Every number is from a run folder under `loadtest/results/` (named in the table). Run counts are the number of runs of that condition; ranges are across those runs, not averages. Nothing here is a production claim: one local VM, shared CPUs, simulated clients.

| What was measured | Conditions | Runs | Range across runs | Main limit | Folder |
|---|---|---:|---|---|---|
| Direct order creation ceiling | `POST /orders` straight to the order service (in-network, no admission), 30 s per run, 2000 / 3200 / 4400 req/s | 15 (5 at 2000, 4 at 3200, 6 at 4400) | Pass rule (accepted p99 < 200 ms, < 1% dropped, zero non-2xx): **4 of 5** at 2000, **3 of 4** at 3200, **1 of 6** at 4400. Accepted p99 3.7–4784 ms; dropped 0–72% | Intermittent failure at every rate; failing runs had Postgres CPU 270–383% on average, passing runs 95–172%. Cause not found. k6 saturated at 4400 | `week6-step2-20261122-1518/` (`direct-*`, `interleave-*`, `phase2-*`, `phase2b-*`) |
| Historical reference: direct order creation (Week 1) | `POST /orders` direct, 30 s per run, Week 1 code (before Week 4 partitioning and later changes) | 2 per rate | 5200 req/s passed (p99 under 200 ms); 5600 req/s failed | Not comparable to the table above (older code, no pickup coordinates) | `order-create/week1-20261121-1418/` |
| Admission path capacity | `POST /orders` through admission, 30 s per run, shedding on (one 1600 run with shedding off) | 800 req/s: 3; 1600 req/s: 3 with shedding on, 1 with shedding off | 800: 100% 201 in all three; accepted p99 3.8–76.6 ms. 1600 (shed on): 503 share 17.38%, 2.72%, 0.00%. 1600 (shed off): 0% 503, accepted p99 366 ms | Postgres and the order connection pool saturate first; one of three 1600 runs failed | `week6-capacity-20261122-1419/` |
| Limiter noisy neighbour | Five normal clients at 20 req/s each, one abusive client at 1200 req/s, 30 s; limiter on vs off, three runs each | 3 on, 3 off | Abusive 429 with limiter on: 32,802 / 32,803 / 32,294 (84–91%); abusive accepted 3,199 / 3,199 / 3,569 (expected about 3,200 = 200 burst + 100/s × 30 s). Normal 429: 0 in all six runs. Normal 503 (shedding): 0 / 0 / 30 with limiter on; 0 / 0 / 35 with limiter off | One instance; client IDs are sent by the client, so a client can claim another ID; run 3 had local-fallback decisions | `week6-step2-20261122-1518/` (`noisy-*`) |
| Limiter overhead | Limiter on vs off, through admission and direct, one run per rate per condition | 1 per rate per condition | p99 moved in both directions (800 req/s: 4.85 off, 35.12 on ms; 1600 req/s: 30.02 off, 16.93 on ms). Median cost about 0.5 ms for the Redis round trip | One run per condition; no consistent p99 effect can be shown at this count | `week3-admission-20261121-2156/`, `week3-direct2-20261121-2159/` |
| Redis outage: fallback and breaker | Noisy-neighbour load, 90 s; Redis stopped (A), paused with breaker on (B), paused with breaker off (C) | 1 per condition | Normal-client accepted p99 during outage: 9 ms (A), 9 ms (B), 55 ms (C). Limiter errors counted: 36 / 91 / 39,279. No 5xx in any run | One run per condition; one instance; a second instance would have its own local buckets | `week3s3-A-stop-breaker-on-20261121-2229/`, `week3s3-B-pause-breaker-on-20261121-2229/`, `week3s3-C-pause-breaker-off-20261121-2229/` |
| NYE curve: shedding on vs off (breaker on in both) | 300 s curve, POST peak 2000 req/s (above the 1600 edge), shedding on and off alternating, three pairs | 3 pairs (6 runs) | Every pair agrees on two things: accepted count higher with shedding on (125,844 / 128,355 / 152,770 vs 67,219 / 62,114 / 97,034) and accepted p99 lower with shedding on (882 / 1,194 / 786 ms vs 2,910 / 2,614 / 1,974 ms) | k6 saturated: 61,063–202,253 iterations dropped per run; the breaker opened 5–7 times in every shedding-off run and never in a shedding-on run, so the effect is shedding plus the breaker, not shedding alone | `week6-step2-20261122-1518/` (`nye-*`) |
| Order-kill breaker test (measurement E) | `POST`, read and menu traffic at 800 req/s for 90 s; order service stopped at +30 s and started at +60 s; breaker and retries on (ON) or off (OFF) | 4 (2 ON, 2 OFF) | ON: failures are fast 503 `order_unavailable` (96.5% of creates during the kill). OFF: during the kill, creates were 19% `connect_timeout`, 74% `other` 503, 4% `shed` (slow failures) | Two runs per condition | `week5-measurement-e-20261122-1220/` |
| Pause test (retries) | 800 req/s for 90 s; order service paused at +30 s and unpaused at +60 s; breaker on in all runs; retries on (ON) or off (OFF) | 4 (2 ON, 2 OFF) | Breaker opened 0.65–0.68 s after the pause and closed 1.5 s after the unpause, in all four runs. No retry was made in any run (the deadline does not fit a second read timeout) | Two runs per condition; retries never fired, so the retry path is not shown to help | `week5-retry-pause-20261122-1241/` |
| Dispatch chaos with static membership | 20 req/s for 150 s; `docker kill` of dispatch at +20, +60, +100 s, restart 15 s later; control runs with no kill | 4 (2 kill, 2 control) | Restart to partitions assigned: 3.1–4.4 s (static membership) vs 38.7–39.3 s for the last kill without it. Invariants: all zero in all four runs | One dispatch instance, one VM; two runs per condition | `week5-chaos-static-20261122-1357/`; previous series without static membership `week5-chaos-series2-20261122-1334/` |
| Outbox retention: DELETE vs DETACH + DROP | Monthly outbox partitions on the experimental database (`peakload_exp`), 5.8 M-row outbox; three repeats of each method | 3 + 3 | Duration: batched DELETE 10.9–27.7 s; DETACH + DROP 45–166 ms. DELETE leaves 2,546,193 dead tuples; DROP leaves 0. Max concurrent insert latency: DELETE 207–254 ms, DROP 76–271 ms | Single database and VM; the DROP case does not print the row count it removed | `week4s4-retention-20261122-0930/` |

**What these numbers do not show.**
- A single local VM: admission, order, Postgres, Redis, Redpanda and k6 share six vCPUs. Every number includes that shared load.
- Small run counts: two to six runs per condition. The spread is between those runs; no mean is given for a difference.
- Simulated clients (k6 and scripted abusers), not real traffic. The NYE curve's shape and rates are assumptions, not measured demand.
- A spoofable client ID: the limiter keys on the `X-Client-Id` header, which a client sets itself.
- k6 saturation: at 4400 req/s and at the NYE peak k6 itself was at or near its CPU limit, so some results measure the generator as well as the service.
- The unknown cause of the intermittent Postgres CPU spikes in the direct runs, and of the 16–18 s stall in the Week 5 cold check.
- Backend errors: the order service's connection-pool timeouts (`CannotCreateTransactionException`) answered 500 until the fix in this step, which maps them to 503 with `Retry-After`. Every measurement above that involves those timeouts predates the fix. No other backend crash is recorded in these folders.

## Run it

```bash
docker compose up --build
```

Host ports for Postgres and Grafana come from `.env` (git-ignored):

```
POSTGRES_PORT=15432
GRAFANA_PORT=13000
```

Defaults are 5432 and 3000 if `.env` is missing. The `.env` here is for this machine. A local Postgres on 5432 would silently capture `localhost:5432` connections, so the compose Postgres is moved to 15432 and Grafana to 13000 to avoid clashes with local tools.

| What | Where |
|---|---|
| Admission (entry point) | http://localhost:8080/api/ping |
| Order / Dispatch / Tracking | not published to the host (compose network only). From a host shell: `bash loadtest/http-in-network.sh order 8081 /api/ping` (the same for `dispatch 8082`, `tracking 8083`) |
| Grafana (dashboard "Peakload overview") | http://localhost:13000 (default 3000) |
| Prometheus | http://localhost:9090 |
| MinIO console | not started by default, see below |
| Postgres | localhost:15432 (db / user / password: peakload) |
| Redpanda (Kafka API) | localhost:19092 |

**MinIO** is behind the `later` compose profile because it is not needed until week 4. It is not started by `docker compose up`. To start it explicitly: `docker compose --profile later up minio`. Note: the `minio/minio` image is no longer on Docker Hub, so this profile will not pull until the image is fixed in week 4.

Run the baseline load test. k6 runs as a compose service on the compose network, so the requests do not go through host port forwarding (on macOS that path added a ceiling of about 30–50 req/s, see Results):

```bash
docker compose --profile loadtest run --rm k6 run /loadtest/baseline.js
```

Change the rate with `-e RATE=400` (default 200), e.g. `docker compose --profile loadtest run --rm -e RATE=400 k6 run /loadtest/baseline.js`. The k6 service is behind the `loadtest` profile, so `docker compose up` does not start it.

Then watch request rate and p99 latency move in Grafana.

## How to run

Everything here runs from the repository root. Commands are as written; the ones marked **tested** were run once from a clean checkout of this branch.

**Prerequisites.** Docker Desktop (the stack and k6 run in containers). JDK 21 for Maven (`JAVA_HOME` must point at a JDK 21; a default JDK 27 fails the build). Bash, curl and Python 3 on the host. The pre-run check reads the Mac's thermal state with `pmset`, so it works only on macOS; on another OS, skip it and record that in the run folder.

**1. Start the stack** (**tested**)

```bash
docker compose up -d --build
curl -s localhost:8080/api/ping        # admission: {"service":"admission",...}
```

Admission (8080) is the only app port on the host. Order, dispatch and tracking are on the compose network; reach them with `loadtest/http-in-network.sh <service> <port> <path>`. Prometheus (9090) and Grafana (3000 or `GRAFANA_PORT`) are published.

**2. Smoke test through admission** (**tested**)

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" -H 'X-Client-Id: smoke' \
  -d '{"customerId":"smoke","city":"bengaluru","totalPaise":500,"pickupLat":12.97,"pickupLon":77.59}' \
  localhost:8080/orders                 # 201
```

**3. Run the tests** (**tested**)

```bash
JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home mvn -B verify
```

The integration tests use Testcontainers, so Docker must be running. This is the same command the Week 6 step 2 check used (green on JDK 21).

**4. Benchmarks.** Each script writes into the folder you name with `OUT=`. Create the folder first if you want a fixed location; the scripts create it. Every run first runs the pre-run check and resets the order (and, where used, dispatch) tables, runs a discarded warm-up, resets again, and then runs the timed load.

| Benchmark | Command | What it measures |
|---|---|---|
| Direct order creation (Week 6 step 2) | `OUT=loadtest/results/my-direct MODE=direct RATE=800 bash loadtest/week6/run-step2.sh` | `POST /orders` straight to the order service, 30 s (**tested** at 800 req/s; use 2000, 3200 or 4400 for the ceiling runs) |
| Limiter noisy neighbour | `OUT=loadtest/results/my-noisy MODE=noisy LIMITER=on bash loadtest/week6/run-step2.sh` (or `LIMITER=off`) | 5 normal clients at 20 req/s, one abusive client at 1200 req/s |
| NYE curve, shedding on/off | `OUT=loadtest/results/my-nye MODE=nye SHED=on PEAK_CRITICAL=2000 bash loadtest/week6/run-step2.sh` | 300 s curve; `PEAK_CRITICAL` is the POST peak (default 1000 in the Week 3 curve) |
| Admission capacity (Week 6 step 1) | `OUT=loadtest/results/my-ramp RATE=800 SHED=on bash loadtest/week6/run-ramp.sh` | `POST /orders` through admission at a fixed rate |
| Redis outage | `OUT=loadtest/results/my-outage OUTAGE=stop BREAKER=true bash loadtest/run-outage.sh` | noisy load with Redis stopped (`stop`) or paused (`pause`) |
| Order kill, breaker/retries (Week 5 measurement E) | `bash loadtest/week5/run-measurement-e.sh loadtest/results/my-e` | ON, OFF, ON, OFF at 800 req/s; order stopped at +30 s, started at +60 s |
| Pause (retries) | `bash loadtest/week5/run-retry-pause.sh loadtest/results/my-pause` (retries ON and OFF alternating, two runs each) | `docker pause` of the order service at +30 s, unpause at +60 s, breaker on |
| Dispatch chaos (Week 5 step 2) | `OUT=loadtest/results/my-chaos KILLS=3 RATE=20 DURATION=150s bash loadtest/chaos/run-chaos.sh` (`KILLS=0` for a control) | dispatch killed with `docker kill` three times per run; invariants checked after the drain |
| Outbox retention (Week 4 step 4) | `OUT=loadtest/results/my-retention bash loadtest/partition/run-retention.sh` | batched DELETE vs DETACH + DROP; **experimental database only** (`peakload_exp`) |

Runs take a few minutes each (the 300 s NYE curve about 8 minutes including its warm-up, reset and drain). The Week 6 step 2 batch ran these in sequence and stopped on the first failed invariant.

The step 2 runner does not write `analysis.txt` by itself. After a run, produce it with `python3 loadtest/week6/analyse_step2.py <OUT>` (it exits 1 if an invariant fails). Tested from a clean checkout of this branch: the direct run at 800 req/s gave 24,001 requests, all 201, accepted p99 2.6 ms, invariants hold. That run's folder was not committed (it was made in the clean checkout only).

**What the pre-run check does** (`loadtest/precheck.sh <OUT>`). It writes `precheck.txt` into the run folder and refuses to start the run (exit non-zero) when: a thermal or speed warning is recorded; the Docker idle CPU (median of three samples, out of 600% on this 6-vCPU VM) is above 100%; or an autovacuum worker or the checkpointer is busy. It also records the table counts, so you can see the starting state.

**What the reset does.** The run scripts drain the order outbox (wait until no row is unpublished, capped at 300–900 s), then run `loadtest/db/seed.sh` with `EMPTY=1`, which truncates the order and dispatch tables. `reset.txt` in the run folder records the counts after each reset. Dispatch is stopped for the create-path runs and started again at the end.

**Where results go, and how to read a run folder.** Each run writes into its folder:

- `analysis.txt`: the numbers, generated from the files below by `loadtest/week6/analyse_step2.py` (step 2 runs) or the matching `analyse_*.py`. It has the outcome breakdown (adds to 100.00%), accepted-request latency, CPU per container, Postgres counters before and after, and the invariant result (`invariants: all hold` or `FAILED: ...`).
- `counts.txt`: orders, idempotency keys, duplicate pairs and unpublished outbox rows at the end.
- `reset.txt`, `precheck-*`, `pg-before.txt`, `pg-after.txt`: the starting state and Postgres counters.
- `samples.jsonl`: CPU and the admission/order actuator values, every 2 s.
- `admission-prometheus.txt`, `order-prometheus.txt`: the counters at the end (breaker transitions, shed, limiter fallback).
- `k6/k6-summary.json`: k6's own summary. The per-request points (`k6/points.json.gz`) are large and are not in git; the analysis reads them, so keep them if you re-analyse a run.

Raw logs (`k6-stdout.log`, `admission-log.txt`, `*.stdout`, `*.log`) are not kept in git; re-running the script reproduces them. Only the analysis and the counters are committed.

## Layout

```
services/admission   rate limiting, load shedding, cache (entry point)
services/order       orders, idempotency, state machine, outbox
services/dispatch    rider matching and assignment
services/tracking    GPS ingest and live position
loadtest/            k6 scripts (baseline, NYE-shaped curve)
monitoring/          Prometheus config, Grafana dashboard
docs/adr/            one decision record per trade-off
```

## Plan

| Week | Milestone | ADRs |
|---|---|---|
| 0 | Scaffold, compose, monitoring, baseline test | 0001 |
| 1 | Order core: idempotency, state machine, outbox | outbox vs dual write; idempotency design |
| 2 | Dispatch: single rider per order, consumer dedupe | assignment source of truth; instant vs batched |
| 3 | Admission: limiter comparison, load shedding, cache | limiter algorithm; limiting vs shedding; cache |
| 4 | Tracking, partitioning, two-lane bus, Postgres levers | partitioning key; two lanes; bottleneck levers |
| 5 | Saga, retries, circuit breaker, tracing, chaos test | saga vs 2PC; retries and breaker |
| 6 | Benchmarks, runbook, README results, demo video | |

## Results

Measured results only. Each run names the machine and the exact command. Numbers are copied from k6's JSON summary export and k6's own threshold line, without rounding.

### In-network baseline (the documented command)

**Machine:** Apple M5 (10 cores), 16 GiB RAM, macOS 26.5.1.

**Docker:** engine 29.5.3, Ubuntu 24.04.4 LTS VM, **6 CPUs and 7.75 GiB RAM** (`docker info`: `cpus=6 mem_bytes=8322072576`). Docker context `desktop-linux` (Docker Desktop) was active. Colima was also running on this Mac (see the port-path diagnosis below).

**Stack:** admission, order, dispatch, tracking, prometheus, grafana, postgres, redis and redpanda all up. `.env` ports: grafana 13000, postgres 15432.

**k6:** v2.4.0, `grafana/k6:latest`, run as the compose service `k6` on network `peakload_default`, with `BASE_URL=http://admission:8080`. Threshold unchanged: `p(99)<200` and `http_req_failed rate<0.01` (`loadtest/baseline.js`).

**Commands** (one run each; `<dir>` is a host folder mounted at `/out` for the JSON summary):

```bash
# documented baseline (RATE=200 default, 30s)
docker compose --profile loadtest run --rm k6 run /loadtest/baseline.js

# warm-up, discarded: 30s at RATE=20
docker compose --profile loadtest run --rm -e RATE=20 -e DURATION=30s -v <dir>:/out k6 run --summary-export /out/warmup.json /loadtest/baseline.js

# ladder, each 30s: RATE=200, 400, 800, 1600 (same command, different RATE)
docker compose --profile loadtest run --rm -e RATE=800 -e DURATION=30s -v <dir>:/out k6 run --summary-export /out/inet-r800.json /loadtest/baseline.js

# 200 rerun, so p95 and p99 come from one run
docker compose --profile loadtest run --rm -e RATE=200 -e DURATION=30s -v <dir>:/out k6 run --summary-export /out/inet-r200-b.json /loadtest/baseline.js
```

| Run | Target req/s | Requests | Achieved req/s | Dropped iterations | p95 | p99 | Error rate | p99<200 |
|---|---|---|---|---|---|---|---|---|
| warm-up RATE=20 (discarded) | 20 | 600 | 19.998561159520545 | 0 | 1.0219544999999994 ms | 1.72ms | 0% | pass |
| RATE=200, first run | 200 | 6001 | 200.013730040989 | 0 | 0.881334 ms | 1.82ms | 0% | pass |
| **RATE=200, rerun (row used)** | 200 | 6001 | 200.01148874523756 | 0 | 0.89325 ms | 3.56ms | 0% | pass |
| RATE=400 | 400 | 12001 | 399.99895786289346 | 0 | 0.740709 ms | 1.36ms | 0% | pass |
| RATE=800 | 800 | 24002 | 799.9473600642148 | 0 | 0.6630160499999994 ms | 1.34ms | 0% | pass |
| RATE=1600 | 1600 | 47980 | 1599.198907338514 | 23 | 0.7290493999999997 ms | 2.03ms | 0% | pass |

The 200 row is the rerun because its p95 and p99 come from the same run. The first 200 run is shown for comparison: its p99 was 1.82 ms, and the rerun's was 3.56 ms, so run-to-run variation is real at this scale.

`max VUs` stayed at 50–51 in every run, so the pool of 50 pre-allocated VUs was the only one used.

**`docker stats --no-stream`** captured 15 s into each run (one sample per run; CPU % is per core, so 100% = one core):

| Container | CPU % (RATE=800) | Mem (RATE=800) | CPU % (RATE=1600) | Mem (RATE=1600) |
|---|---|---|---|---|
| k6 container (`peakload-k6-run-…`) | 29.64% | 27.22MiB | 47.29% | 31.04MiB |
| peakload-admission-1 | 27.71% | 250.2MiB | 38.96% | 252.6MiB |
| peakload-order-1 | 0.16% | 241.7MiB | 0.08% | 242MiB |
| peakload-dispatch-1 | 0.18% | 218.2MiB | 2.96% | 218.3MiB |
| peakload-tracking-1 | 0.14% | 221.4MiB | 0.14% | 221.5MiB |
| peakload-grafana-1 | 1.74% | 335.3MiB | 0.92% | 335.2MiB |
| peakload-prometheus-1 | 0.28% | 35.9MiB | 1.15% | 35.93MiB |
| peakload-postgres-1 | 0.02% | 21.22MiB | 0.02% | 21.22MiB |
| peakload-redis-1 | 0.30% | 12.98MiB | 0.43% | 12.98MiB |
| peakload-redpanda-1 | 0.37% | 382MiB | 0.31% | 382.1MiB |
| sentinel-demo-control-plane (background) | 15.28% | 1.177GiB | 12.17% | 1.18GiB |

**Reading:**
- **Knee (first rate where p99 exceeds 200ms):** not reached. p99 was 3.56 ms at the 200 rerun and 2.03 ms at 1600 req/s, so the knee is above 1600 req/s. To locate it, the ladder needs rates above 1600.
- **First resource that saturates:** none reached 100% of a core. At 1600 req/s, admission used 38.96% of a core and the k6 container used 47.29%. The VM's 6 CPUs and 7.75 GiB were not saturated (sentinel is the largest background user at 12–15%). The data does not show which resource saturates first. The one anomaly is 23 dropped iterations at 1600 req/s (0.05%), which I did not trace to a cause.
- **Why the earlier numbers were low:** the Docker-path runs and the host run went through the `127.0.0.1:8080` listener, which on this Mac is an SSH mux socket from Colima (`ssh: /Users/sindhus/.colima/_lima/colima/ssh.sock [mux]`, pid 75884). A single request to `127.0.0.1:8080` took 0.03–0.27 s. The same request to `[::1]:8080` took 2–6 ms. `curl` connect time was 0.11–0.15 ms, so TCP connection setup is not the cost. `loadtest/baseline.js` does not set `Connection: close` or disable keep-alive. The slow path is the IPv4 forwarder, not the service.

### Docker-path run, second run (Docker Desktop port forwarding, resources raised)

This run used `host.docker.internal` from a container and the host k6 on `localhost`. It is kept for comparison. It measured the port path, not the service.

**Machine:** Apple M5 (10 cores), 16 GiB RAM, macOS 26.5.1.

**Docker:** engine 29.5.3, Ubuntu 24.04.4 LTS VM with **6 CPUs and 7.75 GiB RAM** (raised in Docker Desktop before this run; `docker info`: `cpus=6 mem_bytes=8322072576`).

**Stack during the runs:** admission, order, dispatch, tracking, prometheus and grafana were up. postgres, redis and redpanda were stopped for the runs (`docker compose stop postgres redis redpanda`) and started again afterwards (`docker compose start`). Host ports from `.env` (grafana 13000, postgres 15432).

**Background load:** `sentinel-demo-control-plane` (kindest/node) was running the whole time at about 10–16% CPU when idle and 1.15 GiB RAM. It was not paused. `oracle11g` was not running: after Docker's restart there was no `oracle11g` container, but its volume `oracle11g-data` still exists. It was not restarted.

**k6:** v2.4.0 on the host (`brew install k6`, darwin/arm64) and v2.4.0 in the container (`grafana/k6:latest`, linux/arm64). Same version for both.

**Threshold:** unchanged, `p(99)<200` and `http_req_failed rate<0.01` (from `loadtest/baseline.js`).

**Commands** (the `<name>.json` file is the k6 summary export; one run each):

```bash
# warm-up, discarded: 30s at RATE=20
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e RATE=20 -e DURATION=30s grafana/k6 run --summary-export <name>.json - < loadtest/baseline.js

# ladder, each 30s: RATE=20, 50, 100, 200, 400 (same command, different RATE)
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e RATE=200 -e DURATION=30s grafana/k6 run --summary-export <name>.json - < loadtest/baseline.js

# host k6, RATE=200, 30s
k6 run --summary-export <name>.json -e BASE_URL=http://localhost:8080 -e RATE=200 -e DURATION=30s loadtest/baseline.js

# diagnostic, not the baseline: k6 inside the compose network, no port forwarding
docker run --rm -i --network peakload_default -e BASE_URL=http://admission:8080 -e RATE=200 -e DURATION=30s grafana/k6 run --summary-export <name>.json - < loadtest/baseline.js
```

| Run | Target req/s | Requests | Achieved req/s | Dropped iterations | p95 | p99 | Error rate |
|---|---|---|---|---|---|---|---|
| docker RATE=20 | 20 | 601 | 20.008226788268097 | 0 | 751.004959 ms | 1.25s | 0% |
| docker RATE=50 | 50 | 1250 | 31.03454699610918 | 251 | 12202.541268599998 ms | 14.26s | 0% |
| docker RATE=100 | 100 | 1382 | 31.393902944826444 | 1619 | 35382.616666949994 ms | 37.37s | 1.6642547033285093% |
| docker RATE=200 | 200 | 1658 | 34.25218430532111 | 4342 | 28946.250961499998 ms | 36.39s | 2.3522316043425815% |
| docker RATE=400 | 400 | 2122 | 37.373993875760156 | 9878 | 36016.938774149996 ms | 38.38s | 0.5183788878416589% |
| host RATE=200 | 200 | 2052 | 50.32880011137087 | 3949 | 30003.4303 ms | 31.01s | 0.6335282651072125% |
| in-network RATE=200 (diagnostic) | 200 | 6001 | 200.0151133432792 | 0 | 0.659167 ms ¹ | 1.58ms ¹ | 0% |
| in-network RATE=400 (diagnostic) | 400 | 12001 | 400.00911444815813 | 0 | 0.588916 ms | 1.1ms | 0% |

¹ The in-network RATE=200 run was done twice. The first run's summary file was overwritten by the second, so its p99 (1.58ms) is from the first run's log and its p95 (0.659167 ms) is from the second run's summary. The second run's own p99 was not logged separately.

Warm-up (RATE=20, 30s, discarded from the table): 600 requests, p95 528.5909521 ms, p99 974.68ms, error rate 0%.

**`docker stats --no-stream`** captured 15 s into each run (one sample per run):

| Container | CPU % (RATE=200) | Mem (RATE=200) | CPU % (RATE=400) | Mem (RATE=400) |
|---|---|---|---|---|
| peakload-admission-1 | 0.14% | 217.6MiB | 0.12% | 218MiB |
| peakload-order-1 | 2.94% | 236.4MiB | 0.14% | 236.7MiB |
| peakload-dispatch-1 | 0.15% | 212.4MiB | 0.23% | 212.8MiB |
| peakload-tracking-1 | 0.21% | 215.8MiB | 1.88% | 216.5MiB |
| peakload-grafana-1 | 1.16% | 364.4MiB | 14.28% | 333.8MiB |
| peakload-prometheus-1 | 0.33% | 35.11MiB | 0.77% | 35.12MiB |
| k6 container | 5.25% | 90.83MiB | 4.35% | 97.41MiB |
| sentinel-demo-control-plane (background) | 10.70% | 1.149GiB | 10.40% | 1.154GiB |

**Reading:**
- **Knee (first rate where p99 exceeds 200ms):** at or below **20 req/s**, the lowest rate tested. At RATE=20, p99 was 1.25s (and 974.68ms in the warm-up at the same rate). So on the Docker Desktop port path the knee is below 20 req/s. Finding its exact position needs runs below 20 req/s.
- **Saturated?** Not the services. Every service container stayed under 3% CPU in the 200 and 400 runs, and memory stayed flat at about 210–240 MiB each. Achieved rate plateaued at about 31–37 req/s while the target went from 50 to 400, and errors and dropped iterations rose. The k6 containers were at 4–5% CPU in those samples.
- **Where the limit is:** the in-network diagnostic held 200 and 400 req/s with zero dropped iterations, zero errors and p99 of about 1 to 2 ms. So the services have headroom through 400 req/s on this VM. The ceiling is on the Docker Desktop port path: k6 errors were `Request Failed ... EOF` through `host.docker.internal:8080`. The host k6 at 200 req/s also hit a ceiling (50 req/s achieved, p99 31s), through `localhost:8080` on the same port path.
- **This command measured the port path, not the service.** The README's baseline command is now the in-network one (see the section above).

### Environment-starved run (2 vCPU VM, first run)

Labeled **environment-starved, 2 vCPU VM**. This was the first run, on the Docker VM before resources were raised. Numbers are unchanged from that run.

Machine: Apple M5 (10 cores), 16 GiB RAM, macOS 26.5.1. Docker engine 29.5.2 running a Linux (Ubuntu 24.04) VM with 2 vCPUs and about 3.8 GiB RAM. The four services, Postgres, Redis, Redpanda, Prometheus and Grafana all ran in that VM. Two unrelated containers (oracle11g, sentinel-demo-control-plane) were also running and using CPU during the test.

Command (one run, after `docker compose up --build` and all four services answered `/api/ping`):

```bash
docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e RATE=200 grafana/k6 run - < loadtest/baseline.js
```

k6 settings: constant arrival rate 200 req/s for 30s against `GET /api/ping` on admission, 50 pre-allocated VUs, max 500.

| Metric | Result |
|---|---|
| Requests completed | 702 (k6 target was 200 req/s × 30s = 6000) |
| Achieved rate | 16.7 req/s |
| Dropped iterations | 5297 |
| p95 latency (`http_req_duration`) | 19.08 s |
| p99 latency (`http_req_duration`) | 21.12 s |
| Error rate (`http_req_failed`) | 0.00% (0 of 702) |
| Threshold `p(99)<200` | **crossed** (k6 exited with a threshold error) |

Reading: on this VM the stack could not sustain 200 req/s. The 2-vCPU VM is shared by all services, Redpanda, Prometheus, Grafana, k6 and two unrelated containers, so this baseline measures this environment, not the service. It is a first run after a cold start (JVMs had been up about five minutes). No 5xx responses were recorded. Prometheus recorded about 730 admission requests in the window, which matches the k6 traffic plus manual checks, so the Grafana panels did move.

### Week 1: order creation baseline (`POST /orders`, in-network, single node)

These are the first measurements of order creation. They are not a benchmark of the design: one order-service instance, one Postgres, one Redpanda broker, all in one local Docker VM. Week 0 runs above are unchanged and still describe the admission `/api/ping` path.

**Machine:** Apple M5 (10 cores), 16 GiB RAM, macOS 26.5.1.

**Docker:** engine 29.5.3 on Docker Desktop (`desktop-linux` context), Linux VM with **6 CPUs and 7.75 GiB RAM** (`docker info`: `cpus=6`, `mem=8322072576`). The VM also ran `sentinel-demo-control-plane` (a kind node, not part of this project) at about 10–25% of one core during the runs. Redpanda and Postgres were started with the compose file. Admission, dispatch and tracking were **not running**; the order service was the only application service.

**Service settings (unchanged in this step, not tuned):** order service with Hikari `maximum-pool-size: 10`, `connection-timeout: 2000 ms`; relay interval 1 s, batch 100, one send at a time with acks=all, idempotent producer; Postgres 16; Redpanda v24.2.7.

**k6:** `grafana/k6:latest` (v2.4.0) as the compose service `k6`, `--no-deps`, `BASE_URL=http://order:8081`. Script `loadtest/order-create.js`: `constant-arrival-rate`, a fresh random UUID `Idempotency-Key` (k6 `crypto.randomUUID()`) per request, `X-Client-Id: loadtest`, `preAllocatedVUs` 200, `maxVUs` 2000. Thresholds unchanged from `baseline.js`: `http_req_failed rate<0.01` and `http_req_duration p(99)<200`.

**Procedure** (`loadtest/run-order-create.sh`, outputs in `loadtest/results/order-create/week1-20261121-1418/`): before each run, wait until the outbox backlog is 0 and TRUNCATE `orders, idempotency_keys, outbox, processed_events`. A 30 s warm-up at 20 req/s was discarded. Then 30 s runs at 50, 100, 200, 400, 800, and in steps of 400 above that. The knee was then repeated at the rate just below it and at the knee. `loadtest/sample.py` recorded `docker stats`, `pg_stat_activity` and ungranted locks, the Hikari gauges, and the outbox backlog and oldest-unpublished age every ~4–5 s, including the time after each run until the outbox drained.

**Run ladder.** The first pass stopped at a cap of 4000 req/s, which I had set myself, and no threshold had failed. The ladder was then extended from 4400 in the same results folder, with the cap raised to 6400. The 5600 req/s run failed; the repeat at 5600 failed too, and 5200 passed twice.

**Workload note.** The Week 1 runs sent order bodies without pickup coordinates. Since Week 2 step 2, `POST /orders` requires `pickupLat` and `pickupLon`, and `loadtest/order-create.js` sends them. The numbers below are from the earlier body; they are not a re-measurement with the new one.

**Table** (generated by `loadtest/summarise.py` from k6's JSON summary and the samples; percentiles are k6's, rounded to 2 decimals; `Relay drain s` is measured from the k6 container's exit, so it includes a second or so of teardown):

| Run | Target req/s | Achieved req/s | Dropped iter. | p50 ms | p95 ms | p99 ms | Error rate | p99<200 | Hikari active peak | Hikari pending peak | PG CPU mean/peak % | Order CPU mean/peak % | Outbox backlog end | Outbox backlog peak | Relay drain s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| r0050 | 50 | 50.02 | 0 | 2.67 | 4.24 | 4.99 | 0% | pass | 0 | 0 | 4.38/5.07 | 22.58/29.17 | 51 | 51 | 1 |
| r0100 | 100 | 100.01 | 0 | 1.63 | 3.25 | 3.80 | 0% | pass | 2 | 0 | 6.50/8.19 | 19.76/29.74 | 85 | 103 | 0 |
| r0200 | 200 | 200.02 | 0 | 1.04 | 2.45 | 3.03 | 0% | pass | 1 | 0 | 9.31/12.65 | 23.45/38.49 | 7 | 194 | 0 |
| r0400 | 400 | 400.02 | 0 | 0.76 | 1.74 | 8.11 | 0% | pass | 3 | 0 | 17.99/37.99 | 26.38/53.15 | 202 | 364 | 0 |
| r0800 | 800 | 799.95 | 0 | 0.65 | 1.00 | 1.74 | 0% | pass | 2 | 0 | 23.99/29.78 | 32.23/39.69 | 520 | 780 | 0 |
| r1200 | 1200 | 1199.99 | 0 | 0.57 | 1.02 | 4.76 | 0% | pass | 2 | 0 | 28.25/32.61 | 37.19/44.81 | 441 | 1149 | 1 |
| r1600 | 1600 | 1599.99 | 0 | 0.49 | 0.78 | 2.04 | 0% | pass | 3 | 0 | 40.50/50.22 | 43.05/53.02 | 1252 | 1446 | 1 |
| r2000 | 2000 | 1999.05 | 0 | 0.44 | 0.72 | 1.89 | 0% | pass | 2 | 0 | 50.58/56.69 | 59.14/68.80 | 1371 | 1482 | 0 |
| r2400 | 2400 | 2399.66 | 0 | 0.47 | 0.77 | 1.91 | 0% | pass | 6 | 0 | 57.12/72.43 | 65.54/84.89 | 931 | 2079 | 0 |
| r2800 | 2800 | 2799.92 | 0 | 0.51 | 1.00 | 3.39 | 0% | pass | 5 | 0 | 71.96/96.93 | 83.02/117.64 | 438 | 2879 | 0 |
| r3200 | 3200 | 3199.98 | 0 | 0.59 | 1.40 | 5.25 | 0% | pass | 6 | 0 | 100.03/107.55 | 119.81/134.14 | 860 | 1587 | 0 |
| r3600 | 3600 | 3581.38 | 552 | 0.69 | 2.45 | 24.02 | 0% | pass | 7 | 0 | 106.19/114.41 | 125.29/139.42 | 194 | 3492 | 0 |
| r4000 | 4000 | 3996.58 | 0 | 0.82 | 3.87 | 20.45 | 0% | pass | 9 | 0 | 120.57/128.11 | 137.63/152.61 | 1639 | 3888 | 2 |
| r4400 | 4400 | 4399.77 | 0 | 0.71 | 1.82 | 7.98 | 0% | pass | 10 | 0 | 122.05/131.70 | 129.74/138.17 | 1880 | 4596 | 1 |
| r4800 | 4800 | 4758.12 | 1251 | 0.93 | 13.29 | 89.43 | 0% | pass | 9 | 191 | 135.82/141.28 | 148.44/158.43 | 17723 | 17723 | 4 |
| r5200 | 5200 | 5193.93 | 148 | 1.37 | 19.42 | 48.79 | 0% | pass | 10 | 32 | 169.10/192.80 | 170.38/175.61 | 51817 | 51817 | 10 |
| rep-r5200 | 5200 | 5127.28 | 2179 | 1.62 | 70.61 | 153.37 | 0% | pass | 10 | 191 | 160.44/168.36 | 165.27/172.53 | 50007 | 50007 | 11 |
| r5600 | 5600 | 5171.54 | 11047 | 86.69 | 404.45 | 476.70 | 0% | FAIL | 10 | 191 | 169.61/177.12 | 177.61/193.41 | 75806 | 75806 | 16 |
| rep-r5600 | 5600 | 5473.56 | 3407 | 5.00 | 153.16 | 217.39 | 0% | FAIL | 10 | 191 | 168.28/170.65 | 171.86/177.41 | 69528 | 69528 | 13 |
| r6000 | 6000 | 5873.74 | 3663 | 8.14 | 159.80 | 222.03 | 0% | FAIL | 10 | 191 | 172.29/176.85 | 174.96/180.15 | 77888 | 77888 | 16 |
| r6400 | 6400 | 6307.11 | 2589 | 22.84 | 121.82 | 168.36 | 0% | pass | 10 | 191 | 179.17/186.82 | 180.10/186.48 | 93384 | 93384 | 19 |

**Knee.** The lowest rate where the threshold fails is **5600 req/s**. The first 5600 run failed at p99 476.7 ms with 11,047 dropped iterations, and the repeat failed at p99 217.4 ms with 3,407 dropped iterations. The rate just below the knee, **5200 req/s**, passed both times (p99 48.8 ms and 153.4 ms), so the true knee is between 5200 and 5600 and run-to-run variation at this point is large. Note that the first 5600 run hit k6's VU ceiling (`vus_max` 2000), so part of its drops is k6's; the repeat peaked at 877 VUs and is the cleaner evidence.

**What ran out first (evidence, not proof).**
- **Hikari pool.** All 10 connections were busy from 4400 req/s (`hikari active peak` 10). Requests started queuing for a connection at 4800 (`hikaricp_connections_pending` peak 191). Mean connection hold time rose from 0.20 ms at 4000 to 1.22 ms at 5600. No acquire timeout fired (`hikaricp_connections_timeout_total` stayed 0), because the queue cleared before the 2 s limit.
- **Relay.** The oldest unpublished row was 1.0 s old up to 4400 req/s, and 3.7 s at 4800, 10 s at 5200, 14.4 s at 5600. So the relay also fell behind starting at about 4800. Its backlog peaked at 17,723 rows at 4800, 51,817 at 5200 and 75,806 at 5600.
- **CPU.** No single container was saturated on its own. Peak per-container CPU at 5600 was about 193% for the order JVM, 177% for Postgres, 133% for k6, 25% for Redpanda, 23% for sentinel and 2% for Redis. The sum of these peaks (about 550% of 600%) is an upper bound, since the peaks did not coincide, so the 6-vCPU VM was close to its limit but the data does not identify CPU as the first limit.
- **Postgres locks.** No lock waits and no ungranted locks were seen in any run.

The request-side knee and the relay's drain are separate. Up to about 4400 req/s the relay kept up. Above that it did not, and the backlog grew during each run. The drain times after each run were 10 s at 5200, 16 s at 5600 and 19 s at 6400. Dividing each peak backlog by its drain time gives roughly 4,400–5,200 rows/s. That is a derived estimate, not a direct throughput measurement.

**Above the knee.** At 6000 and 6400 req/s the p99 values were 222.0 ms and 168.4 ms. The 6400 run passed the threshold, but only because k6 completed 6,307 req/s and dropped 2,589 iterations. Dropped iterations are not part of the threshold, so a pass here means no slow *completed* requests, not that the target rate was held. Read the table with the dropped-iterations column next to the p99 column.

**What this does not show.**
- It is one service instance on one local Docker VM. Nothing here is a capacity estimate for a deployment.
- k6, Postgres, Redpanda, the order JVM and the sentinel kind node share the same 6 vCPUs, so each number is affected by the others' load.
- The load is uniform: every request is a new key and a new order. A real mix, retries and replays would change the cost per request.
- Every run used a fresh, truncated database, so cache state and table size are controlled, but they are not those of a long-running system.
- The sampler records about every 4–5 s, so short spikes between samples are not visible, and the outbox age is a sampled value.
- Hikari pool size, indexes and the relay were not changed in this step. The numbers above are the baseline before any tuning.
- Each knee-area rate was run twice. Other rates were run once, so their variation is unknown.

### Week 3: admission baseline (no limiting)

Admission now sits in front of the order service: `POST /orders` goes through it, with no rate limiting or shedding yet. The question is what the extra hop costs, not how the stack behaves under overload.

**Machine and VM:** the same as the Week 1 runs: Apple M5 (10 cores), 16 GiB RAM, Docker Desktop, Linux VM with 6 vCPUs and about 7.75 GiB RAM. Order and admission are containers in that VM, next to Postgres, Redpanda, Redis and k6, which share the same 6 vCPUs.

**Admission configuration (not tuned):** blocking `RestClient` on virtual threads, over an Apache HttpClient 5 pool of at most 200 connections. Connect timeout 500 ms, read timeout 2 s, pool wait 500 ms, no retries. Pass-through: `Idempotency-Key`, `X-Client-Id`, `If-Match` and `Content-Type` go upstream; status, body, `Idempotent-Replay`, `Location` and `ETag` come back.

**Commands:** the same procedure as Week 1 (warm-up at 20 req/s discarded, outbox drained and tables truncated before each run, one 30 s run per rate):

```bash
# direct to the order service (in-network)
OUT=loadtest/results/week3-direct2-<stamp> TARGET_URL=http://order:8081 RATES="200 800 1600" \
  bash loadtest/run-order-create.sh list

# through admission (in-network)
OUT=loadtest/results/week3-admission-<stamp> TARGET_URL=http://admission:8080 RATES="200 800 1600" \
  bash loadtest/run-order-create.sh list
```

k6 version v2.4.0, `loadtest/order-create.js`, thresholds unchanged. The order body includes pickup coordinates, as since Week 2. Percentiles come from k6's JSON summary; CPU from `docker stats` every ~4–5 s during each run.

**Results** (the warm direct campaign was run after the admission campaign, once the order JVM had warmed up; see the cold-start note below):

| Path | Target req/s | Achieved req/s | Dropped iter. | p50 ms | p95 ms | p99 ms | Errors | Order CPU % mean / peak | Admission CPU % mean / peak |
|---|---|---|---|---|---|---|---|---|---|
| Direct to order (warm) | 200 | 200.0 | 0 | 1.06 | 2.30 | 2.74 | 0.00% | 15.2 / 18.5 | n/a |
| Direct to order (warm) | 800 | 800.0 | 0 | 0.63 | 1.06 | 1.67 | 0.00% | 26.8 / 33.9 | n/a |
| Direct to order (warm) | 1600 | 1600.0 | 0 | 0.56 | 0.97 | 1.82 | 0.00% | 53.8 / 62.5 | n/a |
| Through admission | 200 | 200.0 | 0 | 1.09 | 2.47 | 4.41 | 0.00% | 11.4 / 18.4 | 23.4 / 43.0 |
| Through admission | 800 | 800.0 | 0 | 0.70 | 1.20 | 2.36 | 0.00% | 27.9 / 34.5 | 22.4 / 35.8 |
| Through admission | 1600 | 1599.9 | 0 | 0.69 | 1.58 | 5.10 | 0.00% | 52.2 / 63.6 | 28.9 / 39.4 |

**Added latency of the hop** (warm direct against admission, same rate; one run each):

| Target req/s | p50 added by admission (ms) | p99 difference (ms, one run each) |
|---|---|---|
| 200 | +0.03 | +1.67 |
| 800 | +0.08 | +0.69 |
| 1600 | +0.13 | +3.28 |

**First direct campaign, cold order JVM.** This campaign ran before the others, right after the order container was rebuilt, so its order CPU includes JIT compilation. It is kept for transparency and should not be used for the comparison:

| Path | Target req/s | Achieved req/s | Dropped iter. | p50 ms | p95 ms | p99 ms | Errors | Order CPU % mean / peak | Admission CPU % mean / peak |
|---|---|---|---|---|---|---|---|---|---|
| Direct to order (first run, cold JVM) | 200 | 200.0 | 0 | 0.83 | 2.24 | 4.23 | 0.00% | 31.0 / 41.2 | n/a |
| Direct to order (first run, cold JVM) | 800 | 800.0 | 0 | 0.62 | 1.16 | 3.78 | 0.00% | 36.3 / 54.8 | n/a |
| Direct to order (first run, cold JVM) | 1600 | 1600.0 | 0 | 0.58 | 1.27 | 6.21 | 0.00% | 56.3 / 65.6 | n/a |

**What this shows.**
- At 200, 800 and 1600 req/s, admission added about 0.03–0.13 ms at the median. No request was dropped and no request failed in any run.
- The p99 differences (+0.7 to +3.3 ms) come from single runs and do not show a consistent trend. The same direct rate varied a lot between campaigns: 1600 req/s gave a p99 of 1.82 ms in the warm campaign and 6.21 ms in the cold one. So this baseline cannot establish an added p99 cost.
- Admission used about 22–29% of one core on average and up to 43% at its peak, at 200–1600 req/s. Order CPU was about the same through admission as direct, so the hop costs admission CPU rather than order CPU.

**What this does not show.**
- Nothing about saturation. The rates stopped at 1600 req/s, which is well below the direct knee measured in Week 1 (between 5200 and 5600 req/s). Admission's own ceiling was not measured.
- Single runs per rate for the admission and warm direct campaigns, apart from the knee-area runs in Week 1. The p99 values carry real noise; the order of these numbers should not be read as a ranking.
- One machine and one local VM. k6, both services and the databases share 6 vCPUs, so every number reflects that shared load.
- No limiting, shedding or retries. Those behaviours will change the picture under overload and are not part of this measurement.

### Week 3: rate limiter

Admission now limits requests per client and per class, using a token bucket in Redis. A request over its limit gets HTTP 429 and never reaches the order service. Limiter overhead and one noisy-neighbour scenario are measured below. Everything here is a single local VM with a simulated abusive client; nothing is a capacity claim.

**Limits used for the tests (chosen for the test, not recommended):**
- The defaults in `application.yml` are placeholders: CRITICAL 200 burst / 100 per second, STANDARD 200 / 100, LOW 100 / 50. They were picked so the code runs, not measured.
- Overhead campaign, limiter on: CRITICAL 10,000,000 burst and refill, so nothing is rejected. The rejected-request counter stayed at 0 in every run.
- Noisy neighbour, limiter on: CRITICAL 100 burst / 50 per second. A normal client at 20 req/s is never limited; the abusive client at 1200 req/s is.

**How the limiter works.** One Lua script does refill, check and take in a single atomic Redis call. Time comes from `redis.call('TIME')`, not from the application. The key `rl:{clientId}:{class}` holds tokens and a timestamp, and expires after about twice the time to refill from empty. The client is the `X-Client-Id` header, or the remote address if that is missing. `X-Forwarded-For` is not trusted. A Redis error or a 50 ms timeout allows the request (fail open) and is counted in `admission_limiter_errors_total`.

**Tests.** The script tests run against a Testcontainers Redis 7: the burst allows exactly the capacity; refill returns after the right interval; the retry-after value matches the next token; keys and classes are independent; the key has an expiry; and 200 threads against one bucket with capacity 50 let exactly 50 through. Integration tests cover the 429 with `Retry-After` and no upstream call, a second client that is unaffected, a rejected order that creates nothing, and a retry with the same key that succeeds, plus fail-open with Redis on a closed port.

#### Overhead of the limiter (same procedure as Week 3 step 1; limiter off and on in the same session)

Admission was restarted between the two campaigns, with the same warm-up (discarded) and tables reset before each 30 s run.

| Rate req/s | Limiter | Achieved req/s | Dropped iter. | p50 ms | p95 ms | p99 ms | Errors | Admission CPU % mean / peak | Limiter round trip p50 / p95 / p99 ms |
|---|---|---|---|---|---|---|---|---|---|
| 200 | off | 200.0 | 0 | 1.13 | 2.69 | 6.18 | 0.00% | 19.7 / 40.6 | n/a |
| 200 | on | 200.0 | 0 | 1.21 | 2.37 | 4.04 | 0.00% | 25.2 / 44.7 | 0.50 / 0.95 / 0.99 |
| 800 | off | 800.0 | 0 | 0.72 | 1.55 | 4.85 | 0.00% | 26.5 / 43.1 | n/a |
| 800 | on | 800.0 | 0 | 0.81 | 2.41 | 35.12 | 0.00% | 34.8 / 52.5 | 0.51 / 0.97 / 4.27 |
| 1600 | off | 1599.7 | 8 | 0.70 | 2.69 | 30.02 | 0.00% | 34.7 / 56.6 | n/a |
| 1600 | on | 1600.0 | 0 | 0.84 | 2.80 | 16.93 | 0.00% | 40.9 / 49.0 | 0.51 / 0.96 / 1.57 |

The limiter's own round trip (`admission_limiter_seconds`) had a median of about 0.5 ms and a p95 of about 1.0 ms at every rate. Its sample count equalled the number of requests. Admission CPU was about 5–6 points higher on average with the limiter on. The median latency rose by about 0.1 ms at 200 and 800 req/s and by 0.14 ms at 1600 req/s.

**What this shows and does not show.**
- The median cost is small and consistent: about 0.5 ms for the Redis round trip, and about 0.1 ms more at the median for the whole request.
- The p99 values move in both directions with no consistent pattern (for example at 800 req/s: 4.85 ms off, 35.12 ms on; at 1600 req/s: 30.02 ms off, 16.93 ms on, with 8 dropped iterations in the off run). One run per rate cannot show a p99 difference of this size. The overhead at the tail is not established.
- k6, admission, the order service and Redis share six vCPUs on one VM, so every number includes that shared load.

#### Noisy neighbour (one run each; limiter on, then off)

Five normal clients (`normal-1` to `normal-5`) each send 20 req/s of `POST /orders` with their own `X-Client-Id`. One abusive client (`abuser`) sends 1200 req/s, with the same 30 s duration. Limits are the test values above. Results are split by group from k6's per-request points. "Accepted" means HTTP 201.

| Group | Limiter | Clients | Achieved req/s | Accepted (201) | Share 429 | p99 accepted ms | Errors | Dropped iter. (k6, whole run) |
|---|---|---|---|---|---|---|---|---|
| normal | on | 5 at 20 req/s each | 100.1 | 3004 of 3004 | 0.0% | 120.35 | 0 | 73 |
| abusive | on | 1 at 1200 req/s | 1197.6 | 1588 | 95.6% | 362.09 | 0 | 73 |
| normal | off | 5 at 20 req/s each | 100.2 | 3005 of 3005 | 0.0% | 245.60 | 0 | 61 |
| abusive | off | 1 at 1200 req/s | 1198.0 | 35940 | 0.0% | 210.35 | 0 | 61 |

**Reading.**
- With the limiter on, the abusive client was limited as the model predicts: a burst of 100 plus 50 per second for 30 s allows about 1,600 requests, and 1,588 were accepted.
- The normal clients were never limited (0 of 3,004 requests got 429), and their accepted-request p99 was 120 ms with the limiter on and 246 ms with it off, in this single pair of runs.
- The normal clients' p50 was higher with the limiter on (1.87 ms vs 1.12 ms), which is the limiter's round trip showing up on every request.
- A first pair of noisy runs, before the per-group points output was added, gave the same picture in its totals (34,374 requests got 429 with the limiter on; none with it off). Its per-group split was not captured, so it is not in the table.

**What this does not show.**
- The normal-client p99 difference (120 ms vs 246 ms) comes from one run per condition. The overhead campaign showed p99 varying by tens of milliseconds between runs at the same rate, so this comparison is not established.
- Part of the difference could come from load: with the limiter off, all 1200 abusive requests per second reach admission and the order service. With it on, most are answered with 429 before any upstream call. The limit is not the only thing that changed.
- k6 generates the abusive load on the same six vCPUs, and k6 dropped iterations in both runs (73 and 61). A dropped iteration means k6 started fewer requests than scheduled; I did not isolate whether k6 or the stack caused it. The achieved abusive rate was 1198 req/s in both runs.
- One client identity per group, one VM, no real network, and limits chosen for this test.

### Redis outage behaviour

**Single run per condition, on one local VM, with a simulated abusive client.** The setup is the Step 2 noisy-neighbour scenario (five normal clients at 20 req/s each, one abusive client at 1200 req/s) for 90 s. Limits: CRITICAL 100 burst, 50/s. Redis goes down at +30 s and comes back at +60 s. Local limits equal the Redis limits (factors 1.0). Numbers are from one run each, so differences of tens of milliseconds are not established.

**What the limiter does during an outage.** A Redis error or timeout decides that request from a local in-memory bucket with the same key and limit. It also opens a circuit: for 2 s, requests use the local buckets and Redis is not called. After that, one probe goes to Redis. If the probe succeeds, the limiter returns to Redis. If it fails, the circuit opens for another 2 s. Mode changes are logged once each way.

**Run A: Redis stopped (`docker compose stop`), breaker on.** Stopping gives an immediate connection refusal.

**Run B: Redis paused (`docker compose pause`), breaker on.** Paused Redis accepts no commands, so every call waits for the 50 ms command timeout until the circuit opens.

**Run C: Redis paused, breaker off (`RATE_LIMIT_BREAKER_ENABLED=false`).** Every request calls Redis and waits for the timeout; a failure only affects that request.

Each run is split by phase. The outage phase is measured from the times the driver recorded. Switch times come from the limiter's mode gauge (sampled every 0.5 s) and from the admission log, which agree within a second.

#### Run A: Redis stopped, breaker on

| Phase | Group | Requests | Accepted (201) | 429 | Share 429 % | Errors | p50 accepted ms | p99 accepted ms |
|---|---|---|---|---|---|---|---|---|
| Redis up | normal | 2985 | 2985 | 0 | 0.00 | 0 | 2.13 | 245.07 |
| Redis up | abusive | 35712 | 1578 | 34134 | 95.58 | 0 | 1.54 | 503.60 |
| Redis down | normal | 3035 | 3035 | 0 | 0.00 | 0 | 1.81 | 9.08 |
| Redis down | abusive | 36423 | 1616 | 34807 | 95.56 | 0 | 1.40 | 20.92 |
| After recovery | normal | 2983 | 2983 | 0 | 0.00 | 0 | 1.82 | 4.73 |
| After recovery | abusive | 35809 | 1592 | 34217 | 95.55 | 0 | 1.28 | 4.59 |

k6 dropped iterations (whole run): 57. Mode switch to local: 0.51 s after the outage (sampler); back to Redis: 5.16 s after recovery (sampler). Circuit opens this run: 17; limiter errors this run: 36; fallback decisions this run: allowed 5326, limited 39982.

#### Run B: Redis paused, breaker on

| Phase | Group | Requests | Accepted (201) | 429 | Share 429 % | Errors | p50 accepted ms | p99 accepted ms |
|---|---|---|---|---|---|---|---|---|
| Redis up | normal | 2985 | 2985 | 0 | 0.00 | 0 | 1.77 | 16.95 |
| Redis up | abusive | 35794 | 1785 | 34009 | 95.01 | 0 | 1.22 | 30.05 |
| Redis down | normal | 3015 | 3015 | 0 | 0.00 | 0 | 1.91 | 8.99 |
| Redis down | abusive | 36161 | 1604 | 34557 | 95.56 | 0 | 1.34 | 52.33 |
| After recovery | normal | 3003 | 3003 | 0 | 0.00 | 0 | 1.73 | 4.55 |
| After recovery | abusive | 36047 | 1557 | 34490 | 95.68 | 0 | 1.16 | 3.59 |

k6 dropped iterations (whole run): 0. Mode switch to local: 0.23 s after the outage (sampler); back to Redis: 0.93 s after recovery (sampler). Circuit opens this run: 16; limiter errors this run: 91; fallback decisions this run: allowed 5131, limited 37665.

#### Run C: Redis paused, breaker off

| Phase | Group | Requests | Accepted (201) | 429 | Share 429 % | Errors | p50 accepted ms | p99 accepted ms |
|---|---|---|---|---|---|---|---|---|
| Redis up | normal | 2980 | 2980 | 0 | 0.00 | 0 | 1.78 | 217.52 |
| Redis up | abusive | 35652 | 1579 | 34073 | 95.57 | 0 | 1.30 | 475.09 |
| Redis down | normal | 3010 | 3010 | 0 | 0.00 | 0 | 52.27 | 54.70 |
| Redis down | abusive | 36093 | 1604 | 34489 | 95.56 | 0 | 52.01 | 55.95 |
| After recovery | normal | 3014 | 3009 | 5 | 0.17 | 0 | 1.63 | 6.08 |
| After recovery | abusive | 36151 | 1504 | 34647 | 95.84 | 0 | 1.16 | 5.43 |

k6 dropped iterations (whole run): 105. Mode switch to local: n/a s after the outage (sampler); back to Redis: 0.51 s after recovery (sampler). Circuit opens this run: 0; limiter errors this run: 39279; fallback decisions this run: allowed 4633, limited 34646.


**Summary of the switches.**
- **Run A (stop):** the limiter switched to local 0.51 s after Redis stopped (log: 0.20 s) and back to Redis 5.16 s after it returned (log: 4.68 s). The return took longer than the 2 s cool-down because Lettuce needed to reconnect after the container started, and each probe until then failed.
- **Run B (pause):** switched to local 0.23 s after the pause (log: 0.12 s) and back 0.93 s after the unpause (log: 0.72 s).
- **Run C (pause, breaker off):** no circuit, so no switch. Every request in the outage waited for the timeout.

**What the numbers show.**
- **Limits hold during the outage.** The abusive client was still limited to about 95.6% 429s in every outage phase, and it was accepted about 1,600 times per 30 s phase, the same as with Redis up. This is one instance, so the local limit equals the Redis one.
- **Normal clients are not refused during the outage.** The normal clients had no 429s in the outage phase in any run. Their accepted p99 in that phase was 9 ms (run A), 9 ms (run B) and 55 ms (run C, breaker off). Their 429s after recovery in run C are discussed below.
- **The breaker removes the latency cost.** With Redis paused and the breaker off, the median accepted request in the outage was 52 ms and the p99 was 55 ms. With the breaker on, the same outage gave a median of 1.9 ms and a p99 of 9 ms (run B). The timeout is paid once per cool-down, not on every request.
- **Errors are counted, not hidden.** The limiter recorded 36 errors in run A, 91 in run B and 39,279 in run C. Those are Redis calls that failed, each answered locally. No request got a 5xx.

**Things I did not explain or did not isolate.**
- **Warm-up in the first phase.** The "Redis up" phase p99 was high in runs A and C (217–503 ms), while the later phases of the same runs were 4–9 ms. The admission JVM was freshly started for these runs, so I attribute the high p99 to warm-up. Run B reused run A's JVM and its "Redis up" p99 was 17–30 ms, which supports that reading. It is not proven by these runs.
- **Transient timeouts in run B.** Two calls to Redis timed out about 22 s into run B, before the pause. Each switched to local and back within about 2 s. Those are counted in its "Redis up" phase.
- **Five normal 429s in run C after recovery.** Five of 3,014 normal requests were limited in the first 30 s after Redis returned, and none in the other runs. Normal traffic is 20 req/s against a 50/s refill and 100 burst, so these should not happen. I did not investigate this one.
- **k6 dropped iterations.** 57 (run A), 0 (run B) and 105 (run C). The drop counts are for the whole run, and I did not isolate whether k6 or the stack caused them.
- **Shared JVM.** Runs A and B reused one admission process, because Compose did not recreate the container when its settings were unchanged. Run C started a new process. The counters in the tables are per-run deltas.

**Limits of this setup.** One instance, so the "up to N times the configured limit" effect is not measured. The local buckets are per process, so a second instance would have its own. The cap on local buckets (50,000 keys by default) and eviction were not exercised by this load.

## Week 3: load shedding

Admission now sheds load by class when the system is busy. Order of shedding: LOW first (ELEVATED), then STANDARD (HIGH), then CRITICAL too at the hard cap (HARD_CAP, rejected at once, not queued). A shed request gets `503` with `Retry-After` and a body with `"error": "shed"`, the class and the level. It is counted in `admission_shed_total{class}` and `admission_requests_total{outcome="shed"}`. It never reaches the order service or the menu table. This is separate from the `429` rate limit, which is per client.

**The thresholds are unmeasured starting values.** They are in `application.yml` under `peakload.admission.shed` and are marked as such there. The measurement below shows what this load did with them; it did not tune them.

- In-flight is counted across all classes, including local menu reads, as a fraction of the upstream pool (200 connections). Enter/exit: ELEVATED 0.50/0.40, HIGH 0.75/0.60, HARD_CAP 0.95/0.85.
- Smoothed order-service latency (EWMA, weight 0.2), counting timeouts. Enter/exit: ELEVATED 250/150 ms, HIGH 1000/600 ms, HARD_CAP 2500/1500 ms. The value halves every 5 s with no upstream calls, so it can recover while shedding.
- Each signal has its own level with hysteresis. The overall level is the higher of the two. `SHED_ENABLED=false` turns shedding off. The level is still computed for the gauge.

### Setup

- One VM: Docker Desktop with 6 vCPUs and 8 GB. Shared with k6, Postgres, Redis, Redpanda, Prometheus and Grafana. Single admission and single order instance.
- **Test assumptions, not real traffic.** CRITICAL `POST /orders` at 800 req/s, STANDARD `GET /orders/{id}` at 50 req/s. LOW `GET` menu at 0 in phase 1 (calm), then 400, 1200 and 2400 req/s in phases 2, 3 and 4. Four 30 s phases, one warm-up of phase 1 (discarded), tables truncated before each run, admission restarted before each run.
- **Why CRITICAL at 800 req/s.** Week 1 passed direct `POST /orders` up to 5200 req/s. Week 3 Step 1 passed admission at 200, 800 and 1600 req/s with no drops. 800 is inside the range Step 1 passed cleanly, well below the Week 1 knee. A confirm run first: CRITICAL alone at 800 req/s, shedding off, passed (96,004 requests, all accepted, no dropped iterations, p99 2.8–3.5 ms per phase).
- **LOW ramp.** A pilot (shedding off, one run, not in the table below) ran the ramp to pick the LOW rates. At 1200 req/s of LOW, CRITICAL p99 went from 5.5 ms to 740 ms and k6 dropped 1,113 iterations. The final ramp is the pilot's, unchanged.
- The rate limiter was on but set to 1,000,000 req/s per class, so it did not limit this load. Its limit was not what was measured.
- k6 reports only (no thresholds). Dropped iterations are k6's count, and a dropped iteration means k6 started fewer requests than scheduled. I did not isolate whether k6 or the stack caused them.

### Results (per phase; numbers from `loadtest/shed_report.py`)

Phase 3 is LOW at 1200 req/s, phase 4 is LOW at 2400 req/s. "Accepted" means HTTP 200 or 201. In shedding-off runs, every 503 is an upstream pool timeout from admission (shed counter zero). Shedding-off HTTP 500s on LOW were not counted by admission's outcome counters. The cause is now confirmed: the menu's connection pool (10 connections) was full, and a menu read waited 2 s for a connection and then failed (`SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 2008ms`). Spring turned that into a 500. Reproduced with the mixed load and shedding off; LOW alone at 2400 req/s did not fail. Admission's Hikari acquire max was 4.39 s in shedding-off run 2 and 0.10 s in shedding-on run 2. The fix (menu pool timeout answered 503 with `menu_unavailable`, counted as outcome `database_unavailable`) is in the code but was not re-measured.

**Phase 4 (LOW 2400 req/s), all six runs.**

| Shedding | Run | CRITICAL achieved req/s | CRITICAL accepted % | CRITICAL p50 / p95 / p99 ms | LOW accepted % | LOW p99 ms | STANDARD p99 ms | k6 dropped iter. | Shed CRITICAL / STANDARD / LOW |
|---|---|---|---|---|---|---|---|---|---|
| off | 1 | 689.0 | 93.65 (503: 6.35) | 10.33 / 2245.59 / 3380.45 | 87.74 (500: 6,202) | 2935.55 | 3286.61 | 24,842 | 0 / 0 / 0 |
| off | 2 | 655.3 | 93.45 (503: 6.55) | 502.97 / 3057.5 / 4082.75 | 45.48 (500: 19,397) | 6483.56 | 4118.86 | 40,917 | 0 / 0 / 0 |
| off | 3 | 800.0 | 100.0 | 1.45 / 5.79 / 20.06 | 100.0 | 24.54 | 16.48 | 508 | 0 / 0 / 0 |
| on | 1 | 763.6 | 95.94 (503: 4.06) | 1.6 / 24.34 / 132.13 | 93.28 (503: 6.72) | 45.23 | 51.93 | 4,707 | 930 / 90 / 4,601 |
| on | 2 | 800.0 | 100.0 | 1.47 / 11.86 / 52.6 | 98.33 (503: 1.67) | 30.72 | 38.73 | 0 | 0 / 9 / 1,368 |
| on | 3 | 800.0 | 98.2 (503: 1.8) | 1.39 / 12.9 / 280.29 | 96.35 (503: 3.65) | 37.44 | 65.08 | 0 | 453 / 49 / 3,070 |

**Phase 3 (LOW 1200 req/s), CRITICAL p99 ms:** shedding off 10.27, 131.94, 420.28; shedding on 10.48, 25.37, 77.23. Phase 3 CRITICAL accepted was 100% in every run except shedding-on run 3 (99.92%).

**What this shows.**
- **Shedding fired in the order it is meant to.** LOW was shed in every shedding-on run, STANDARD in every shedding-on run (9 to 90 requests), and CRITICAL only in runs 1 and 3 (930 and 453 requests, all at HARD_CAP or HIGH bursts). I did not time the shed responses separately.
- **The effect on CRITICAL is not consistent across runs.** CRITICAL p99 in phase 4 was 3,380 and 4,082 ms with shedding off in runs 1 and 2, and 20 ms in run 3. With shedding on it was 132, 53 and 280 ms. Run 3 with shedding off was the best CRITICAL run of all six, so the direction holds in two of three pairs and reverses in the third. Three runs per condition cannot separate the effect from run-to-run variation.
- **Shedding-off runs failed requests.** CRITICAL got 503s from admission's upstream pool (`pool_timeout` and `upstream_error`) and LOW got HTTP 500s, in runs 1 and 2 only. Shedding-on runs had fewer failures in phase 4. In shedding-on runs 1 and 3, the CRITICAL 503s are the shed count (930 and 433 in phase 4, plus 20 in phase 3 for run 3, which gives the 453 counted).
- **The load level changed briefly.** Shedding-on run 3 (sampled every 0.5 s): HIGH at 79.4 s (in-flight 137, EWMA 27 ms), back to NORMAL 0.5 s later, then HARD_CAP at 119.5 s (in-flight 186, EWMA 79 ms), back to NORMAL 0.6 s later. Most short bursts were shorter than the sampling interval, so this timeline is a lower bound. The shed counters are exact. These bursts line up with order-service stalls. Shedding-on runs 1 and 2 have no level timeline: the sampler had a bug, fixed below, and its output for those runs is blank.
- **Phase 2 in shedding-on run 2 shows a stall.** CRITICAL p99 was 115 ms in phase 2, and 1.2% of LOW was shed (ELEVATED). I did not isolate the cause.

**Things that went wrong during the work, and what I did about them.**
- The EWMA faded during a slow call's own duration, so back-to-back slow calls decayed their own value. The integration test caught it; it is fixed before any measurement, and the unit test still passes.
- The shedding-on runs 1 and 2 have no level timeline. The sampler matched metric names without their label set. It was fixed and checked against live admission, and shedding-on run 3 was run again with the fixed sampler. Runs 1 and 2 are kept in `loadtest/results` with their k6 and counter data, which were not affected.
- Admission's total in-flight gauge exports as `admission_in_flight` with no `class` label, because Micrometer drops the `.total` suffix. The per-class gauges have a `class` label.
- With shedding off, the level gauge was not updated. Fixed after these runs, and covered by a unit test. The shedding-off runs' k6 data are unaffected.
- The order module's tests stopped on a JDK change (Java 27 with an older Byte Buddy). They pass with `-DargLine=-Dnet.bytebuddy.experimental=true`. That changes the test JVM only.

**Limits.** One VM, one admission and one order instance. Numbers are for this request mix and these rates, which are assumptions. Three runs per condition is not enough to measure a p99 difference of this size. The shedding thresholds were not tuned. A second admission instance would have its own in-flight count and EWMA.

## Week 3: New Year's Eve curve

A 300 s open-loop curve (`loadtest/nye-curve.js`) run six times, shedding off and on alternating (off, on, off, on, off, on), three of each. Same reset, same 30 s warm-up (discarded), no code or threshold change between runs. Per-phase tables for each run are in `report.md` in its folder under `loadtest/results/week3s5-nye-*`.

**Assumptions (test inputs, not real traffic).**
- Shape: calm 0–60 s at base rates; ramp in four 15 s steps (60–120 s); peak 30 s (120–150 s); decline in three steps (150–210 s); calm tail 90 s (210–300 s).
- Rates per class, base → peak: CRITICAL `POST /orders` 200 → 1000 req/s; LOW menu reads 400 → 2400 req/s; STANDARD `GET /orders/{id}` 20 → 100 req/s, 20% of those also advance the order status.
- Peak chosen from earlier results. Step 4 found CRITICAL at 800 req/s passes alone and degrades with LOW at 2400 req/s. The peak is just above the CRITICAL level that passed. Week 1's direct knee for `POST /orders` was 5200–5600 req/s, so the peak is well below it.
- Client ids: about 300 per class, rate limiter at its defaults, no abusive client.
- Dispatch: 9 riders, no rider positions in Redis. Every order was logged `PENDING`, so dispatch made no assignments in any run. The dispatch invariants hold trivially here.

**Per-run summary.** Peak = the 30 s peak phase. "Accepted" is HTTP 200/201. "Shed" is 503 from shedding. Other errors are shown separately.

| Shedding | Run | CRITICAL achieved req/s (target 1000) | CRITICAL accepted % | CRITICAL shed % | CRITICAL p50 / p95 / p99 ms (accepted) | LOW accepted % | Outbox peak backlog | Outbox zero after peak end (s) | k6 dropped iter. | Circuit opens | Created check |
|---|---|---|---|---|---|---|---|---|---|---|---|
| off | 1 | 963.7 | 99.04 | 0 | 4.1 / 700.96 / 1248.99 | 93.79 | 1019 | 4.2 | 21932 | 9 | match |
| off | 2 | 405.9 | 89.16 | 0 | 1327.93 / 6466.32 / 7219.19 | 37.83 | 11919 | 250.5 | 225207 | 7 | match |
| off | 3 | 456.6 | 83.35 | 0 | 1215.68 / 4429.79 / 6354.29 | 34.85 | 3923 | 217.4 | 227086 | 74 | **1 extra row** (see below) |
| on | 1 | 1000.0 | 96.43 | 7.38 | 7.74 / 57.72 / 116.9 | 86.88 | 1187 | 155.2 | 14781 | 9 | match |
| on | 2 | 751.3 | 57.57 | 46.42 | 260.77 / 949.59 / 1643.1 | 14.52 | 895 | 26.9 | 83438 | 39 | match |
| on | 3 | 927.6 | 65.48 | 32.6 | 109.44 / 581.18 / 745.77 | 27.48 | 1278 | 153.8 | 29423 | 17 | match |

**Range across the three runs per condition** (min to max, no averages).

| Metric | Shedding off | Shedding on |
|---|---|---|
| CRITICAL peak p99 ms (accepted) | 1,249 to 7,219 | 117 to 1,643 |
| CRITICAL peak accepted % | 83.4 to 99.0 | 57.6 to 96.4 |
| CRITICAL peak shed % | 0 | 7.4 to 46.4 |
| LOW peak accepted % | 34.9 to 93.8 | 14.5 to 86.9 |
| Outbox peak backlog | 1,019 to 11,919 | 895 to 1,278 |
| Outbox zero after peak end (s) | 4.2 to 250.5 | 26.9 to 155.2 |
| k6 dropped iterations | 21,932 to 227,086 | 14,781 to 83,438 |
| Circuit opens (limiter) | 7 to 74 | 9 to 39 |

**What the runs do and do not show.**
- **Accepted p99 for CRITICAL at the peak was lower with shedding on in all three pairs** (117 vs 1,249 ms, 1,643 vs 7,219 ms, 746 vs 6,354 ms). Shedding turned away CRITICAL requests at HARD_CAP, and the accepted share was lower in every pair.
- **The runs disagree a lot.** Within the shedding-off condition alone, the peak CRITICAL p99 ranged from 1.2 s to 7.2 s, and the outbox peak from 1,019 to 11,919 rows. Run 1 with shedding off was much better than runs 2 and 3. With three runs per condition, the direction is consistent in three pairs but the spread is wide. I do not claim a statistically established effect.
- **Redis timeouts happened in every run.** The limiter switched to local mode and opened its circuit 7 to 74 times per run. The Redis container shares the VM and the load, so this is a confound in both conditions.
- **Failures at the peak (not shedding).** With shedding off, CRITICAL got 503s at the peak (277, 1,320 and 2,281 in runs 1, 2 and 3), most likely admission's upstream pool timeouts; I did not break these down by type in this series. LOW got 503s on the menu path (3,759 to 8,757). These are the menu-pool 503s from the follow-up fix, which I did not separately confirm in these runs.
- **Outbox.** The order outbox drained to zero after the peak in every run, in 4 to 250 s. The drain was slowest in the shedding-off runs 2 and 3 (250 and 217 s), where the backlog peaked at 11,919 and 3,923 rows.
- **Created check.** In every run except shedding-off run 3, the orders in Postgres equal the CRITICAL 201 responses plus the 400 seed orders. Shedding-off run 3 has one extra order in Postgres. The k6 run recorded three CRITICAL requests in the tail phase with status 0, `request timeout`. The most likely explanation is that one of those requests was committed on the server after k6 gave up on it. The key wasn't logged, so I can't tie the row to a specific request.
- **No duplicates.** Every run has zero orders without a key, zero orders on two keys, and zero duplicate (client, key) pairs. Every run has zero unpublished order outbox rows at the end.
- **Dispatch.** The invariant checks (the four queries from `Invariants.java`) hold in every run, but they hold trivially: dispatch made no assignments. The logs show every order `PENDING`, and Redis has no rider position keys, so the likely cause is the missing rider positions.

**Limits.**
- One local VM (6 vCPU, 8 GB) shared by admission, order, dispatch, Postgres, Redis, Redpanda, k6 and the samplers. The load generator competes for the same CPU. Dropped iterations are k6's count; I did not isolate whether k6 or the stack caused them.
- Simulated traffic with assumed rates and mix. Not measured from real demand.
- Three runs per condition. The spread within a condition is larger than the difference between conditions for some metrics.
- Dispatch has 9 riders and no rider positions, so the assignment path was not exercised. A real dispatch load would add Postgres and Redis work that is not in these numbers.
- Redis co-located on the VM, with timeouts during the peak in every run.
- Thresholds are the untuned starting values from Step 4. They were not changed for this series.
- Run-to-run variation at this load on this machine is large. The numbers here describe these six runs only.

**Test harness notes.** The first attempt had all six runs fail at setup: 400 seed orders from one client id hit the rate limit burst. Those runs were discarded and are not in the repository. The seed step now uses one client id per seed.

## Week 4: database baseline

Read-only diagnosis of the order database on a realistic-size dataset. Single run per condition on one local VM (6 vCPU, 8 GB); the workload is the Week 1 create load, not real traffic. No schema or application change. The run counts below are from the result folders named in each row.

**Instrumentation.** `pg_stat_statements` 1.10 is preloaded (`shared_preload_libraries`, `track=all`) in the compose Postgres, and the extension is created by `ops/postgres/init/01-pg-stat-statements.sql` on a fresh volume or `loadtest/db/enable-pg-stat.sh` on an existing one. Cost: a hash and a counter update per statement. I did not measure this overhead, and the comparison with Week 1 is confounded by it.

**Dataset (`loadtest/db/seed-orders.sql`, `loadtest/db/seed.sh`).**
- 1,000,000 orders, created over the last 365 days, weighted to lunch (11–14 h) and dinner (19–22 h). Eight cities, weighted towards Pune and Mumbai.
- Status mix: orders older than 24 h are 95% DELIVERED and 5% CANCELLED. Orders from the last 24 h are CREATED 3%, CONFIRMED 5%, PREPARING 7%, READY 8%, PICKED_UP 12%, DELIVERED 60%, CANCELLED 5%. Measured after the seed: DELIVERED 94.8%, CANCELLED 5.0%.
- One OrderCreated and one OrderStatusChanged per status step, all published. About 5.8 outbox rows per order.
- processed_events: one row per outbox event, because the order service consumes its own event stream.
- idempotency_keys: only the last 24 h (4,019 rows after the seed), since the cleanup job removes older keys.
- Loaded with server-side `INSERT ... SELECT` from a fixed random seed. Took 81 s. `ANALYZE` after loading.
- Why this size: it loads in about a minute and fits the 8 GB VM with room to spare. It is not a claim about any real company's volume. Postgres runs with the default 128 MB shared buffers, so the dataset is much larger than the cache.
- What it cannot tell us: anything about contention with real concurrent readers, long-running reports, or a dataset that has grown for years. The cleanup job and the processed_events table never shrink, so a longer history would add rows we did not model.

**Runs.** All three use the same warm-up (30 s at 20 creates/s, discarded), the same measured run (30 s of creates at 4,400 req/s directly to the order service, plus 50 GET reads/s on random seeded orders), and the same Week 1 thresholds.
- **Run A, contaminated:** the big dataset, but dispatch's tables still held about 1.4 M pending orders from earlier NYE runs. My first seed step did not clear them. Shown for transparency, not as the baseline.
- **Run B, clean (the baseline):** big dataset, dispatch tables cleared (`seed.sh`).
- **Run C, control:** same code and rate, order and dispatch tables empty.

| Run | Create achieved req/s | Create p50 / p95 / p99 ms | Read p50 / p99 ms | k6 dropped iterations | Order Hikari pending peak |
|---|---|---|---|---|---|
| Week 1, r4400 (for reference) | 4,400 | 0.71 / 1.82 / 7.98 | n/a | 0 | 0 |
| A, big, contaminated | 2,323 (all requests) | 806.6 / 1,257 / 1,438 | 852.6 / 1,426 | 61,614 | 191 |
| B, big, clean | 2,295 (all requests) | 679.1 / 1,806 / 2,147 | 765.0 / 2,156 | 63,520 | 191 |
| C, empty control | 2,736 (all requests) | 655.3 / 1,148 / 1,522 | 679.9 / 1,629 | 49,631 | 191 |

**Reading the table.**
- The dataset size is not what changed latency. Run C, with empty tables, is just as slow as run B. Its median is 655 ms against 679 ms, and its p99 is 1.5 s against 2.1 s. The p99 difference is from one run each and is not established.
- The gap from Week 1 is large: about 900 times the median and about 270 times the p99 (run B against Week 1) at the same nominal rate. Week 1 had no pool queueing at this rate (pending 0), and every run here shows pending 191. So the request path is slower per request now than in Week 1, or it has less CPU to run in. I have not identified which. See suspicions below.
- The order service's Hikari pool (10 connections) was fully busy for the whole run, with about 190 requests waiting. Order and Postgres were at roughly 160–180% CPU each on a 6 vCPU VM, so CPU contention is a likely reason each connection is held longer. Not verified by a CPU profile.
- The outbox relay fell behind during the run (backlog about 35,000 rows at the peak in run A) and drained after the load stopped.

**Top statements (run B, `pg_stat_statements`, measured run only).**

| Calls | Total ms | Mean ms | Statement (abbreviated) |
|---|---|---|---|
| 68,586 | 6,512 | 0.095 | `UPDATE idempotency_keys SET order_id ... WHERE client_id ...` |
| 68,586 | 5,371 | 0.078 | `INSERT INTO orders ...` |
| 68,586 | 4,892 | 0.071 | `INSERT INTO idempotency_keys ...` |
| 68,586 | 3,316 | 0.048 | `INSERT INTO outbox ...` |
| 68,586 | 1,830 | 0.027 | `UPDATE outbox SET published_at = now() WHERE id = $1` (relay, one row at a time) |
| 695 | 890 | 1.28 | relay batch select (`published_at IS NULL ... FOR UPDATE SKIP LOCKED`) |
| 1,397 | 948 | 0.68 | GET by id |
| 30 | 757 | 25.2 | `SELECT count(*) FROM outbox WHERE published_at IS NULL` (metrics scrape) |
| 1,800 | 1,582 + 1,538 | 0.88 + 0.85 | dispatch retry queries on `pending_orders` (dispatch shares this Postgres) |

By mean time, the slowest recurring statements are the dispatch retry queries and the outbox count from the metrics scrape. The request-path statements all average under 0.1 ms.

**EXPLAIN (ANALYZE, BUFFERS) for the hot queries (run B, `explain-hot.txt`).** Each runs in a transaction that is rolled back, with parameters from a real seeded order.

| Query | Plan | Execution time |
|---|---|---|
| Order insert | insert, no read path | 0.4 ms |
| Idempotency key insert (ON CONFLICT DO NOTHING) | insert, conflict arbiter `idempotency_keys_pkey` | 0.6 ms |
| Idempotency key lock read (FOR UPDATE) | index scan on `idempotency_keys_pkey` | ~0.1 ms |
| Outbox insert | insert | 0.5 ms |
| Relay batch select (100 rows, SKIP LOCKED) | index scan on `outbox_unpublished_idx` (partial) | 1.1 ms |
| Status update (version and status conditional) | index scan on `orders_pkey` | 0.7 ms |
| GET by id | index scan on `orders_pkey` | 0.1 ms |
| Idempotency cleanup batch | index scan on `idempotency_keys_expires_at_idx` for the expired rows | 0.1 ms |

No hot query uses a sequential scan. An earlier version of my lock-read query did a sequential scan, because it looked up the key by `order_id`, which has no index. That was my test query, not the app's, so I changed it to the app's query.

**Indexes and usage (run B, scans during the measured run).**

| Table | Index | Scans in run | What it is for |
|---|---|---|---|
| orders | orders_pkey | 69,986 | GET by id, status update, FK check from idempotency_keys |
| idempotency_keys | idempotency_keys_pkey | 137,187 | key lock, conflict arbiter, status update of the stored response |
| idempotency_keys | idempotency_keys_expires_at_idx | 2 | cleanup job (runs every minute) |
| outbox | outbox_pkey | 68,596 | relay update of published_at |
| outbox | outbox_unpublished_idx | 737 | relay batch select (partial, unpublished rows only) |
| processed_events | processed_events_pkey | 35,497 | consumer dedupe insert |

No index of the order service's tables went unused. The `flyway_schema_history` indexes had zero scans, as expected for a table only read at startup. The `orders` table has no index on `client_id` or `created_at`, because no current query needs one. Any future "orders by client" or "orders by time" query would scan the whole table.

**Autovacuum and dead tuples (run B, cumulative since the last statistics reset; I reset only `pg_stat_statements`).** `outbox` had 69,187 dead tuples after the run, from the relay's per-row `published_at` updates. Autovacuum had run on every table in the last few minutes (88–93 times in total). Table sizes after run B: outbox 1,302 MB data and 233 MB indexes; processed_events 511 MB and 229 MB; orders 263 MB and 40 MB; idempotency_keys 28 MB and 8 MB.

**What I suspect but did not verify.**
- **CPU contention on the shared VM.** Order (~180%) and Postgres (~160%) together use most of the 6 vCPUs, and k6 runs on the same machine. Each connection may be held longer because the work waits for CPU. Not profiled.
- **Extra per-request work since Week 1.** The order service now writes idempotency rows, and its own consumer inserts into processed_events for every event it publishes. Both use the same connection pool. Week 1 had neither in the path. Not measured separately.
- **Dispatch on the same Postgres.** Dispatch's retry queries and inserts ran during every run, against the same server. Run B still processed about 37,000 pending inserts. Its effect on the order path is not separated out. The dispatch tables were cleared before each run, but dispatch kept consuming during it.
- **pg_stat_statements overhead.** Not measured. It may explain part of the gap from Week 1.
- **The metrics count query.** `SELECT count(*) FROM outbox WHERE published_at IS NULL` averages 25 ms, although it returns zero rows. My guess is that the partial index still holds entries for the many rows the relay has updated, so the scan visits dead index entries. Not verified; it runs on every scrape, so it is worth checking.
- **Week 1 comparability.** The Week 1 numbers were taken on the same VM but on a different day, with a different code state and without dispatch running. I did not re-run Week 1's exact configuration, so I cannot say which change caused the gap.

## Week 4: reproducing Week 1 and database fixes

**Plan (written before the runs).**
- Step 1 found that the same 4,400 req/s create load gives a median of about 680 ms now, against 0.71 ms in Week 1, even with empty tables. This section finds which factor accounts for the gap.
- I will change one factor at a time and run each condition twice, alternating: dispatch stopped; `pg_stat_statements` tracking off; the admission front door in the path, with its rate limiter and shedding off.
- I will also run the Week 1 order-service commit (`68e524a`) on the same VM, same Postgres and same workload, to separate "the machine changed" from "the code changed". It uses a separate database, `week1`, and is recreated for each run.
- All bisect runs start from empty tables (`seed.sh` with `EMPTY=1`, then a truncate), with the same 30 s warm-up at 20 req/s and the same 30 s measured run at 4,400 req/s. Read traffic is off for these runs.
- Host and VM CPU are sampled every 2 s during each run. Nothing is tuned between runs.

### Step 2 results: bisect (`loadtest/step2/`, folder `loadtest/results/week4s2-bisect-20261122-0826/`)

Each condition was run twice, in alternating order (r1 forward, r2 reverse). Same procedure every run: empty tables, 30 s warm-up at 20 creates/s (discarded), tables truncated, `pg_stat_statements` reset, 30 s at 4,400 creates/s, then the outbox drained. Creates only, except the reads condition. Sampling every 2 s for containers, Postgres, Hikari and the outbox; host process CPU every 2 s. The table is generated by `loadtest/step2/summarise_step2.py`.

| Run | Condition | Achieved req/s | Dropped iter. | p50 ms | p95 ms | p99 ms | Fail rate | Order CPU mean/peak % | PG CPU mean/peak % | Hikari pending peak | Outbox backlog peak | Drain s | Host CPU sum mean/peak (100 = 1 core) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| r1-01 | current, dispatch on, pg_stat on, direct | 4323 | 2294 | 1.74 | 52.53 | 138.11 | 0.0000 | 111/161 | 131/175 | 191 | 41217 | 12 | 448/662 |
| r2-06 | current, dispatch on, pg_stat on, direct | 4072 | 7933 | 375.77 | 551.27 | 639.99 | 0.0000 | 107/167 | 127/183 | 191 | 61687 | 21 | 433/552 |
| r1-02 | current, dispatch on, pg_stat on, direct, reads 50/s | 4446 | 100 | 1.58 | 12.70 | 39.12 | 0.0113 | 110/155 | 133/191 | 108 | 38301 | 11 | 433/522 |
| r2-05 | current, dispatch on, pg_stat on, direct, reads 50/s | 4300 | 3585 | 92.14 | 265.78 | 339.34 | 0.0116 | 119/228 | 136/218 | 192 | 60534 | 19 | 449/609 |
| r1-03 | current, dispatch off, pg_stat on, direct | 4287 | 3153 | 2.35 | 139.81 | 248.89 | 0.0000 | 119/168 | 123/191 | 191 | 54249 | 15 | 454/657 |
| r2-04 | current, dispatch off, pg_stat on, direct | 4396 | 109 | 1.81 | 12.14 | 35.14 | 0.0000 | 128/189 | 124/177 | 168 | 45193 | 12 | 400/522 |
| r1-04 | current, dispatch off, pg_stat off, direct | 4351 | 1476 | 1.84 | 67.44 | 148.47 | 0.0000 | 118/170 | 123/177 | 190 | 52324 | 15 | 423/552 |
| r2-03 | current, dispatch off, pg_stat off, direct | 4400 | n/a | 1.59 | 9.50 | 21.24 | 0.0000 | 129/181 | 126/205 | 57 | 44902 | 10 | 406/546 |
| r1-05 | current, dispatch off, pg_stat off, admission (limiter and shedding off) | 902 | 103752 | 2051 | 4159 | 6441 | 0.5012 | 25/85 | 25/82 | 26 | 709 | 0 | 264/669 |
| r2-02 | current, dispatch off, pg_stat off, admission (limiter and shedding off) | 813 | 106282 | 2185 | 4240 | 5604 | 0.4974 | 62/229 | 30/51 | 0 | 1668 | 0 | 276/608 |
| r1-06 | Week 1 code (`68e524a`), dispatch off, pg_stat off, direct | 4229 | 4837 | 36.19 | 346.88 | 450.80 | 0.0000 | 155/351 | 112/183 | 191 | 67855 | 18 | 452/634 |
| r2-01 | Week 1 code (`68e524a`), dispatch off, pg_stat off, direct | 4033 | 10996 | 53.45 | 649.82 | 787.68 | 0.0000 | 156/345 | 108/178 | 191 | 62406 | 16 | 465/646 |

**What this shows, in plain words.**
- **The same condition gives very different results on different runs.** Current code, dispatch on, pg_stat on, direct: p99 138 ms in r1-01 and 640 ms in r2-06. Current code, dispatch off, pg_stat off, direct: p99 148 ms in r1-04 and 21 ms in r2-03. The spread inside one condition is as large as the gaps between conditions. Two runs per condition is not enough to rank them.
- **The Week 1 code is slower today than the current code.** Under the same conditions, the Week 1 binary has a median of 36–53 ms and a p99 of 450–790 ms, against 1.6–2.4 ms and 21–150 ms for the current code. The code did not get slower since Week 1. So the gap from Week 1's 0.71 ms is mostly the environment (the VM, Docker, or the other host load) as it is today, not the order-service code.
- **Dispatch, `pg_stat_statements` and reads: no effect that is larger than the run-to-run spread.** Dispatch on vs off: p99 138 and 640 ms against 249 and 21 ms, so the direction flips between runs. `pg_stat_statements` on vs off: p99 35–138 ms against 21–148 ms, which overlaps. Reads at 50/s: 39 and 339 ms, overlapping with the no-read runs. I cannot claim any of these is a cause.
- **Admission in the path: the hop is large and half the requests failed.** With the limiter and shedding switched off, achieved throughput was 813–902 req/s with about 50% of creates failing the `status is 201` check. A direct probe through admission after the runs returned 201, so the failures happen under load. I did not determine the failure status codes (`admission` logs did not show them and the container was restarted), so this is unexplained. These two runs are not comparable to the direct runs.
- **Background CPU.** Host process CPU summed over processes averaged about 400–470 (per cent of one core, so about 4–5 cores busy), with the Docker VM and the sentinel kind node included. The sentinel node alone was 15% at the start of this session. I cannot separate the VM's own overhead from the host's other load in this data.

**Fixes.** None applied. The bisect did not identify a factor that is larger than its run-to-run spread, so no fix is supported by the evidence. Nothing was changed in application code or in the database settings during the runs (except `pg_stat_statements.track` in the pg_stat-off runs, restored afterwards).

**What I did not verify.**
- Whether the environment got slower or just noisier. Week 1's Docker and VM state is not recorded, and I did not re-run Week 1's exact setup.
- Whether more runs would separate dispatch or pg_stat from noise. That needs more runs per condition than the two the plan asked for.
- The admission failure codes.

### Step 2 correction: the admission-path runs were cold, and the 50% failures are explained

The two admission-path runs in the bisect (r1-05, r2-02) failed about half their creates. I investigated without changing code (`loadtest/step2/status-probe.js`, folders `loadtest/results/week4s2-status-probe-*`).
- **Cause, as measured:** admission had been recreated just before those runs (`docker compose up -d` with the limiter and shedding off). A **freshly started** admission returns 503 on about half of creates (`status_503` 12,824 of 27,037 and 14,703 of 24,381 in the two cold probes). The admission counters show the 503s are upstream errors: `admission_upstream_errors_total{type="pool_timeout"}` 8,322 and 10,944, and `type="other"` 4,502 and 3,759. There were no 429s, no shed events and no 504s.
- **Warm:** the same container, probed again right after, returned 130,947 of 130,947 creates as 201 with no upstream errors, at 4,365 req/s.
- **Not determined:** why cold admission hits pool timeouts (the 200-connection pool with a 0.5 s wait is my guess, not measured), and what the `other` type is. The admission log did not record it.
- **Consequence for the bisect:** the admission-path runs measured admission's cold start, not steady state. They are not valid for the comparison "admission in the path". Their warm-up (30 s at 20 req/s) was too light to warm 200 upstream connections. I have not re-run them.

### Step 2 pre-run check (`loadtest/precheck.sh`)

Every measured run now starts with `loadtest/precheck.sh <run folder>`, which writes its readings to `precheck.txt` in that folder and refuses to start the run if:
- `pmset -g therm` records a thermal or performance warning, or a CPU speed limit below 100;
- the median of three `docker stats` CPU sums (2 s apart) is above 100 (out of 600 on this VM, about 17%);
- an autovacuum worker or the checkpointer is busy.

It also records the background bgwriter and autovacuum counters. The thresholds are starting values, chosen before any run in this step. In Step 3 and 4 the check refused several times while autovacuum was working on tables the previous operation had just loaded. Each wait is recorded in `driver.log`.

### Week 4: partitioning (experimental database `peakload_exp`)

**Scope.** The experiment runs on `peakload_exp`, a copy of the 1M-order dataset created by dump and reseed, never on `peakload`. The variants are standalone SQL (`loadtest/partition/01-setup-variants.sql`), not Flyway migrations. Adopting one would be a separate decision. The flat tables are untouched.

**What each variant changes.**
- `outbox_p`: monthly `RANGE` partitions on `created_at` (2025-11 to 2026-12, plus a default). The primary key becomes `(id, created_at)`, because a partitioned table's key must include the partition column. The relay's partial index is created per partition.
- `orders_p`: the same partitioning for `orders`, with primary key `(id, created_at)`. **It has no foreign key from `idempotency_keys`.** A foreign key can only reference a partitioned table on a unique key that includes `created_at`, so the real design would need `idempotency_keys` to carry `order_created_at` as a composite foreign key. This measured copy is only for reads, and the foreign key is not reproduced.
- `idempotency_keys` and `processed_events` are **not partitioned**. Their uniqueness on the key must not depend on a partition column, and partitioning them would weaken the duplicate guarantee.

**Deterministic measures** (`loadtest/partition/02-measure-deterministic.sql`, three repeats, output in `loadtest/results/week4s3-partition-wal-fixed-measure/`, table from `loadtest/partition/extract.py`). Planning and execution times vary between repeats, and the buffer split varies with cache state, so the table gives ranges:

| Measure | Flat | Partitioned | Notes |
|---|---|---|---|
| GET by id on `orders` (no `created_at`) | 1 leaf, planning 0.090–0.188 ms, execution 0.016–0.025 ms, planning buffers 47 | 15 leaves probed, planning 0.646–1.209 ms, execution 0.103–0.358 ms, planning buffers 513 | Partitioning costs on the read by id, as expected. |
| GET by id with `created_at` in the predicate | n/a | 1 leaf, execution 0.007–0.008 ms | Pruning works when the predicate has `created_at`. |
| Range: orders from the last 7 days | 1 leaf (seq scan), execution 57.4–57.6 ms, 28,000+ buffers read | 3 leaves, execution 5.1–7.4 ms, 317 buffers, no reads | **Confounded**: the flat `orders` table has no `created_at` index. Partitioning vs. adding an index is not separated (see "Not done"). |
| Relay batch (oldest 100 unpublished) | 1 leaf, execution 1.24–9.55 ms (one outlier at 9.55), buffers 226–240 | 15 leaves probed, execution 1.05–2.44 ms, buffers 158–171, planning 0.47–0.78 ms | Partitioned: more planning cost and more partitions probed, fewer buffers, similar execution time. |

**Sizes** (summed over leaves, bytes): `orders` 296.8 MB total (257.3 MB heap, 39.5 MB indexes) vs `orders_p` 360.3 MB (257.3 MB heap, 102.4 MB indexes, from the extra primary key and the composite index). `outbox` 1.589 GB (1.343 GB heap, 245 MB indexes) vs `outbox_p` 1.649 GB (1.333 GB heap, 316 MB indexes).

**WAL per 10,000-row insert** (insert LSN read inside the transaction, rolled back): flat outbox 2.78–2.83 MB, partitioned 2.78–2.99 MB. The spreads overlap; no difference.

**Order-create replay** (secondary, `loadtest/partition/bench-create.sh`; pgbench, 16 clients, 60 s, one statement writing order, idempotency row and outbox row, alternating, three repeats, experimental database):

| Repeat | Flat outbox tps / avg latency | Partitioned outbox tps / avg latency |
|---|---|---|
| 1 | 10,327 / 1.549 ms | 9,499 / 1.684 ms |
| 2 | 9,539 / 1.677 ms | 9,042 / 1.769 ms |
| 3 | 8,038 / 1.990 ms | 6,852 / 2.335 ms |

Partitioned was slower in all three pairs (by 8%, 5% and 15%). But the flat repeats span 8,038–10,327 tps, a wider range than the gap between the means (mean 9,302 vs 8,464, about 9%). Throughput also fell from repeat to repeat as the tables grew and autovacuum ran. **I do not claim a throughput difference from these numbers.** The result is "no measurable difference beyond the run-to-run spread, with a possible slowdown from partitioning that three repeats cannot confirm".

**Decision.** Partition `outbox` only if the owner accepts a 15-partition relay query and a primary key that includes `created_at`. The measured gains are small. Partitioning `orders` also needs the composite foreign key, which is a schema change that this step did not test. The default recommendation is to keep the unpartitioned design. This is a decision for the owner.

### Week 4: retention (batched DELETE vs DETACH and DROP)

**Setup.** Each repeat rebuilds two copies of the 5.8 M-row outbox on `peakload_exp` (`loadtest/partition/03-retention-setup.sql`): `ret_flat` with indexes on `created_at` and on unpublished rows, and `ret_part`, partitioned monthly. The operation removes rows with `created_at < 2026-05-01`: 2,546,193 rows, six months, in the flat table. Each operation runs while 8 pgbench clients insert into the same table (`loadtest/partition/run-retention.sh`). Three repeats, with the order of the two operations alternating between repeats. The batched delete commits every 10,000 rows (`04-op-batch.sql`, procedure `purge_flat`). The partition option detaches and drops the six partitions (`04-op-drop.sql`, procedure `drop_old_partitions`).

**Results** (`loadtest/results/week4s4-retention-20261122-0930/`; raw pgbench logs are kept out of the repo because of their size).

| Measure | Batched DELETE, repeats 1 / 2 / 3 | DETACH + DROP, repeats 1 / 2 / 3 |
|---|---|---|
| Duration of the operation | 19.7 s / 27.7 s / 10.9 s | 85.5 ms / 166 ms / 45 ms |
| Rows removed | 2,546,193 (each run, printed by the procedure) | 6 partitions; their row count was not printed |
| WAL written by the operation | 2.393 GB / 2.425 GB / 2.393 GB | 0.48 MB / 0.66 MB / 0.45 MB |
| Dead tuples right after | 2,546,193 / 2,546,193 / 2,546,193 | 0 |
| Table size before → after | 2.107 → 2.132 GB; 2.108 → 2.135 GB; 2.114 → 2.134 GB | 1.946 → 1.238 GB; 1.943 → 1.233 GB; 1.949 → 1.241 GB |
| Autovacuum runs during the operation | 0 / 0 / 0 | not recorded |
| Concurrent insert tps (100 s window that contains the operation) | 7,438 / 7,745 / 11,280 | 10,064 / 9,189 / 12,766 |
| Concurrent insert average latency | 1.08 / 1.03 / 0.71 ms | 0.80 / 0.87 / 0.63 ms |
| Concurrent inserts over 100 ms | 54 / 120 / 14 | 23 / 12 / 0 |
| Max single concurrent insert | 254 / 245 / 207 ms | 225 / 271 / 76 ms |

**What the numbers say.**
- **Duration and WAL.** The partition option is far cheaper: tens of milliseconds and under 1 MB of WAL, against 10–28 seconds and about 2.4 GB. The gap is much larger than the spread across repeats. Dropping a partition does not write each row.
- **Space.** The batched delete leaves 2.5 M dead tuples and returns no space at once (the table stays at about 2.1 GB until VACUUM and a rebuild). The partition drop returns about 0.7 GB immediately.
- **Concurrent writes.** In all three pairs the partition option had higher insert tps and lower average latency than the batched delete (tps 35%, 19% and 13% higher). The mean difference (10,673 vs 8,821 tps, about 21%) is about the size of the run-to-run spread of the batch option (7,438–11,280), so I do not claim it as an established effect. The 100 s window contains each operation only partly. The batch option's WAL and I/O may slow inserts for the whole window, which would favour the partition option, but this was not separated. The counts of slow inserts point the same way; the maxima overlap.
- **Locks.** The plain `DETACH PARTITION` takes an `ACCESS EXCLUSIVE` lock on the parent table, so it can block writers. The latency log shows no multi-second stall for either option, but it covers the whole window, so a stall of a few hundred milliseconds would not be separated out. The batched delete commits per batch by design. Lock waits were not measured with `pg_locks`, and `DETACH PARTITION ... CONCURRENTLY` was not tested.

**Limitations.** Three repeats each. The batch size (10,000) and the cut-off (2026-05-01) were chosen, not tuned. Autovacuum ran between operations (the pre-run check waited for it several times), so the table state differs a little between repeats.

## Week 5: retries and circuit breaker

**What was added.** Admission now retries one failed order call and wraps every order call in a circuit breaker. Retries: GET, and `POST /orders` with an `Idempotency-Key` (the retry sends the same key); only connection-level failures (refused, reset, connect timeout, read timeout); not 4xx, not an upstream 5xx, not a full pool; at most one retry with jitter, within a 3 s deadline, within a retry budget of 10% of calls over 10 s, and only at load level NORMAL. Breaker: counts timeouts, connection errors and upstream 502/503/504; 50% of the last 20 calls (after 10 calls) opens it for 10 s; half-open admits 1 probe; a success closes it. Open means `503 order_unavailable` with `Retry-After` and no call to the order service. Every threshold is an unmeasured starting value. Flags `RETRY_ENABLED` and `CIRCUIT_ENABLED` turn each part off. The library was not used (Resilience4j's breaker cannot take an injected clock); the classes are `RetryPolicy`, `RetryBudget`, `CircuitBreaker`, `ProxyService`. Tests: 57 unit and 31 integration tests pass with `mvn -B verify` on JDK 21 for the admission module.

**Week 5 runs start from reset tables.** Every run: pre-run check, reset (order and dispatch tables emptied, riders set AVAILABLE, `loadtest/db/seed.sh` with `EMPTY=1`), admission restarted with its flags, a 20 s warm-up at the target rate (discarded), a second reset, a second pre-run check (`precheck.txt` records the table counts), and then the timed run. The table counts and the Postgres checkpoint and autovacuum counters are recorded in each run folder.

**Two checks before measurement E.**
- 20 s at 800 req/s, cold admission, no warm-up (`loadtest/results/week5-e-check-20261122-1158`): 1,407 of 14,953 answers were 503 `shed`. They came in two windows, t = 0–1 s (right after the restart) and t = 16–18 s (a stall with a CPU spike in admission, order and Postgres; cause not determined). The steady seconds between had none.
- 20 s at 400 req/s, cold (`week5-e-check400-20261122-1209`): 8,000 answers, all 2xx; create p99 233 ms.

A 60 s check at 800 req/s with the 20 s warm-up (`week5-e-warm60-20261122-1218`) was clean: 23,991 creates, all 201; create p99 3.4 ms; admission CPU peak 51%. So the 800 req/s rate was kept for measurement E.

**Measurement E.** 800 req/s for 90 s through admission, a mix of creates (50%), order reads (30%) and menu reads (20%), each request with its own client id. The order service was stopped at +30 s and started at +60 s. ON = retries and breaker on; OFF = both off. Order ON, OFF, ON, OFF, two runs each. Raw data and per-run analysis: `loadtest/results/week5-measurement-e-20261122-1220/`.

Share of answers in each phase (before, during = order stopped, after = order running again). Every row adds to 100.00%; generated by `loadtest/week5/outcome_table.py` from the k6 points:

| Run | Kind | Phase | n | 2xx | 503 connect_timeout | 503 connection_refused | 503 order_unavailable | 503 other | 503 shed |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|
| run1-on | create | before | 11850 | 100.00% | 0% | 0% | 0% | 0% | 0% |
| run1-on | create | during | 12100 | 3.46% | 0% | 0% | 96.54% | 0% | 0% |
| run1-on | create | after | 12089 | 59.72% | 0% | 0% | 40.28% | 0% | 0% |
| run1-on | read | before | 7032 | 100.00% | 0% | 0% | 0% | 0% | 0% |
| run1-on | read | during | 7149 | 3.90% | 0% | 0% | 96.10% | 0% | 0% |
| run1-on | read | after | 7304 | 60.56% | 0% | 0% | 39.44% | 0% | 0% |
| run1-on | menu | before | 4811 | 100.00% | 0% | 0% | 0% | 0% | 0% |
| run1-on | menu | during | 4751 | 85.03% | 0% | 0% | 0% | 0% | 14.97% |
| run1-on | menu | after | 4915 | 100.00% | 0% | 0% | 0% | 0% | 0% |
| run2-off | create | before | 12054 | 100.00% | 0% | 0% | 0% | 0% | 0% |
| run2-off | create | during | 12060 | 2.31% | 19.04% | 0.57% | 0% | 73.97% | 4.11% |
| run2-off | create | after | 12236 | 72.98% | 0% | 0% | 0% | 27.02% | 0% |
| run2-off | read | before | 6948 | 100.00% | 0% | 0% | 0% | 0% | 0% |
| run2-off | read | during | 7187 | 2.42% | 1.11% | 0.60% | 0% | 72.08% | 23.79% |
| run2-off | read | after | 7330 | 71.28% | 0% | 0% | 0% | 27.53% | 1.19% |
| run2-off | menu | before | 4713 | 100.00% | 0% | 0% | 0% | 0% | 0% |
| run2-off | menu | during | 4754 | 75.56% | 0% | 0% | 0% | 0% | 24.44% |
| run2-off | menu | after | 4718 | 97.48% | 0% | 0% | 0% | 0% | 2.52% |
| run3-on | create | before | 11906 | 100.00% | 0% | 0% | 0% | 0% | 0% |
| run3-on | create | during | 12106 | 3.36% | 0% | 0% | 96.62% | 0.02% | 0% |
| run3-on | create | after | 12101 | 30.58% | 0% | 0% | 69.41% | 0.01% | 0% |
| run3-on | read | before | 7081 | 100.00% | 0% | 0% | 0% | 0% | 0% |
| run3-on | read | during | 7123 | 3.19% | 0% | 0% | 96.78% | 0.03% | 0% |
| run3-on | read | after | 7305 | 30.17% | 0% | 0% | 69.82% | 0.01% | 0% |
| run3-on | menu | before | 4700 | 100.00% | 0% | 0% | 0% | 0% | 0% |
| run3-on | menu | during | 4771 | 100.00% | 0% | 0% | 0% | 0% | 0% |
| run3-on | menu | after | 4909 | 100.00% | 0% | 0% | 0% | 0% | 0% |
| run4-off | create | before | 12001 | 100.00% | 0% | 0% | 0% | 0% | 0% |
| run4-off | create | during | 12050 | 3.82% | 81.52% | 0.19% | 0% | 0.07% | 14.40% |
| run4-off | create | after | 12108 | 62.69% | 3.33% | 0% | 0% | 32.48% | 1.50% |
| run4-off | read | before | 7002 | 100.00% | 0% | 0% | 0% | 0% | 0% |
| run4-off | read | during | 7082 | 3.84% | 0.90% | 0.25% | 0% | 0.06% | 94.95% |
| run4-off | read | after | 7315 | 58.89% | 0% | 0% | 0% | 33.14% | 7.97% |
| run4-off | menu | before | 4719 | 100.00% | 0% | 0% | 0% | 0% | 0% |
| run4-off | menu | during | 4868 | 4.70% | 0% | 0% | 0% | 0% | 95.30% |
| run4-off | menu | after | 4856 | 91.60% | 0% | 0% | 0% | 0% | 8.40% |

Latency of failed requests in the phase (p50 / p99, ms): ON 1 creates during 0.4 / 3.7; ON 3 creates during 0.4 / 2.0; OFF 2 creates during 501 / 502; OFF 4 creates during 501 / 504. So the breaker turns a failure into a fast answer, and without it each failure takes about the 0.5 s connect timeout.

Breaker timing: open within the first request after the stop in both ON runs (the analysis rounds to 0.00 s); time to close (first create answered 201 after the order was started) 10.1 s in ON 1 and 19.1 s in ON 3. With retries and breaker off, the 201 came back after 6.6 s and 9.4 s, but those runs had no breaker, so "close" there means the first success.

Consistency: every run's 201 count equals the orders created in that run, and there were no duplicate (client, idempotency key) pairs. Admission CPU during the timed run: mean 33–41%, peak 59–96% (one core = 100%).

**What these runs show.** On one VM, with a single order-service kill per run and two runs per condition: with the breaker on, the failures become fast 503s and creates recover after the restart; with it off, failures are slow and the admission sheds load (shed in the OFF runs during the kill; no create or read was shed in the ON runs).

**The retry was never exercised in measurement E.** The `admission_upstream_retries_total` counter does not appear in any run, and neither does `admission_upstream_retries_skipped_total`. Micrometer creates a counter only when it is first incremented, so no retry was sent and no retry was skipped in any of the four runs. The reason is the failure the stop produces. With the container stopped, the service name `order` stops resolving, and the error is `java.net.UnknownHostException: order`, which admission classifies as `other`. The retry rules only retry refused connections, resets and timeouts, so these failures never qualified. A dead instance that still resolves would give connection refused, which is retryable, so the retry path needs a different kill to be measured. The breaker's effect in these runs is therefore the only effect measured.

**What they do not show.** Two runs per condition is too few to give a spread for the time-to-close figures (10 s vs 19 s is the same breaker with a different probe outcome, not a measured effect). The stall seen in the 800 req/s check without warm-up (t = 16–18 s) did not appear in the warmed check or in any E run before the kill. The 20 s warm-up is a protocol change from the earlier checks; it was applied to every run in this section. No rate other than 800 req/s was measured for E. Admission at 400 req/s was clean in one check, and was not used.


## Week 5: retry measurement with docker pause

**Setup.** 800 req/s for 90 s, the same mix as measurement E, after a 20 s discarded warm-up and a reset. The order service was **paused** (`docker pause`, the process frozen) at +30 s and **unpaused** at +60 s. The breaker was on in every run. Retries ON and OFF alternated (ON, OFF, ON, OFF), two runs each. A pre-run check ran before each run. Raw data: `loadtest/results/week5-retry-pause-20261122-1241/`.

**Retries.** No retry was made in any run: `admission_upstream_retries_total` never appears. Skips: `load` 192 (ON run 1) and 191 (ON run 3); none for budget or deadline; none in the OFF runs (retries disabled). Creates that succeeded only after a retry: 0.

Why no retry was made. A paused process still accepts the TCP connection, so admission waits and then gives up at its 2 s read timeout (answered 504 `read_timeout`, 0.46–0.58% of creates in the paused window). The retry rules send a retry only if the first attempt, the jitter and one more read timeout fit in the 3 s deadline. After a 2 s timeout they do not fit, so retries are not sent. Under load the shed level (ELEVATED or above) skips retries first; that is the `load` count.

**Breaker.** Open 0.65–0.68 s after the pause in all four runs. Closed (first create answered 201) 1.5 s after the unpause in all four runs.

**Status breakdown.** Every row adds to 100.00%:

| Run | Kind | Phase | n | 2xx | 503 connect_timeout | 503 order_unavailable | 503 shed | 504 read_timeout |
|---|---|---|---:|---:|---:|---:|---:|---:|
| run1-retry-on | create | before | 11832 | 99.81% | 0% | 0% | 0.19% | 0% |
| run1-retry-on | create | during | 11996 | 0.28% | 0.60% | 97.48% | 1.11% | 0.53% |
| run1-retry-on | create | after | 12211 | 94.38% | 0% | 5.62% | 0% | 0% |
| run1-retry-on | read | before | 7019 | 99.87% | 0% | 0% | 0.13% | 0% |
| run1-retry-on | read | during | 7183 | 0.25% | 0.21% | 67.87% | 31.10% | 0.57% |
| run1-retry-on | read | after | 7396 | 93.88% | 0% | 6.12% | 0% | 0% |
| run1-retry-on | menu | before | 4627 | 98.98% | 0% | 0% | 1.02% | 0% |
| run1-retry-on | menu | during | 4820 | 12.63% | 0% | 0% | 87.37% | 0% |
| run1-retry-on | menu | after | 4915 | 100.00% | 0% | 0% | 0% | 0% |
| run2-retry-off | create | before | 12061 | 100.00% | 0% | 0% | 0% | 0% |
| run2-retry-off | create | during | 11920 | 0.28% | 0.65% | 97.44% | 1.17% | 0.46% |
| run2-retry-off | create | after | 12151 | 94.23% | 0% | 5.77% | 0% | 0% |
| run2-retry-off | read | before | 7009 | 100.00% | 0% | 0% | 0% | 0% |
| run2-retry-off | read | during | 7254 | 0.18% | 0.18% | 68.00% | 30.98% | 0.66% |
| run2-retry-off | read | after | 7337 | 94.44% | 0% | 5.56% | 0% | 0% |
| run2-retry-off | menu | before | 4645 | 100.00% | 0% | 0% | 0% | 0% |
| run2-retry-off | menu | during | 4826 | 13.22% | 0% | 0% | 86.78% | 0% |
| run2-retry-off | menu | after | 4797 | 100.00% | 0% | 0% | 0% | 0% |
| run3-retry-on | create | before | 12139 | 100.00% | 0% | 0% | 0% | 0% |
| run3-retry-on | create | during | 11944 | 0.25% | 0.58% | 97.61% | 1.10% | 0.46% |
| run3-retry-on | create | after | 12064 | 94.17% | 0% | 5.83% | 0% | 0% |
| run3-retry-on | read | before | 6967 | 100.00% | 0% | 0% | 0% | 0% |
| run3-retry-on | read | during | 7320 | 0.16% | 0.26% | 67.23% | 31.69% | 0.66% |
| run3-retry-on | read | after | 7367 | 94.11% | 0% | 5.89% | 0% | 0% |
| run3-retry-on | menu | before | 4611 | 100.00% | 0% | 0% | 0% | 0% |
| run3-retry-on | menu | during | 4736 | 13.22% | 0% | 0% | 86.78% | 0% |
| run3-retry-on | menu | after | 4852 | 100.00% | 0% | 0% | 0% | 0% |
| run4-retry-off | create | before | 12065 | 100.00% | 0% | 0% | 0% | 0% |
| run4-retry-off | create | during | 12107 | 0.25% | 0.60% | 97.34% | 1.23% | 0.58% |
| run4-retry-off | create | after | 12071 | 93.92% | 0% | 6.08% | 0% | 0% |
| run4-retry-off | read | before | 6984 | 100.00% | 0% | 0% | 0% | 0% |
| run4-retry-off | read | during | 7111 | 0.31% | 0.13% | 67.43% | 31.53% | 0.60% |
| run4-retry-off | read | after | 7373 | 94.05% | 0% | 5.95% | 0% | 0% |
| run4-retry-off | menu | before | 4699 | 100.00% | 0% | 0% | 0% | 0% |
| run4-retry-off | menu | during | 4781 | 13.91% | 0% | 0% | 86.09% | 0% |
| run4-retry-off | menu | after | 4810 | 100.00% | 0% | 0% | 0% | 0% |

**Duplicates and the 201 count.** Duplicate (client, key) pairs: 0 in all four runs. The 201 count does **not** equal the orders in Postgres in any run. Orders exceed the 201 answers by 64 (ON run 1), 55 (OFF run 2), 55 (ON run 3) and 70 (OFF run 4). In the last run every order has a stored idempotency response of 201, so no key was answered twice. The likely cause: admission answered 504 or 503 after its read timeout for requests that the paused order service had already accepted and then completed after the unpause. I did not match individual requests to keys, so this is a likely cause, not a verified one. Measurement E's runs did match exactly.

## Week 5: dispatch chaos test

**Setup.** One dispatch instance, one order service, one Postgres, one Redpanda, one Redis, one Docker VM. Creates go directly to the order service at 20 req/s for 150 s (3,000 orders per run), with the same cities and pickups as the riders (`loadtest/chaos/create-orders.js`). Riders: 1,200 per city in Pune, Mumbai and Delhi (3,600 per run), in a box of about 15 km around each centre; the 3 km search radius of dispatch finds riders from the same box. 1,200 per city is about 1.2 times the orders each city receives, so supply is not the limit at this rate. The baseline used 480 per city (60 s, 1,200 orders). Riders are seeded in Postgres (AVAILABLE) and Redis (GEO position and a seen key that lasts an hour), by `loadtest/chaos/seed-riders.sh`.

**Reset order (every run).** Load stopped; then the drain check passes, with a limit of 900 s, which fails the run loudly if reached: processed events equal published order events, orders created minus assignments minus pending equals zero, and each partition's committed offset equals its end offset for both consumer groups (the group's total lag is not used, see below). Then dispatch is stopped, the order and dispatch tables are truncated, the riders are reseeded, and dispatch is started and checked healthy (`loadtest/chaos/reset.sh`). A 20 s warm-up (discarded) runs after the first reset, and the reset runs again before the timed run.

**Chaos.** `docker kill` (SIGKILL) on dispatch at +20 s, +60 s and +100 s of the 150 s run, each restarted 15 s after the kill. Control runs: no kill. Order: kill, control, kill, control.

**Results** (`loadtest/results/week5-chaos-series2-20261122-1334/`):

| Run | Orders | Assignments | Pending | Balance (orders - assign - pending) | Processed = published | Redeliveries rejected | Assignment lag p50 / p95 (s) | Restart to first event processed (s) | Drain (s) | Invariants total |
|---|---:|---:|---:|---:|---|---:|---|---|---:|---:|
| kill-1 | 3000 | 2993 | 7 | 0 | 3000 = 3000 | 0 | 72.784 / 137.166 | 118.9, 78.8, 38.7 | 30 | 0 |
| control-1 | 3001 | 2994 | 7 | 0 | 3001 = 3001 | 0 | 0.61 / 0.983 | n/a (no kill) | 0 | 0 |
| kill-2 | 3001 | 3000 | 1 | 0 | 3001 = 3001 | 3 | 75.431 / 139.609 | 119.1, 79.3, 39.3 | 31 | 0 |
| control-2 | 3001 | 2990 | 11 | 0 | 3001 = 3001 | 0 | 0.61 / 0.986 | n/a (no kill) | 0 | 0 |

**Recovery.** After the last restart, the first event was processed about 39 s later in both kill runs. The three restarts each waited for a join that only completed after the last kill's session expired, so the time is dominated by the consumer group's session timeout, not by dispatch's startup. Evidence from the dispatch logs: the effective `session.timeout.ms` is 45000 and `heartbeat.interval.ms` is 3000, `group.instance.id` is null; the Spring context starts in about 1.5 s ("Starting" to "Started"); each consumer joined about 37 s after its process started, and the join that completed came 55 s after the last kill. A killed consumer sends no leave, so the coordinator keeps its place until the session expires. Options, none applied in this series: (1) a lower `session.timeout.ms` (for example 10–15 s), which speeds recovery but risks false rebalances under GC or load pauses; (2) static membership (`group.instance.id`), where a restarted member with the same id takes its partitions back at once, but every instance needs a stable id; (3) a graceful stop (SIGTERM, a leave) instead of `docker kill`, which removes the wait for planned restarts but does not help a crash, which is what a kill models.

**Invariants.** In all four runs: no order with two assignments, no rider with two active assignments, no active assignment to a rider that is not BUSY, and no BUSY rider without an active assignment: all zero. Orders minus assignments minus pending is zero in every run, and no order or event was missing after the drain.

**Harness issues found and fixed on the way (not dispatch bugs).** (a) The first baseline reset truncated the order and dispatch tables while the warm-up's events were still in flight; dispatch then assigned riders to orders that had been deleted (four assignments, three riders left AVAILABLE with an active assignment). The reset now drains first. (b) The first kill run reported before dispatch had finished draining, because the group's total lag read 0 right after a restart while events were still unprocessed. That run is marked invalid (`week5-chaos-runs-20261122-1317/kill-1/INVALID.txt`), and its timing figures are not used anywhere. The drain now uses the three conditions above, and the run that replaced it drained fully. (c) The dispatch `PingTest` was a full `@SpringBootTest` that connected to the live database and ran Flyway against it. It is now a `@WebMvcTest` slice, and it passes with an unreachable database URL. The tracking `PingTest` is also a full `@SpringBootTest`, but tracking has no database dependency, so it cannot reach one.

**What the chaos runs show.** Dispatch loses no order or event across three hard kills per run, once it has drained. Redeliveries after a kill are rejected by the processed-events table (3 in one kill run, 0 in the other). Assignment latency in the kill runs rises to about 73–75 s at the median, because events wait for the consumer to rejoin; the controls stay at about 0.6 s.

**What they do not show.** One dispatch instance on one VM, with only kills by `docker kill`. Two runs per condition, so the spread is between two runs only. The rate is 20 req/s, which is the rate of the baseline; higher rates were not measured, so no capacity claim is made. Recovery numbers are specific to the 45 s session timeout and the 15 s restart gap in this series. The pending orders (1 to 11 per run) are at 3 km from the pickup, with no free rider in the radius for most of them; those are supply effects at this density, not measured limits of the search.

### Static membership: applied, and the recovery comparison

**The change.** The dispatch consumer now has a `group.instance.id` (static membership), set from `DISPATCH_INSTANCE_ID` and defaulting to the container hostname, which stays the same across `docker kill` and `docker start`. Nothing else was changed: the session timeout (45 s), the heartbeat and the other settings are as before. The same procedure ran: reset, 20 s warm-up, reset, three `docker kill`s at +20, +60 and +100 s, each restarted 15 s later, controls with no kill, the drain check and the invariants after every run. Results: `loadtest/results/week5-chaos-static-20261122-1357/`.

| Run | Orders | Assignments | Pending | Balance | Redeliveries rejected | Riders BUSY without active | Assignment lag p50 / p95 (s) | Restart to partitions assigned, per kill (s) | Restart to first event processed, per kill (s) | Drain after load end (s) | Invariants total |
|---|---:|---:|---:|---:|---:|---:|---|---|---|---:|---:|
| static-kill-1 | 3001 | 2994 | 7 | 0 | 0 | 0 | 1.008 / 19.378 | 3.3, 3.1, 3.1 | 3.4, 3.1, 3.2 | 0 | 0 |
| static-control-1 | 3001 | 2996 | 5 | 0 | 0 | 0 | 0.607 / 0.99 | n/a (no kill) | n/a (no kill) | 0 | 0 |
| static-kill-2 | 3000 | 2996 | 4 | 0 | 0 | 0 | 1.389 / 20.299 | 4.4, 3.1, 3.1 | 4.4, 3.2, 3.1 | 0 | 0 |
| static-control-2 | 3001 | 2990 | 11 | 0 | 0 | 0 | 0.615 / 0.989 | n/a (no kill) | n/a (no kill) | 0 | 0 |

**What it shows, as measured.** After a restart, the partitions were assigned in 3.1–4.4 s, against 38.7–39.3 s for the last kill in the earlier series (and 78.7–79.3 s and 118.8–119.1 s for the first two kills there). The static member takes its partitions back at once, so the wait for the old session is gone. Redeliveries rejected by `processed_events` were 0 in all four runs; in the earlier series, one kill run had 3. Assignment lag at the median stays near 1 s in the kill runs, but the 95th percentile rises to about 19–20 s, because events that arrived while dispatch was down wait for the restart. Controls are unchanged at about 0.6 s.

**Per run, the end state.** Invariants: all zero in every run. Orders created minus assignments minus pending: zero in every run, so no order is lost. Orders left unassigned at the end (pending, 4 to 11 per run): in the sample of pending orders, most had no free rider within 3 km of the pickup (the supply limit of the seeded riders at this density); one or two pending orders per control had a free rider in radius, but outside the candidate window of the search. Riders left BUSY without an active assignment: zero in every run.

**What it does not show.** The drain reads 0 s after the load ended in every run, so the backlog was already processed before the load stopped. The time at which the backlog cleared after the last restart was not recorded, so I cannot give it. Two runs per condition, one dispatch instance, one VM, kills by `docker kill` only.

**Caveats for the static membership.** Every dispatch instance needs a unique `group.instance.id`: two instances with the same id fence each other, and the second one is rejected. The default (the container hostname) is unique per container, but a multi-instance deployment must set `DISPATCH_INSTANCE_ID` explicitly. A restart that lasts longer than the session timeout (45 s here) loses the benefit: the coordinator removes the silent member after the session expires and rebalances, and the partitions move during the outage. The tested restarts were 15 s, well under that. Spring Kafka appends a consumer index to the id (`-0` here), which is stable across restarts of the same container.

**The other options, still not applied.** (1) A lower `session.timeout.ms`, for example 10–15 s: it shortens recovery for every restart, but a GC or load pause longer than the session triggers a false rebalance, and every rebalance pauses consumption. (2) A graceful stop (SIGTERM, which leaves the group) in place of `docker kill`: planned restarts then skip the session wait, but a crash, which is what a kill models, still waits for the session. Static membership is the only option that helps a crash restart in these runs.

## Week 6: admission capacity

**Setup.** Creates through admission (`POST /orders`, `loadtest/week6/create-ramp.js`) at a constant arrival rate, 30 s per run, with a 20 s discarded warm-up at the same rate. Each run starts from a reset (order outbox drained, `loadtest/db/seed.sh` with `EMPTY=1`), and admission is restarted with the shedding flag for that run. The limiter stays on, with about 100,000 client IDs so no single client is limited; the shedding policy and the circuit breaker are at their defaults. Dispatch was stopped for the whole series, so this measures the create path only. k6 runs in the compose network. Results: `loadtest/results/week6-capacity-20261122-1419/`.

**Process note.** The ramp was meant to stop at the first rate with more than 5% non-2xx. A bug in my series script (the status line was captured with the value, so the comparison never fired) let the ramp run on to 4000 req/s. The rates 2400, 3200 and 4000 were run anyway; they are marked "after the stop point" and are not part of the ramp decision. The decision uses the 800 and 1600 runs only, which were valid. The required repeats (800 and 1600, three runs each) and the shedding-off run at 1600 followed.

| Run | Rate | Shedding | Achieved req/s | Dropped iter. | Status breakdown (adds to 100%) | Accepted p50 / p95 / p99 (ms) | Postgres CPU mean / peak % | Order Hikari pending peak / mean | Admission in-flight peak / mean | k6 CPU mean / peak % |
|---|---:|---|---:|---:|---|---|---|---|---|---|
| ramp-800 | 800 | shed on | 800.0 | 0 | 201 100.00% | 1.4 / 4.8 / 60.6 | 44 / 78 | 0 / 0.0 | 2 / 1.1 | 24 / 30 |
| repeat-800-2 | 800 | shed on | 799.8 | 0 | 201 100.00% | 1.2 / 6.2 / 76.6 | 39 / 64 | 0 / 0.0 | 3 / 1.2 | 25 / 48 |
| repeat-800-3 | 800 | shed on | 800.0 | 0 | 201 100.00% | 1.1 / 2.0 / 3.8 | 32 / 38 | 0 / 0.0 | 3 / 1.2 | 20 / 23 |
| ramp-1600 | 1600 | shed on | 1585.3 | 103 | 201 82.62%, 503 shed 17.38% | 98.7 / 282.3 / 394.1 | 241 / 304 | 173 / 111.6 | 194 / 140.3 | 66 / 87 |
| repeat-1600-2 | 1600 | shed on | 1584.6 | 457 | 201 97.28%, 503 shed 2.72% | 2.1 / 43.6 / 213.0 | 94 / 111 | 0 / 0.0 | 39 / 7.8 | 52 / 66 |
| repeat-1600-3 | 1600 | shed on | 1599.9 | 0 | 201 100.00% | 1.8 / 5.2 / 25.3 | 88 / 103 | 0 / 0.0 | 7 / 3.8 | 47 / 54 |
| shed-off-1600 | 1600 | shed off | 1578.0 | 651 | 201 100.00% | 2.5 / 102.0 / 366.1 | 95 / 121 | 11 / 1.4 | 21 / 7.4 | 58 / 67 |
| after-stop-2400 | 2400 | shed on, after the stop point | 2378.5 | 624 | 201 97.65%, 503 shed 2.35% | 4.3 / 76.8 / 180.1 | 116 / 129 | 56 / 11.2 | 54 / 21.1 | 80 / 94 |
| after-stop-3200 | 3200 | shed on, after the stop point | 3117.8 | 2442 | 201 91.03%, 503 shed 8.97% | 10.2 / 117.0 / 212.1 | 123 / 160 | 151 / 60.8 | 214 / 79.6 | 96 / 134 |
| after-stop-4000 | 4000 | shed on, after the stop point | 3778.7 | 5894 | 201 59.11%, 503 shed 40.89% | 66.2 / 196.2 / 344.6 | 164 / 191 | 151 / 84.0 | 321 / 193.9 | 125 / 133 |

**Where failures begin.** At 800 req/s: no failures in three runs. At 1600 req/s: the rate is the first that failed the 5% rule, but only once out of three runs (17.38%, 2.72% and 0.00%). The failures were all `503 shed`; no pool timeout, circuit-open or `other` answer appeared at this rate. So the 1600 rate is at the edge of the capacity, not clearly past it.

**Which resource saturates first.** Postgres, and the order service's connection pool behind it. In the run that failed at 17.38%, Postgres used 241% CPU on average (of 600% for the VM), the order pool queued up to 173 waiting requests, and admission spent about 20 s at HIGH and HARD_CAP, which is where shedding starts to hit CRITICAL requests too. In the runs at 1600 that passed, Postgres used 88–94% on average and the order pool never queued. At 1600 req/s, admission's peak CPU was 83–95% and Redis peaked at 10–13%; in the runs after the stop point admission peaked at 121–192% and Redis at 12–27%. So the first saturating resource is Postgres (through the order pool). I cannot say why one run saturated and the others did not: the autovacuum and checkpoint counts were not recorded for these runs, and that is the first thing to check. The admission's upstream connection pool is not exposed as a metric, so its use was not measured.

**Is k6 a limit?** Partly, at the top of the ramp. Dropped iterations appear from 2400 req/s up, and k6's own CPU averages 80% at 2400, 96% at 3200 and 125% (peak 133%) at 4000. The numbers at 3200 and 4000 are therefore not clean measurements of admission: k6 could not send the full rate. At 1600 k6 used 47–66% of a core, so k6 was not the limit there, although one run dropped 457 iterations.

**What shedding does.** With shedding off at 1600 req/s, every request was answered 201 (no 503), but the accepted p99 was 366 ms and k6 dropped 651 iterations. With shedding on, the failing run answered 17% of requests with 503 and kept its accepted p99 at 394 ms. So shedding trades slow answers for fast refusals, and does not raise the throughput limit.

**The causes behind "other".** None in the timed runs, except one logged `org.apache.hc.core5.util.DeadlineTimeoutException` in the shedding-off run. The admission log is rate-limited, so this is the count the log covers, not a total.

**What these numbers do not show.** One VM, with admission, order, Postgres, Redis and k6 sharing six vCPUs. At most three runs per rate, so the spread is between three runs and says little about the true edge. A single 30 s run per rate after the stop point. No dispatch load. The results depend on the state of Postgres at the start of each run, which the reset controls only partly.

### Step 2 A: the 1600 req/s intermittency (diagnosis only, nothing applied)

Three more runs at 1600 req/s with the step 1 procedure, with Postgres counters before and after each run (`loadtest/week6/run-ramp.sh`, now records `pg-before.txt` and `pg-after.txt`). Results: `loadtest/results/week6-diag-1600-20261122-1509/`. Counters are the deltas across the timed run; the table is in that folder's `diag-*/analysis.txt`.

| Run | 201 | 503 shed | Accepted p50 / p95 / p99 (ms) | Postgres CPU mean / peak % | Order Hikari pending peak | Checkpoints (timed / requested) during run | Autovacuum runs during run |
|---|---:|---:|---|---|---|---|---|
| diag-1 | 87.83% | 12.17% | 55.1 / 230.7 / 326.2 | 262 / 369 | 178 | +1 timed / +0 | 0 |
| diag-2 | 100.00% | 0.00% | 1.7 / 3.6 / 6.9 | 97 / 140 | 0 | +0 / +0 | +4 |
| diag-3 | 99.04% | 0.96% | 2.0 / 12.0 / 90.3 | 87 / 110 | 1 | +0 / +0 | 0 |

**Reading.** The 503 share ranges from 0.00% to 12.17% across three runs at the same rate. The run with the high share (diag-1) is the only one with a timed checkpoint during the run, and the only one where Postgres averaged over 200% CPU and the order pool queued. That is a coincidence in one run, not a demonstrated cause: diag-2 had autovacuum activity and no checkpoint and was clean, and diag-3 had neither and still shed about 1%. With three runs I cannot separate the two. Nothing here shows which resource starts the queue.

**Options, not applied.** Each is a hypothesis to test in a later step, with its trade-off:
- `max_wal_size` (larger means fewer requested checkpoints under write bursts; costs more WAL on disk and longer crash recovery).
- `checkpoint_completion_target` (spreads checkpoint writes over more of the interval; can lengthen the period during which dirty pages are being written).
- `shared_buffers` (a larger buffer cache reduces reads; uses more RAM that the other containers on this 6 vCPU VM also need).
- Autovacuum thresholds on the `orders` and `outbox` tables (earlier vacuum keeps dead tuples low; more frequent vacuum work during the run).

### Step 2 B: order, dispatch and tracking ports are internal to the compose network

Only admission (8080), the monitoring ports (Prometheus 9090, Grafana 3000) and the data stores on their existing mappings (Postgres 15432, Redis 6379, Redpanda 19092, MinIO 9000/9001) are published to the host. `order` (8081), `dispatch` (8082) and `tracking` (8083) publish nothing now. Prometheus and k6 already reached them by service name.

- `docker-compose.yml`: removed the `ports:` entries for order, dispatch and tracking. `docker compose config -q` passes.
- Scripts that read those services' metrics from the host now read them from inside the container through `loadtest/http-in-network.sh <service> <port> <path>` (`docker compose exec` with a bash `/dev/tcp` GET): `loadtest/sample.py`, `loadtest/week6/sampler.py`, `loadtest/run-nye.sh`, `loadtest/chaos/run-chaos.sh`, `loadtest/chaos/reset.sh`. Admission's upstream address in the compose file is `http://order:8081`.
- Checked after recreating the three containers: `curl localhost:8081`, `8082`, `8083` all get no answer; the helper returns `{"status":"UP"}` for order and tracking, and `{"status":"ok","service":"dispatch"}` for dispatch's ping.
- Smoke test through admission only: `POST /orders` at `localhost:8080` returned `201` with an `orderId`, and `GET /orders/{id}` through admission returned `200` with the order in `CREATED`.

### Step 2 C: final runs (in progress)

Every run uses `loadtest/week6/run-step2.sh` (one procedure for all modes): pre-run check, order outbox drained, tables reset (`loadtest/db/seed.sh` with `EMPTY=1`), a discarded warm-up at the same load (20 s; for the NYE curve, its 30 s calm stage), reset again, a second pre-run check, Postgres counters before and after, the timed run with the sampler, drain, and a per-run `reset.txt`, `precheck` and `counts.txt`. Dispatch is stopped for every run in this step, so these runs measure the create path only. Results: `loadtest/results/week6-step2-20261122-1518/`.

**Invariants (checked after every run, `loadtest/week6/analyse_step2.py`).** These replace the first version, which required orders to equal the 201 answers exactly.
- (a) No duplicate `(client_id, idempotency_key)` pair. The table's primary key already enforces this; the count is recorded at the end of each run from this step on.
- (b) The number of 201 answers is at most the number of orders created.
- (c) Orders created minus 201 answers is at most the number of POST answers that were not 201.
- (d) No unpublished order-outbox row at the end.

**Unknown outcome.** A POST that times out or gets a 500 or 504 can still have created its order: the order commits, and the client does not see the 201. A client that retries with the same `Idempotency-Key` gets that order back. The order service stores the first response with the key and answers a replay with status 200 and the stored body (`OrderService.createOrder`), so the retry is not a second order. Invariant (c) allows for this.

**NYE run `nye-off-1` (shedding off, POST peak 2000 req/s).** It passes the corrected invariants. Orders created 67,220; POST 201 answers 67,219; so one order exists without a 201 answer, and the POST answers that were not 201 number 80,566 (80,530 × 503, 35 × 504, 1 × 500). Duplicate pairs: 0. Outbox unpublished at end: 0. The 67,219 201 answers include 400 seed orders that k6's `setup()` created before the curve started, so the curve itself created 66,819 orders.
- The extra order is an unknown outcome: one POST got a 500 or 504 but its order was written. The admission log has no request IDs, so I can't name the request. The 500 came from the order service, not admission: `OrderController` has no handler for the exception, so Spring answers 500 and admission passes that status through. The order service logged 24 errors in the second of that 500, all `org.springframework.transaction.CannotCreateTransactionException: Could not open JDBC Connection for transaction`, with `java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 2000ms` as the root cause (Hikari pool of 10, `connection-timeout` 2000 ms). The frame that throws is `TransactionAspectSupport.invokeWithinTransaction`, at the start of the `@Transactional` `OrderService.createOrder`. Admission's `admission_requests_total{outcome="server_error"}` counts this one 500 as passed through. The 504s are admission's `read_timeout` (`SocketTimeoutException` mapped to 504 by `UpstreamException.Type.READ_TIMEOUT`; 38 across all methods, the same as the 35 POST, 2 GET and 1 PATCH 504s). The `DeadlineTimeoutException` lines in the log are a different path: `OTHER`, answered 503 ("the order service call failed").
- The order pool is the saturated resource in this run: all 10 connections busy and up to 184 requests waiting.
- **The POST 503s are mostly the circuit breaker, not shedding.** With shedding off, `admission_shed_total` is 0. Admission's counters for the run show `circuit_open` 78,049 for CRITICAL (the POST class) and 4,772 for STANDARD, against 73,620 CRITICAL successes. Upstream errors were `other` 1,608 and `pool_timeout` 1,055 (both answered 503), and `read_timeout` 38 (504). So in this run the 503 share is the Week 5 breaker opening, and the shedding comparison is not a comparison of shedding alone: the breaker is on in both conditions. The breaker opened 7 times during the run (`admission_circuit_transitions_total`: 7 to open, 7 to half-open, 7 to closed; state closed at the end).
- **Analysis fix.** The first version of `analyse_step2.py` counted every non-201, non-429, non-503 answer as "other", so the GET and PATCH 200s in this run were counted as create failures (the "other" figure was 181,247, or 53%). That was wrong. The corrected breakdown counts POST answers only: 201 45.48%, 429 0%, 503 54.49%, other 0.03%; GET and PATCH are reported by status in their own table. I re-ran the analysis for every existing run in this folder. The figures for the POST-only runs are unchanged; only the labels and invariant wording changed, and the NYE run's numbers changed as shown above (its old "other" and 503 shares are superseded).
- **k6 saturated at the NYE peak.** k6 dropped 202,253 iterations and its CPU averaged 122% (peak 365%). The peak POST rate was therefore not delivered as scheduled. The shedding-on versus shedding-off comparison uses the same generator and the same schedule, so the two conditions face the same limit. The rates are the ones this VM and this generator produced, not claims about the service's ceiling.

**Direct order ceiling (`MODE=direct`, POST straight to the order service on the compose network; admission not in the path).** One column per run; the rate is the target over 30 s.

| Run | Scheduled | Sent | Dropped | 201 | 429 | 503 | other | Accepted p50 / p95 / p99 (ms) | Postgres CPU mean / peak % |
|---|---:|---:|---:|---:|---:|---:|---:|---|---|
| direct-2000-1 (first after recreation) | 60000 | 44324 | 15681 (26.14%) | 44324 | 0 | 0 | 0 | 522.9 / 3328.1 / 3539.3 | 333.8 / 396.1 |
| interleave-2000-a | 60000 | 60002 | 0 (0.00%) | 60002 | 0 | 0 | 0 | 1.1 / 2.0 / 3.7 | 96.9 / 120.4 |
| interleave-2000-b | 60000 | 60002 | 0 (0.00%) | 60002 | 0 | 0 | 0 | 1.1 / 2.0 / 3.7 | 95.5 / 117.8 |
| interleave-2000-c | 60000 | 60000 | 0 (0.00%) | 60000 | 0 | 0 | 0 | 1.2 / 2.3 / 4.8 | 107.6 / 118.5 |
| direct-3200-1 | 96000 | 95129 | 872 (0.91%) | 95129 | 0 | 0 | 0 | 1.7 / 18.5 / 148.7 | 139.3 / 151.9 |
| interleave-3200-a | 96000 | 96003 | 0 (0.00%) | 96003 | 0 | 0 | 0 | 1.5 / 4.7 / 16.6 | 133.4 / 151.0 |
| interleave-3200-b | 96000 | 36519 | 59482 (61.96%) | 36519 | 0 | 0 | 0 | 2840.3 / 4607.8 / 4995.4 | 382.8 / 480.9 |
| direct-4400-1 | 132000 | 40760 | 91225 (69.11%) | 40753 | 0 | 0 | 7 | 2399.1 / 4266.0 / 4709.7 | 269.6 / 417.6 |
| interleave-4400-a | 132000 | 131472 | 529 (0.40%) | 131472 | 0 | 0 | 0 | 5.1 / 77.0 / 139.6 | 157.4 / 177.3 |
| phase2-4400-b | 132000 | 130762 | 1241 (0.94%) | 130762 | 0 | 0 | 0 | 38.3 / 150.0 / 211.7 | 171.7 / 176.0 |

Invariants hold in all ten runs. The `other` 7 in direct-4400-1 is k6 requests that got no status (client-side failures); they are not orders. Run labels: `direct-*` is the first run at that rate in the batch, `interleave-*` the repeats interleaved with other rates, `phase2-*` the third run at 4400 req/s.

**Pass rule.** A run passes if the accepted (201) p99 is below 200 ms, fewer than 1% of the scheduled requests are dropped, and there are zero non-2xx answers. This rule was set after the first run at 4400 req/s, and applied to every direct run below.

| Rate (req/s) | Run | Dropped (of scheduled) | Non-2xx | Accepted p99 (ms) | Result |
|---|---|---:|---:|---:|---|
| 2000 | direct-2000-1 (first after recreation) | 15681 (26.14%) | 0 | 3539.3 | fail |
| 2000 | interleave-2000-a | 0 (0.00%) | 0 | 3.7 | pass |
| 2000 | interleave-2000-b | 0 (0.00%) | 0 | 3.7 | pass |
| 2000 | interleave-2000-c | 0 (0.00%) | 0 | 4.8 | pass |
| 2000 | phase2b-2000-d | 0 (0.00%) | 0 | 6.7 | pass |
| 3200 | direct-3200-1 | 872 (0.91%) | 0 | 148.7 | pass |
| 3200 | interleave-3200-a | 0 (0.00%) | 0 | 16.6 | pass |
| 3200 | interleave-3200-b | 59482 (61.96%) | 0 | 4995.4 | fail |
| 3200 | phase2b-3200-c | 0 (0.00%) | 0 | 22.3 | pass |
| 4400 | direct-4400-1 | 91225 (69.11%) | 7 | 4709.7 | fail |
| 4400 | interleave-4400-a | 529 (0.40%) | 0 | 139.6 | pass |
| 4400 | phase2-4400-b | 1241 (0.94%) | 0 | 211.7 | fail (p99) |
| 4400 | phase2b-4400-c | 87535 (66.31%) | 0 | 3650.0 | fail |
| 4400 | phase2b-4400-d | 95095 (72.04%) | 0 | 4707.1 | fail |
| 4400 | phase2b-4400-e | 95160 (72.09%) | 0 | 4783.9 | fail |

**Passes per rate.** 2000 req/s: **4 of 5** runs passed. 3200 req/s: **3 of 4** passed. 4400 req/s: **1 of 6** passed.

**Result.** The highest rate at which most runs pass is **3200 req/s** (3 of 4). At 4400 req/s, one run in six passes, so 4400 req/s is past the ceiling on this setup, and the 2000 to 4400 req/s range is intermittent. The runs that failed at 3200 and 4400 req/s have Postgres CPU averaging 270–383%; the passing runs have 95–172%. The cause of that spike is not found: checkpoints did not fall within any timed run, pre-run idle CPU does not separate the runs, and autovacuum appears in both passing and failing runs.

**Historical reference (Week 1, before the fixes in this README).** Direct `POST /orders` to the order service, 30 s runs, k6 in the compose network, from `loadtest/results/order-create/week1-20261121-1418/`: 5200 req/s passed and 5600 req/s failed (two runs each). These were run on the Week 1 code, before the Week 4 outbox partitioning and later order-service changes, so the rates are not directly comparable to the runs in this step. Week 1 also sent order bodies without pickup coordinates.
