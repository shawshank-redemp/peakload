# ADR-0001: Stack and service split

- Status: accepted
- Date: 2026-11-19

## Problem
Pick a stack and a service boundary that make the real trade-offs (consistency, overload, cost) visible, without hiding them behind a framework.

## Options considered
- **Language:** Java 25 + Spring Boot (chosen) vs Node/TypeScript vs Go.
- **Orders store:** PostgreSQL (chosen) vs MongoDB/DynamoDB vs MySQL.
- **Event bus:** Redpanda (Kafka API, chosen) vs RabbitMQ vs SQS vs Redis Streams.
- **Shape:** four services (chosen) vs modular monolith vs many microservices.

## Evidence
To be filled with measurements as each week lands (see the README plan).

## Decision
Java 25 + Spring Boot for a strong concurrency story; Postgres because orders and assignment need ACID; Redis for rate limits, cache and rider candidates; Redpanda for a replayable, partition-ordered log; four services (admission, order, dispatch, tracking) so that overload protection, order correctness and the consumer side can fail and scale independently. Java 25 is the selected LTS runtime. Spring Boot remains on the 3.x line; its baseline is 3.5.x because the previous 3.3.x stack's Spring ASM cannot parse Java 25 class files. Other stack decisions are unchanged.

## Cost (the honest part)
A single Postgres node bounds write throughput, and four services add operational overhead that a modular monolith would not have. Both are measured and documented in later ADRs.
