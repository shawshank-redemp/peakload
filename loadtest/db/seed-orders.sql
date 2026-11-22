-- Realistic order data for the Week 4 database baseline. Run with:
--   docker compose exec -T postgres psql -U peakload -d peakload -v n=1000000 -f - < loadtest/db/seed-orders.sql
-- (or loadtest/db/seed.sh, which also truncates the dispatch tables and checks the counts).
--
-- What it writes (the real schema; no schema change):
--   orders            n rows. created_at spread over the last 365 days, weighted to lunch (11-14h) and dinner
--                     (19-22h). Status mix: for orders older than 24 h, 95% DELIVERED and 5% CANCELLED. For orders
--                     in the last 24 h: CREATED 3%, CONFIRMED 5%, PREPARING 7%, READY 8%, PICKED_UP 12%,
--                     DELIVERED 60%, CANCELLED 5%. version = number of status steps taken.
--   outbox            one OrderCreated per order and one OrderStatusChanged per status step, all published
--                     (published_at = created + 2 s). About 5.7 rows per order.
--   processed_events  one row per outbox event: the order service consumes its own event stream (see
--                     OrderEventConsumer), so every event is processed once.
--   idempotency_keys  one row per order created in the last 24 h only. Older keys are removed by the cleanup job
--                     (TTL PT24H), so they would not be there.
-- The random sequence is fixed (setseed), and parallel query is off, so the same n gives the same data.
\set ON_ERROR_STOP on
\timing on
SET max_parallel_workers_per_gather = 0;
SET synchronous_commit = off;
SELECT setseed(0.2026);

BEGIN;
TRUNCATE orders, idempotency_keys, outbox, processed_events;

-- UUIDv7 with the timestamp in the first 48 bits, as the application makes them (time-ordered index inserts).
CREATE OR REPLACE FUNCTION pg_temp.uuid_v7(ts timestamptz) RETURNS uuid LANGUAGE sql VOLATILE AS $$
  SELECT (lpad(to_hex((extract(epoch FROM ts) * 1000)::bigint), 12, '0')
       || '7' || substr(md5(random()::text), 1, 3)
       || '8' || substr(md5(random()::text), 1, 3)
       || substr(md5(random()::text), 1, 12))::uuid
$$;

CREATE TEMP TABLE cities (name text, lat double precision, lon double precision, cum double precision);
INSERT INTO cities VALUES
  ('Pune',       18.52, 73.86, 0.22),
  ('Mumbai',     19.08, 72.88, 0.42),
  ('Delhi',      28.61, 77.21, 0.58),
  ('Bengaluru',  12.97, 77.59, 0.70),
  ('Chennai',    13.08, 80.27, 0.80),
  ('Hyderabad',  17.39, 78.49, 0.88),
  ('Kolkata',    22.57, 88.36, 0.95),
  ('Ahmedabad',  23.02, 72.57, 1.00);

CREATE TEMP TABLE base AS
SELECT g AS n,
       floor(random() * 365)::int AS day_back,
       random() AS r_slot, random() AS r_hour, random() AS r_minute,
       random() AS r_city, random() AS r_status, random() AS r_amount,
       random() AS r_cust, random() AS r_client, random() AS r_lat, random() AS r_lon
FROM generate_series(1, :n) AS g;

