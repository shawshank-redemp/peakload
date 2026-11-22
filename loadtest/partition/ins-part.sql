INSERT INTO ret_part (id, aggregate_type, aggregate_id, event_type, payload, created_at)
VALUES (gen_random_uuid(), 'order', gen_random_uuid(), 'OrderCreated', '{}'::jsonb, now());
