-- Week 4 step 3: partitioned variants on the EXPERIMENTAL database peakload_exp (never peakload).
-- Monthly RANGE partitions on created_at, 2025-11 .. 2026-12, plus a DEFAULT partition for anything later.
--   outbox_p : PRIMARY KEY (id, created_at) (the key must include the partition column); partial index on
--              unpublished rows per partition (the relay's query).
--   orders_p : PRIMARY KEY (id, created_at). NO foreign key from idempotency_keys: Postgres cannot reference a
--              partitioned table on (id) alone, so this copy exists to measure reads only. See the README.
-- Data is moved from the flat tables with INSERT ... SELECT. The flat tables are left as they are.
\set ON_ERROR_STOP on
\timing on

CREATE TABLE outbox_p (LIKE outbox INCLUDING DEFAULTS) PARTITION BY RANGE (created_at);
ALTER TABLE outbox_p ADD PRIMARY KEY (id, created_at);
CREATE INDEX outbox_p_unpub ON outbox_p (created_at) WHERE published_at IS NULL;

CREATE TABLE orders_p (LIKE orders INCLUDING DEFAULTS) PARTITION BY RANGE (created_at);
ALTER TABLE orders_p ADD PRIMARY KEY (id, created_at);
CREATE INDEX orders_p_client_idx ON orders_p (client_id, created_at);

DO $$
DECLARE m date := date '2025-11-01';
BEGIN
  WHILE m < date '2027-01-01' LOOP
    EXECUTE format('CREATE TABLE outbox_p_%s PARTITION OF outbox_p FOR VALUES FROM (%L) TO (%L)',
                   to_char(m, 'YYYY_MM'), m, m + interval '1 month');
    EXECUTE format('CREATE TABLE orders_p_%s PARTITION OF orders_p FOR VALUES FROM (%L) TO (%L)',
                   to_char(m, 'YYYY_MM'), m, m + interval '1 month');
    m := m + interval '1 month';
  END LOOP;
END $$;
CREATE TABLE outbox_p_default PARTITION OF outbox_p DEFAULT;
CREATE TABLE orders_p_default PARTITION OF orders_p DEFAULT;

INSERT INTO outbox_p SELECT * FROM outbox;
INSERT INTO orders_p SELECT * FROM orders;
ANALYZE outbox_p;
ANALYZE orders_p;

SELECT 'outbox_p rows' AS what, count(*) FROM outbox_p
UNION ALL SELECT 'orders_p rows', count(*) FROM orders_p
UNION ALL SELECT 'outbox_p partitions', count(*) FROM pg_inherits WHERE inhparent = 'outbox_p'::regclass
UNION ALL SELECT 'orders_p partitions', count(*) FROM pg_inherits WHERE inhparent = 'orders_p'::regclass;
