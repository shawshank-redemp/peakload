-- Week 4 step 5, item 1: the 7-day range query on flat orders WITH a created_at index, and on orders_p.
-- Read-only. Run three times per variant by the driver (run-range.sh), alternating.
\set ON_ERROR_STOP on
\pset pager off
\echo '== range 7 days, flat orders with created_at index'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON) SELECT count(*) FROM orders WHERE created_at > now() - interval '7 days';
\echo '== range 7 days, orders_p (partitioned, no index needed for pruning)'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, SUMMARY ON) SELECT count(*) FROM orders_p WHERE created_at > now() - interval '7 days';
