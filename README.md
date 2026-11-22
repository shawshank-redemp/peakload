# Peakload

A surge-resilient order and dispatch backend, load-tested on a New Year's Eve-shaped traffic curve.
The point of the project is the engineering trade-offs, each written up as an ADR with numbers.

> Status: **Week 0 scaffold.** Services only expose `/api/ping` and metrics. Results tables are empty until each milestone is measured.

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
| Order / Dispatch / Tracking | :8081 / :8082 / :8083 `/api/ping` |
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
