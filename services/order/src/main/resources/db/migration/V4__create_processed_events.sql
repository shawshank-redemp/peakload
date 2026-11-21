-- Events this service has already consumed. The consumer inserts the event id here with
-- ON CONFLICT DO NOTHING. If the insert adds a row, the event is processed. If it adds nothing,
-- the event is a duplicate and is ignored. Delivery is at-least-once, so duplicates do happen.
-- event_id: the outbox event id (UUIDv7), carried in the message's "event-id" header.
CREATE TABLE processed_events (
    event_id      UUID         PRIMARY KEY,
    event_type    TEXT         NOT NULL,
    aggregate_id  UUID         NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
