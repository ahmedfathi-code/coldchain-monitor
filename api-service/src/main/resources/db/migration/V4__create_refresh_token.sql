-- Refresh tokens: long-lived, single-use tokens that get a new (short-lived) access token.
-- Only a SHA-256 hash of each token is stored: a copy of this table cannot be used to log in.
CREATE TABLE refresh_token
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    token_hash CHAR(64)    NOT NULL, -- hex SHA-256 of the token
    family_id  VARCHAR(36) NOT NULL, -- one login session: all tokens rotated from the same login
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,     -- set when used (rotated), on logout, or on reuse detection
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    INDEX idx_refresh_token_family (family_id),
    INDEX idx_refresh_token_expires (expires_at)
);
