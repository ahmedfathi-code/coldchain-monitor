-- ShedLock: one row per scheduled job that must run on ONE api-service instance at a time
-- (the outbox relay). An instance runs the job only if it can set lock_until into the future
-- on this row; the others skip that run. Times come from the database clock (UTC).
CREATE TABLE shedlock
(
    name       VARCHAR(64)  NOT NULL, -- job name, e.g. outbox-relay
    lock_until TIMESTAMP(3) NOT NULL, -- locked until; a crashed holder's lock expires here
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL, -- host name of the instance holding the lock
    PRIMARY KEY (name)
);
