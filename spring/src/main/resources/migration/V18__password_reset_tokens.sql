-- Forgot password (BE-041): one-time reset links. Only a SHA-256 hash of each random token is stored, never the token
-- itself. A token is valid until expires_at and until used_at is set (used, or replaced by a newer request).

CREATE TABLE password_reset_tokens (
    password_reset_token_id UUID        PRIMARY KEY,
    user_id                 UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    token_hash              VARCHAR(64) NOT NULL,
    expires_at              TIMESTAMP   NOT NULL,
    used_at                 TIMESTAMP,
    created_at              TIMESTAMP   NOT NULL,
    CONSTRAINT uq_password_reset_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_password_reset_tokens_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_password_reset_tokens_user ON password_reset_tokens (user_id, used_at);
