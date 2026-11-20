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
