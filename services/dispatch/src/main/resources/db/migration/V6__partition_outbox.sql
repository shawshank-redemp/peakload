-- Partitions the outbox by created_at (monthly), with a DEFAULT partition for rows outside the created months.
-- Why the primary key changes: a partitioned table's unique key must include the partition column, so the key is
-- (id, created_at). id is still unique in practice (UUIDv7) and consumers deduplicate by id as before.
-- Why the copy: the rows are moved in the same transaction as the table swap, so a failed migration leaves the
-- original table as it was. The old table is dropped at the end.
-- Partitions created here: the month of the oldest row (or the current month if empty) up to three months ahead.
-- OutboxPartitions creates later months and removes old, fully published ones.

CREATE TABLE outbox_partitioned (
    id              UUID         NOT NULL,
    aggregate_type  TEXT         NOT NULL,
    aggregate_id    UUID         NOT NULL,
    event_type      TEXT         NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

DO $$
DECLARE
    m          date := date_trunc('month', COALESCE((SELECT min(created_at) FROM outbox), now()))::date;
    last_month date := (date_trunc('month', now()) + interval '3 months')::date;
BEGIN
    WHILE m <= last_month LOOP
        EXECUTE format('CREATE TABLE outbox_p_%s PARTITION OF outbox_partitioned FOR VALUES FROM (%L) TO (%L)',
                       to_char(m, 'YYYY_MM'), m, (m + interval '1 month')::date);
        m := (m + interval '1 month')::date;
    END LOOP;
END $$;

CREATE TABLE outbox_p_default PARTITION OF outbox_partitioned DEFAULT;

INSERT INTO outbox_partitioned (id, aggregate_type, aggregate_id, event_type, payload, created_at, published_at)
SELECT id, aggregate_type, aggregate_id, event_type, payload, created_at, published_at FROM outbox;

DROP TABLE outbox;
ALTER TABLE outbox_partitioned RENAME TO outbox;
ALTER INDEX outbox_partitioned_pkey RENAME TO outbox_pkey;

-- Partial index on unpublished rows, one per partition (the relay's query reads only these).
CREATE INDEX outbox_unpublished_idx ON outbox (created_at) WHERE published_at IS NULL;
