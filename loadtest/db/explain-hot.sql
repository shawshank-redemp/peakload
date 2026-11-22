-- EXPLAIN (ANALYZE, BUFFERS) for each hot query of the order service. Writes run inside a transaction that is rolled
-- back, so the data is not changed. Parameters come from one real seeded order (passed with -v by the driver):
--   :'oid' (order id), :'ver' (its version), :'st' (its status), :'cid' (its client id), :'key' (its idempotency key).
-- Run: psql -v oid=... -v ver=... -v st=... -v cid=... -f loadtest/db/explain-hot.sql

\echo '== 1. order insert (POST /orders, inside the create transaction)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
INSERT INTO orders (id, client_id, status, version, payload, pickup_lat, pickup_lon)
VALUES (gen_random_uuid(), 'loadtest', 'CREATED', 0, '{"city":"Pune"}'::jsonb, 18.5, 73.8);
ROLLBACK;

\echo '== 2. idempotency key insert (ON CONFLICT DO NOTHING)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
INSERT INTO idempotency_keys (client_id, idempotency_key, request_hash, expires_at)
VALUES ('loadtest', gen_random_uuid()::text, 'h', now() + interval '24 hours')
ON CONFLICT (client_id, idempotency_key) DO NOTHING;
ROLLBACK;

\echo '== 3. idempotency key lock read (SELECT ... FOR UPDATE, after a conflict)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
SELECT request_hash, response_body::text, expires_at <= now() AS expired
FROM idempotency_keys
WHERE client_id = :'cid' AND idempotency_key = :'key'
FOR UPDATE;
ROLLBACK;

\echo '== 4. outbox insert (same transaction as the order)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
VALUES (gen_random_uuid(), 'order', :'oid', 'OrderCreated', '{"orderId":"x"}'::jsonb);
ROLLBACK;

\echo '== 5. relay batch select (FOR UPDATE SKIP LOCKED, 100 rows), with 100 unpublished rows present'
BEGIN;
INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
SELECT gen_random_uuid(), 'order', :'oid', 'OrderCreated', '{}'::jsonb FROM generate_series(1, 100);
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, aggregate_id, event_type, payload::text
FROM outbox
WHERE published_at IS NULL
ORDER BY created_at, id
LIMIT 100
FOR UPDATE SKIP LOCKED;
ROLLBACK;

\echo '== 6. status update (conditional on version and status)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
UPDATE orders SET status = 'CONFIRMED', version = version + 1, updated_at = now()
WHERE id = :'oid' AND version = :ver AND status = :'st';
ROLLBACK;

\echo '== 7. GET order by id'
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, payload->>'customerId' AS customer_id, payload->>'city' AS city, status,
       (payload->>'totalPaise')::bigint AS total_paise, version, created_at, updated_at
FROM orders
WHERE id = :'oid';

\echo '== 8. idempotency cleanup batch (expired keys, LIMIT 500)'
BEGIN;
EXPLAIN (ANALYZE, BUFFERS)
DELETE FROM idempotency_keys
WHERE (client_id, idempotency_key) IN (
    SELECT client_id, idempotency_key
    FROM idempotency_keys
    WHERE expires_at <= now()
    LIMIT 500);
ROLLBACK;
