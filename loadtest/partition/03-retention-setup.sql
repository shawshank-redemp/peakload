-- Week 4 step 3/4: fresh copies of the outbox for one retention repeat, on the EXPERIMENTAL database only.
--   ret_flat : flat table, primary key (id), partial index on unpublished rows (as the app), and an index on
--              created_at. The created_at index is needed by a batched DELETE; without it each batch scans the heap.
--   ret_part : monthly partitions 2025-11 .. 2026-12 plus a default, primary key (id, created_at).
-- Both are dropped and rebuilt at the start of every repeat, so every repeat starts from the same rows.
\set ON_ERROR_STOP on
DROP TABLE IF EXISTS ret_flat, ret_part CASCADE;

CREATE TABLE ret_flat (LIKE outbox INCLUDING DEFAULTS);
ALTER TABLE ret_flat ADD PRIMARY KEY (id);
CREATE INDEX ret_flat_unpub ON ret_flat (created_at) WHERE published_at IS NULL;
CREATE INDEX ret_flat_created ON ret_flat (created_at);
INSERT INTO ret_flat SELECT * FROM outbox;
ANALYZE ret_flat;

CREATE TABLE ret_part (LIKE outbox_p INCLUDING DEFAULTS) PARTITION BY RANGE (created_at);
ALTER TABLE ret_part ADD PRIMARY KEY (id, created_at);
CREATE INDEX ret_part_unpub ON ret_part (created_at) WHERE published_at IS NULL;
DO $$
DECLARE m date := date '2025-11-01';
BEGIN
  WHILE m < date '2027-01-01' LOOP
    EXECUTE format('CREATE TABLE ret_p_%s PARTITION OF ret_part FOR VALUES FROM (%L) TO (%L)',
                   to_char(m, 'YYYY_MM'), m, m + interval '1 month');
    m := m + interval '1 month';
  END LOOP;
END $$;
CREATE TABLE ret_p_default PARTITION OF ret_part DEFAULT;
INSERT INTO ret_part SELECT * FROM outbox_p;
ANALYZE ret_part;

-- Batched purge for the flat table: commits every batch, so each batch's locks are released before the next.
CREATE OR REPLACE PROCEDURE purge_flat(cut timestamptz, batch int DEFAULT 10000) LANGUAGE plpgsql AS $$
DECLARE n bigint; total bigint := 0;
BEGIN
  LOOP
    DELETE FROM ret_flat WHERE ctid IN (SELECT ctid FROM ret_flat WHERE created_at < cut LIMIT batch);
    GET DIAGNOSTICS n = ROW_COUNT;
    EXIT WHEN n = 0;
    total := total + n;
    COMMIT;
  END LOOP;
  RAISE NOTICE 'purge_flat removed % rows', total;
END $$;

-- Drop the monthly partitions that end at or before the cut-off (detach, then drop).
CREATE OR REPLACE PROCEDURE drop_old_partitions(cut date) LANGUAGE plpgsql AS $$
DECLARE r record; n int := 0;
BEGIN
  FOR r IN SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
           WHERE i.inhparent = 'ret_part'::regclass AND c.relname ~ '^ret_p_20[0-9]{2}_[0-9]{2}$'
             AND to_date(substr(c.relname, 7), 'YYYY_MM') + interval '1 month' <= cut
           ORDER BY c.relname LOOP
    EXECUTE format('ALTER TABLE ret_part DETACH PARTITION %I', r.relname);
    EXECUTE format('DROP TABLE %I', r.relname);
    n := n + 1;
  END LOOP;
  RAISE NOTICE 'dropped % partitions', n;
END $$;

SELECT 'ret_flat rows' AS what, count(*) FROM ret_flat
UNION ALL SELECT 'ret_part rows', count(*) FROM ret_part
UNION ALL SELECT 'rows before cut-off 2026-05-01 (flat)', count(*) FROM ret_flat WHERE created_at < '2026-05-01';
