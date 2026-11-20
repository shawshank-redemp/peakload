-- Idempotency keys. One row per (client, key) pair.
-- The primary key is a unique constraint on (client_id, idempotency_key). When two requests with
-- the same key arrive at the same time, the database lets only one INSERT succeed; the other gets
-- a unique-violation error and returns the stored result instead of creating a second order.
-- request_hash: SHA-256 of the request body. Same key with a different hash means a client error.
-- response_status / response_body: the stored answer, replayed for duplicate requests.
--   NULL while the first request is still in progress.
-- expires_at: rows past this time are removed by a cleanup job (step 2).
CREATE TABLE idempotency_keys (
    client_id        TEXT         NOT NULL,
    idempotency_key  TEXT         NOT NULL,
    request_hash     TEXT         NOT NULL,
    order_id         UUID         REFERENCES orders (id),
    response_status  INTEGER,
    response_body    JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (client_id, idempotency_key)
);

-- The cleanup job finds expired rows by expires_at.
CREATE INDEX idempotency_keys_expires_at_idx ON idempotency_keys (expires_at);
