# Prompt to paste into Claude Code (Week 0)

You are helping me finish Week 0 of "Peakload", a portfolio project. The repo
already contains a scaffold. Read CLAUDE.md, README.md and docs/adr/0001-stack-and-service-split.md first.

Do these steps in order, and stop to tell me if anything fails in a way you can't fix minimally:

1. Verify the build. Run `mvn -B verify` from the repo root. The four services (admission, order,
   dispatch, tracking) were generated without being compiled, so fix any compile or test failure with the
   smallest possible change. Do not add features or dependencies beyond what is needed.
2. Verify the stack. Run `docker compose up --build -d`. Wait until infrastructure is healthy, then check:
   - `curl localhost:808{0,1,2,3}/api/ping` returns the right service name
   - `curl localhost:808{0,1,2,3}/actuator/health` returns UP
   - `curl localhost:808{0,1,2,3}/actuator/prometheus` returns metrics
   - Prometheus (localhost:9090/targets) shows all four targets UP
   - Grafana (localhost:3000) has the dashboard "Peakload overview"
   Fix compose or config problems you find (for example Redpanda healthcheck or startup flags).
3. Run the baseline load test:
   `docker run --rm -i --network host -e RATE=200 grafana/k6 run - < loadtest/baseline.js`
   Report the p95 and p99 latency and error rate it prints, and confirm the Grafana panels move.
   Record the numbers in the README "Results" section as a Week 0 baseline, with my machine's
   CPU/RAM and the exact command. Do not invent or round up numbers.
4. Initialize git on branch `main` if it isn't already, and make one commit: "Week 0 scaffold: compose, 4 services, monitoring, k6 baseline".
5. Give me a short report: what passed, what you changed and why, anything still unverified.

Constraints: do not implement Week 1 or later features. Keep the structure, ports and file names as they are.
