-- Schema of the api-service (MySQL 8). Managed by Flyway: never edit an applied migration,
-- add a new V<n>__description.sql file instead.
-- All times are stored in UTC.

-- A customer of the platform: a pharmacy or a transport company.
CREATE TABLE tenant
(
    id         VARCHAR(64)  NOT NULL, -- readable slug, e.g. nile-pharmacy (also used in MQTT topics)
    name       VARCHAR(200) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
);

-- People who log in. A SUPER_ADMIN belongs to no tenant; everybody else belongs to exactly one.
CREATE TABLE app_user
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(200) NOT NULL,
    password_hash VARCHAR(100) NOT NULL, -- BCrypt hash, never the password itself
    role          VARCHAR(20)  NOT NULL, -- SUPER_ADMIN, TENANT_ADMIN, OPERATOR
    tenant_id     VARCHAR(64)  NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT uk_app_user_username UNIQUE (username),
    CONSTRAINT fk_app_user_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT chk_app_user_tenant CHECK ((role = 'SUPER_ADMIN') = (tenant_id IS NULL))
);

-- A fridge or a truck with a sensor. The id is the deviceId used in MQTT, Kafka and Redis.
CREATE TABLE device
(
    id                    VARCHAR(64)  NOT NULL,
    tenant_id             VARCHAR(64)  NOT NULL,
    serial_number         VARCHAR(100) NOT NULL,
    name                  VARCHAR(200) NOT NULL,
    type                  VARCHAR(10)  NOT NULL, -- FRIDGE, TRUCK
    location              VARCHAR(200) NULL,
    min_temp              DOUBLE       NOT NULL,
    max_temp              DOUBLE       NOT NULL,
    excursion_minutes     INT          NOT NULL,
    door_open_max_minutes INT          NOT NULL,
    active                BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_device_serial_number UNIQUE (serial_number),
    CONSTRAINT fk_device_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    INDEX idx_device_tenant (tenant_id, type, active)
);

-- One row per alert episode (OPENED ... RECOVERED). The id is the alertId of the OPENED event.
-- No foreign key to device: alerts are history and must survive a device being deleted.
CREATE TABLE alert
(
    id              VARCHAR(36)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    device_id       VARCHAR(64)  NOT NULL,
    type            VARCHAR(30)  NOT NULL, -- TEMP_EXCURSION, DOOR_OPEN_TOO_LONG, DEVICE_OFFLINE, LOW_BATTERY
    severity        VARCHAR(10)  NOT NULL, -- WARNING, CRITICAL
    status          VARCHAR(15)  NOT NULL, -- OPEN, ACKNOWLEDGED, RESOLVED
    details         TEXT         NULL,     -- JSON object from the alert event
    opened_at       DATETIME(6)  NOT NULL,
    acknowledged_by VARCHAR(200) NULL,
    acknowledged_at DATETIME(6)  NULL,
    resolved_by     VARCHAR(200) NULL,     -- a username, or "system" when the device recovered
    resolved_at     DATETIME(6)  NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_alert_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    INDEX idx_alert_tenant_opened (tenant_id, opened_at),
    INDEX idx_alert_open_episode (tenant_id, device_id, type, status)
);