CREATE TEMP TABLE plan AS
WITH t AS (
  SELECT b.*,
         LEAST(
           date_trunc('day', now()) - b.day_back * interval '1 day'
             + (CASE WHEN b.r_slot < 0.20 THEN 11 + floor(b.r_hour * 4)     -- lunch
                     WHEN b.r_slot < 0.55 THEN 19 + floor(b.r_hour * 4)     -- dinner
                     ELSE floor(b.r_hour * 24) END) * interval '1 hour'
             + b.r_minute * interval '1 hour',
           now() - interval '1 minute') AS created_at
  FROM base b
), c AS (
  SELECT t.*, ci.name AS city, ci.lat AS c_lat, ci.lon AS c_lon
  FROM t
  JOIN LATERAL (SELECT * FROM cities WHERE cum > t.r_city ORDER BY cum LIMIT 1) ci ON true
), s AS (
  SELECT c.*,
         CASE WHEN created_at < now() - interval '24 hours' THEN
                CASE WHEN r_status < 0.95 THEN 'DELIVERED' ELSE 'CANCELLED' END
              ELSE
                CASE WHEN r_status < 0.03 THEN 'CREATED'
                     WHEN r_status < 0.08 THEN 'CONFIRMED'
                     WHEN r_status < 0.15 THEN 'PREPARING'
                     WHEN r_status < 0.23 THEN 'READY'
                     WHEN r_status < 0.35 THEN 'PICKED_UP'
                     WHEN r_status < 0.95 THEN 'DELIVERED'
                     ELSE 'CANCELLED' END
         END AS status
  FROM c
), v AS (
  SELECT s.*,
         CASE status WHEN 'CREATED' THEN 0 WHEN 'CONFIRMED' THEN 1 WHEN 'PREPARING' THEN 2 WHEN 'READY' THEN 3
                     WHEN 'PICKED_UP' THEN 4 WHEN 'DELIVERED' THEN 5 WHEN 'CANCELLED' THEN 2 END AS version,
         'c-' || floor(r_cust * 400000)::int AS cust,
         CASE WHEN r_client < 0.50 THEN 'app-ios' WHEN r_client < 0.85 THEN 'app-android'
              WHEN r_client < 0.95 THEN 'web' ELSE 'partner-api' END AS client_id,
         150 + floor(r_amount * r_amount * 9000)::int AS amount
  FROM s
)
SELECT n,
       pg_temp.uuid_v7(created_at) AS id,
       client_id, status, version, cust, city, amount, created_at,
       created_at + version * interval '6 minutes' AS updated_at,
       c_lat + (r_lat - 0.5) * 0.12 AS pickup_lat,
       c_lon + (r_lon - 0.5) * 0.12 AS pickup_lon
FROM v;

INSERT INTO orders (id, client_id, status, version, payload, created_at, updated_at, pickup_lat, pickup_lon)
SELECT id, client_id, status, version,
       jsonb_build_object('city', city, 'customerId', cust, 'totalPaise', amount,
                          'pickupLat', pickup_lat, 'pickupLon', pickup_lon),
       created_at, updated_at, pickup_lat, pickup_lon
FROM plan;

-- One OrderCreated (k = 0) and one OrderStatusChanged per later step (k = 1..version), all published.
INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload, created_at, published_at)
SELECT pg_temp.uuid_v7(ev.ts), 'order', p.id,
       CASE WHEN k = 0 THEN 'OrderCreated' ELSE 'OrderStatusChanged' END,
       jsonb_build_object('orderId', p.id,
                          'status', CASE WHEN k = p.version THEN p.status
                                         ELSE (ARRAY['CREATED','CONFIRMED','PREPARING','READY','PICKED_UP','DELIVERED'])[k + 1] END,
                          'version', k, 'city', p.city),
       ev.ts, ev.ts + interval '2 seconds'
FROM plan p
CROSS JOIN LATERAL generate_series(0, p.version) AS k
CROSS JOIN LATERAL (SELECT p.created_at + k * interval '6 minutes' AS ts) ev;

INSERT INTO processed_events (event_id, event_type, aggregate_id, processed_at)
SELECT id, event_type, aggregate_id, published_at + interval '1 second'
FROM outbox;

INSERT INTO idempotency_keys (client_id, idempotency_key, request_hash, order_id, response_status, response_body,
                              created_at, expires_at)
SELECT p.client_id, gen_random_uuid()::text,
       encode(sha256(convert_to(p.id::text || p.amount::text, 'UTF8')), 'hex'),
       p.id, 201,
       jsonb_build_object('orderId', p.id, 'status', 'CREATED', 'customerId', p.cust,
                          'city', p.city, 'totalPaise', p.amount),
       p.created_at, p.created_at + interval '24 hours'
FROM plan p
WHERE p.created_at > now() - interval '24 hours';

COMMIT;

ANALYZE orders;
ANALYZE outbox;
ANALYZE processed_events;
ANALYZE idempotency_keys;

SELECT 'orders' AS table_name, count(*) FROM orders
UNION ALL SELECT 'outbox', count(*) FROM outbox
UNION ALL SELECT 'processed_events', count(*) FROM processed_events
UNION ALL SELECT 'idempotency_keys', count(*) FROM idempotency_keys;
