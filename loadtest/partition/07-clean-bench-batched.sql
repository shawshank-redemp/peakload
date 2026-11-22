-- Removes the pgbench rows (client_id 'bench') from the EXPERIMENTAL database, in batches of 200,000 committed
-- one at a time. Each batch is its own transaction, so progress is visible and a failure loses one batch.
-- Assumes 06-clean-bench-rows.sql already removed the bench rows from idempotency_keys and the outbox tables.
\set ON_ERROR_STOP on
\timing on
CREATE OR REPLACE PROCEDURE clean_bench_orders() LANGUAGE plpgsql AS $$
DECLARE n bigint; total bigint := 0;
BEGIN
  LOOP
    DELETE FROM orders WHERE id IN (SELECT id FROM orders WHERE client_id = 'bench' LIMIT 200000);
    GET DIAGNOSTICS n = ROW_COUNT;
    EXIT WHEN n = 0;
    total := total + n;
    COMMIT;
    RAISE NOTICE 'removed % bench orders so far', total;
  END LOOP;
END $$;
CALL clean_bench_orders();
