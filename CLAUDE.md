# Peakload: context for Claude Code

A surge-resilient order and dispatch backend, built as an engineering portfolio project. The value is in measured trade-offs and ADRs, not in features.

## Stack (locked, see docs/adr/0001)
Java 21, Spring Boot 3.3.x (Maven multi-module), PostgreSQL 16, Redis 7, Redpanda (Kafka API), MinIO, Prometheus + Grafana (Micrometer), k6 via Docker, Testcontainers for tests, Flyway for migrations (from week 1).

## Services
admission (:8080, entry point: rate limiting, load shedding, cache), order (:8081), dispatch (:8082), tracking (:8083). Packages: `com.peakload.<service>`.

## Design decisions already made
- Postgres is the source of truth for orders and rider assignment. Redis GEO only suggests candidate riders.
- Event delivery is at-least-once (transactional outbox). Consumers deduplicate by event ID. Never claim exactly-once.
- Idempotency keys store a request hash and expire.
- Rate limiting (per client) and load shedding (system-wide, priority-based) are separate layers.
- Limiter fails open to a local in-memory fallback if Redis is down.

## Working rules
- Work one weekly milestone at a time (plan table in README.md). Do not build ahead.
- Every milestone ends with: something that runs with `docker compose up --build`, a recorded benchmark, and an ADR from `docs/adr/0000-template.md`.
- Never invent benchmark numbers. Fill README results and ADR evidence only from real runs, and state the machine and load used.
- The event-cost figure must be labeled "simulated" and use an explicit, documented cost model.
- Keep services stateless; all state lives in Postgres, Redis or the bus.
- Small, reviewable commits. Prefer simple code the author can explain line by line.
