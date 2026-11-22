-- Fills the backlog for one relay replay run: 300,000 unpublished rows, created across the last 3 months,
-- so the claim has rows in more than one partition. Run once per variant before each replay run.
-- Parameter: -v tbl=outbox|outbox_p
INSERT INTO :tbl (id, aggregate_type, aggregate_id, event_type, payload, created_at)
SELECT gen_random_uuid(), 'order', gen_random_uuid(), 'OrderCreated', '{}'::jsonb,
       now() - (random() * interval '90 days')
FROM generate_series(1, 300000);
