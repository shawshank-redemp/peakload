-- Assignments: which rider was given which order. This table is the source of truth. Redis never holds it.
-- "Active" means status = 'ACTIVE': from assignment until the rider completes the delivery (COMPLETED).
-- order_id is unique: one order gets at most one assignment.
CREATE TABLE assignments (
    id          UUID         PRIMARY KEY,
    order_id    UUID         NOT NULL UNIQUE,
    rider_id    UUID         NOT NULL REFERENCES riders (id),
    status      TEXT         NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'COMPLETED')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- A rider may have at most one ACTIVE assignment. The partial index applies only to ACTIVE rows, so a
-- rider can still have any number of COMPLETED assignments. The database enforces this even when two
-- requests race.
CREATE UNIQUE INDEX assignments_one_active_per_rider ON assignments (rider_id) WHERE status = 'ACTIVE';
