-- Transactional outbox: messages that must reach Kafka are first written here, in the SAME
-- database transaction as the business change (e.g. a device update). A background relay
-- then publishes them to Kafka in id order and sets published_at.
-- Either the device change AND its message are saved, or neither is.
CREATE TABLE outbox_event
(
    id           BIGINT       NOT NULL AUTO_INCREMENT, -- publishing order
    topic        VARCHAR(100) NOT NULL,
    message_key  VARCHAR(64)  NOT NULL,
    payload      TEXT         NULL,                    -- JSON value; NULL = tombstone (delete)
    created_at   DATETIME(6)  NOT NULL,
    published_at DATETIME(6)  NULL,                    -- NULL = not published yet
    attempts     INT          NOT NULL DEFAULT 0,      -- failed publishing attempts
    last_error   VARCHAR(500) NULL,
    PRIMARY KEY (id),
    INDEX idx_outbox_pending (published_at, id)
);
