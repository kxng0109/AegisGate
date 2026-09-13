-- Notification preferences (opt-in delivery), bounce suppression, send dedupe, send log, and the
-- alerts archive. Delivery is strictly opt-in per scope: no preference row means no outbound message, while
-- alert auditing (alert_events, budget_audit) stays always-on. Secrets are NEVER stored here: channels carry
-- a secret_ref naming a runtime environment variable resolved at send time.
CREATE TABLE notification_preferences
(
    id          UUID         PRIMARY KEY,
    scope       VARCHAR(160) NOT NULL,
    channel     TEXT         NOT NULL CHECK (channel IN ('email', 'teams', 'slack', 'webhook')),
    target      VARCHAR(512) NOT NULL,
    secret_ref  VARCHAR(128),
    min_severity TEXT        NOT NULL DEFAULT 'warning' CHECK (min_severity IN ('warning', 'critical')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (scope, channel, target)
);

-- Hard-bounced destinations: the dispatcher skips them without attempting delivery.
CREATE TABLE notification_bounces
(
    id          UUID         PRIMARY KEY,
    channel     TEXT         NOT NULL,
    target      VARCHAR(512) NOT NULL,
    reason      VARCHAR(256) NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (channel, target)
);

-- Per-alert per-channel send guard: at-least-once delivery plus this claim collapses duplicates.
CREATE TABLE notification_dedupe
(
    id          UUID         PRIMARY KEY,
    dedupe_sha  VARCHAR(64)  NOT NULL,
    channel     TEXT         NOT NULL,
    target      VARCHAR(512) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (dedupe_sha, channel, target)
);

-- Every delivery attempt, success or terminal failure (the send audit trail).
CREATE TABLE notification_log
(
    id          UUID         PRIMARY KEY,
    dedupe_sha  VARCHAR(64)  NOT NULL,
    scope       VARCHAR(160) NOT NULL,
    channel     TEXT         NOT NULL,
    target      VARCHAR(512) NOT NULL,
    status      TEXT         NOT NULL CHECK (status IN ('SENT', 'FAILED', 'SKIPPED', 'SUPPRESSED')),
    detail      VARCHAR(512) NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_notification_log_created ON notification_log (created_at);

-- One-year online archive for resolved alert history. The retention janitor moves SENT/RESOLVED rows older
-- than 90 days here (same shape as alert_events); rows live here 1 year before a reviewed purge.
CREATE TABLE alert_events_archive (LIKE alert_events INCLUDING ALL);

ALTER TABLE alert_events_archive DROP CONSTRAINT IF EXISTS alert_events_archive_pkey;
ALTER TABLE alert_events_archive ADD PRIMARY KEY (id);
