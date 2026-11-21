-- Pickup point of each order, used by dispatch to find riders near it. Nullable so that orders created
-- before this migration (which had no pickup point) remain valid. New orders always set both columns.
ALTER TABLE orders
    ADD COLUMN pickup_lat DOUBLE PRECISION CHECK (pickup_lat BETWEEN -90 AND 90),
    ADD COLUMN pickup_lon DOUBLE PRECISION CHECK (pickup_lon BETWEEN -180 AND 180);
