-- The async usage and cost ledger. Every completed streaming request that
-- reported token usage is recorded here on a background executor.
CREATE TABLE usage_ledger
(
    id                UUID PRIMARY KEY,
    request_id        UUID         NOT NULL UNIQUE,
    owner_id          VARCHAR(64)  NOT NULL,
    provider          VARCHAR(64)  NOT NULL,
    model             VARCHAR(128) NOT NULL,
    prompt_tokens     INTEGER      NOT NULL,
    completion_tokens INTEGER      NOT NULL,
    total_tokens      INTEGER      NOT NULL,
    cost_usd_micros   BIGINT       NOT NULL,
    duration_ms       BIGINT       NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_usage_ledger_owner_id ON usage_ledger (owner_id);
CREATE INDEX idx_usage_ledger_created_at ON usage_ledger (created_at);