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
