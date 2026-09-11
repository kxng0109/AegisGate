-- Shared dead-letter staging for usage events: every instance drains the same
-- table, so a record spilled on one pod is replayed by whichever pod claims it.
-- The per-pod spillway file remains as the last resort when PostgreSQL itself
-- is unreachable. Rows are claimed with SELECT ... FOR UPDATE SKIP LOCKED
-- (disjoint sets across concurrent consumers, no waiting), drained into
-- usage_ledger (request_id UNIQUE keeps replays idempotent), then marked DONE.
-- Poisoned rows park forever for audit; DONE/POISONED rows older than the
-- retention window are purged by the drainer.
CREATE TABLE usage_ledger_staging
(
    id                    UUID         PRIMARY KEY,
    request_id            UUID         NOT NULL UNIQUE,
    owner_id              VARCHAR(64)  NOT NULL,
    provider              VARCHAR(64)  NOT NULL,
    -- Deliberately TEXT (wider than usage_ledger.model): rows the ledger
    -- rejects still stage here, then park as POISONED with the evidence intact.
    model                 TEXT         NOT NULL,
    prompt_tokens         BIGINT       NOT NULL,
    completion_tokens     BIGINT       NOT NULL,
    total_tokens          BIGINT       NOT NULL,
    cost_usd_micros       BIGINT       NOT NULL,
    duration_ms           BIGINT       NOT NULL,
    event_time            TIMESTAMPTZ  NOT NULL,
    uncached_prompt_tokens BIGINT      NOT NULL DEFAULT 0,
    cache_read_tokens     BIGINT       NOT NULL DEFAULT 0,
    cache_write_tokens    BIGINT       NOT NULL DEFAULT 0,
    reasoning_tokens      BIGINT       NOT NULL DEFAULT 0,
    effective_cost_micros BIGINT       NOT NULL DEFAULT 0,
    billed_cost_micros    BIGINT       NOT NULL DEFAULT 0,
    request_hash          VARCHAR(64),
    status                TEXT         NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'CLAIMED', 'DONE', 'POISONED')),
    attempts              INTEGER      NOT NULL DEFAULT 0,
    claimed_by             VARCHAR(128),
    claimed_at             TIMESTAMPTZ,
    next_retry_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_staging_request_id ON usage_ledger_staging (request_id);
CREATE INDEX ix_staging_poll ON usage_ledger_staging (next_retry_at, created_at)
    WHERE status = 'PENDING';
