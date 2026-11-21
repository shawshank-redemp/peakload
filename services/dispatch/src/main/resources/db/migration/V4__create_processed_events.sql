-- Events this service has already consumed. Insert with ON CONFLICT DO NOTHING: a row inserted means
-- the event is new and gets processed; no row means it is a duplicate. Used from step 2.
CREATE TABLE processed_events (
    event_id      UUID         PRIMARY KEY,
    event_type    TEXT         NOT NULL,
    aggregate_id  UUID         NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
