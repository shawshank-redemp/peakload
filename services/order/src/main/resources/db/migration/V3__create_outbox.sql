-- Transactional outbox. An event row is written in the same transaction as the order change,
-- so an event exists if and only if the change was committed. A relay publishes the rows to the
-- bus later. Delivery is at-least-once: a row can be published twice, so consumers deduplicate
-- by id.
-- id: the event ID (UUIDv7). Consumers use it to ignore duplicates.
-- published_at: NULL until the relay has published the row.
CREATE TABLE outbox (
    id              UUID         PRIMARY KEY,
    aggregate_type  TEXT         NOT NULL,                -- e.g. 'order'
    aggregate_id    UUID         NOT NULL,                -- the order the event is about
    event_type      TEXT         NOT NULL,                -- e.g. 'OrderCreated'
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);

-- Partial index: only unpublished rows are indexed. The relay reads just these rows, so the
-- index stays small even when the outbox holds millions of published rows.
CREATE INDEX outbox_unpublished_idx ON outbox (created_at) WHERE published_at IS NULL;
