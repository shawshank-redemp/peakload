-- Transactional outbox (same shape as the order service). Written in the same transaction as the change
-- it describes, published by a relay later. Delivery is at-least-once, so consumers deduplicate by id.
CREATE TABLE outbox (
    id              UUID         PRIMARY KEY,             -- the event id (UUIDv7)
    aggregate_type  TEXT         NOT NULL,                -- e.g. 'rider', 'assignment'
    aggregate_id    UUID         NOT NULL,
    event_type      TEXT         NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ                           -- NULL until the relay has published the row
);

CREATE INDEX outbox_unpublished_idx ON outbox (created_at) WHERE published_at IS NULL;
