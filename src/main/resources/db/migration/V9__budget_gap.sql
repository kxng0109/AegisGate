-- Settlement gap audit: every hold that never settles cleanly leaves one row.
--
-- A gap row documents spend that is counted (the hold H stayed in the month
-- counters — the safe over-count direction) but unattributed to measured usage:
-- crash before settle, settle after hold TTL, abort-grace expiry, or a month
-- rollover straddle. Detectors and chargeback read this table, never Redis.
-- Like budget_audit, gap rows are insert-only; the trigger aborts any UPDATE or
-- DELETE for every role including the table owner.
CREATE TABLE budget_gap
(
    id             UUID        PRIMARY KEY,
    hold_id        VARCHAR(64) NOT NULL,
    level          TEXT        NOT NULL CHECK (level IN ('KEY', 'TEAM', 'ORG')),
    subject_id     VARCHAR(128) NOT NULL,
    held_micros    BIGINT      NOT NULL CHECK (held_micros >= 0),
    settled_micros BIGINT      NOT NULL CHECK (settled_micros >= 0),
    orig_month     VARCHAR(7)  NOT NULL,
    settle_month   VARCHAR(7)  NOT NULL,
    reason         TEXT        NOT NULL CHECK (reason IN ('EXPIRED', 'ABORTED', 'ROLLOVER', 'CRASH')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_budget_gap_subject ON budget_gap (level, subject_id, created_at);

CREATE OR REPLACE FUNCTION prevent_budget_gap_mutation() RETURNS trigger AS $$
BEGIN
	RAISE EXCEPTION 'budget_gap is append-only (trigger trg_budget_gap_immutable)';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_budget_gap_immutable ON budget_gap;
CREATE TRIGGER trg_budget_gap_immutable
	BEFORE UPDATE OR DELETE ON budget_gap
	FOR EACH ROW EXECUTE FUNCTION prevent_budget_gap_mutation();
