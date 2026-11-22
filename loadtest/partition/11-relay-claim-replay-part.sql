-- Same claim-and-mark on outbox_p (after). The update names created_at, as the new relay does.
WITH c AS (
  SELECT id, created_at FROM outbox_p WHERE published_at IS NULL ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
)
UPDATE outbox_p o SET published_at = now() FROM c WHERE o.id = c.id AND o.created_at = c.created_at;
