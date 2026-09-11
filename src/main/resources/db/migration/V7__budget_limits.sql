-- Budget limits + admin audit trail for hard spend caps.
--
-- Enforcement reads limits from Redis config hashes (seeded write-through by
-- admin CRUD and backfilled at startup), never from this table on the hot path:
-- per-request PostgreSQL reads would cap throughput. This table is the durable
-- source of truth and the chargeback query surface; Redis is the enforcement copy.
CREATE TABLE budget_limits
(
    id          UUID         PRIMARY KEY,
    level       TEXT         NOT NULL CHECK (level IN ('KEY', 'TEAM', 'ORG')),
    subject_id  VARCHAR(128) NOT NULL,
    minute_micros BIGINT     NOT NULL DEFAULT 0,
    month_micros  BIGINT     NOT NULL DEFAULT 0,
    webhook_url VARCHAR(512),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (level, subject_id)
);

-- Every limit mutation, with actor + before/after snapshots. Spend snapshots
-- are appended here (not updated) when a budgeted key or team is deleted, so
-- chargeback history survives the subject. Never auto-purged by the app.
CREATE TABLE budget_audit
(
    id         UUID         PRIMARY KEY,
    actor      VARCHAR(128) NOT NULL,
    action     TEXT         NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'DELETE')),
    level      TEXT         NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    before_json TEXT,
    after_json  TEXT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_budget_audit_subject ON budget_audit (level, subject_id, created_at);
