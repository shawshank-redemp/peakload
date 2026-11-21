-- Orders that had no candidate rider when their OrderCreated event was handled. A scheduled job retries
-- them. Removal and the assignment happen in one transaction, so an order is never both pending and assigned.
-- attempts / last_attempt_at record how often the job tried; used for the give-up decision and for debugging.
CREATE TABLE pending_orders (
    order_id         UUID         PRIMARY KEY,
    city             TEXT         NOT NULL,
    pickup_lat       DOUBLE PRECISION NOT NULL CHECK (pickup_lat BETWEEN -90 AND 90),
    pickup_lon       DOUBLE PRECISION NOT NULL CHECK (pickup_lon BETWEEN -180 AND 180),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    attempts         INTEGER      NOT NULL DEFAULT 0,
    last_attempt_at  TIMESTAMPTZ
);

-- The retry job takes the oldest rows first.
CREATE INDEX pending_orders_created_at_idx ON pending_orders (created_at);
