-- FOCUS 1.4 FinOps schema expansion for prompt caching, reasoning telemetry, and cryptographic request verification
ALTER TABLE usage_ledger
    ADD COLUMN IF NOT EXISTS uncached_prompt_tokens INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cache_read_tokens      INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cache_write_tokens     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS reasoning_tokens       INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS effective_cost_micros  BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS billed_cost_micros     BIGINT  NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS request_hash           VARCHAR(64);

-- Index for cryptographic audit verification lookups
CREATE INDEX IF NOT EXISTS idx_usage_ledger_request_hash ON usage_ledger (request_hash);
