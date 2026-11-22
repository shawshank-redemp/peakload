-- Relay claim-and-mark as one transaction per row (pgbench custom script), flat variant (before). The relay in the
-- service claims a batch and marks each row; this measures the database cost of one claim-and-mark step.
WITH c AS (
  SELECT id FROM outbox WHERE published_at IS NULL ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
)
UPDATE outbox o SET published_at = now() FROM c WHERE o.id = c.id;
