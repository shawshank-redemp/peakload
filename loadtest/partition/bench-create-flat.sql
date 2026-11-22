-- Order-create transaction, flat outbox (pgbench custom script). One statement, so the three writes are one
-- transaction: the order, its idempotency row and its outbox row. Experimental database only.
\set amt random(150, 9000)
WITH o AS (
  INSERT INTO orders (id, client_id, status, version, payload, pickup_lat, pickup_lon)
  VALUES (gen_random_uuid(), 'bench', 'CREATED', 0, jsonb_build_object('city', 'Pune', 'totalPaise', :amt), 18.5, 73.8)
  RETURNING id
), i AS (
  INSERT INTO idempotency_keys (client_id, idempotency_key, request_hash, order_id, response_status, response_body, expires_at)
  SELECT 'bench', gen_random_uuid()::text, 'h', id, 201, '{"status":"CREATED"}'::jsonb, now() + interval '24 hours' FROM o
  RETURNING order_id
)
INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload)
SELECT gen_random_uuid(), 'order', id, 'OrderCreated', '{"orderId":"x"}'::jsonb FROM o;
