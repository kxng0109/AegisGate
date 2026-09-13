-- Alert outbox: detector decisions awaiting delivery to Alertmanager, plus their outcomes.
--
-- The detector writes rows transactionally (never POSTs inline); a dispatcher claims PENDING rows with
-- SELECT ... FOR UPDATE SKIP LOCKED, POSTs the Alertmanager v2 array body, and marks SENT (or backs off).
-- The dedupe_sha UNIQUE constraint makes double-evaluation collapse to one alert even across pods.
-- Resolved alerts are marked RESOLVED after the closing POST (endsAt in the past), never deleted.
CREATE TABLE alert_events
(
    id          UUID        PRIMARY KEY,
    dedupe_sha  VARCHAR(64) NOT NULL UNIQUE,
    scope       VARCHAR(160) NOT NULL,
    detector    VARCHAR(64) NOT NULL,
    severity    TEXT        NOT NULL CHECK (severity IN ('warning', 'critical')),
    starts_at   TIMESTAMPTZ NOT NULL,
    ends_at     TIMESTAMPTZ,
    payload     TEXT        NOT NULL,
    status      TEXT        NOT NULL DEFAULT 'PENDING'
                CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'RESOLVED')),
    attempts    INTEGER     NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_alert_events_claim ON alert_events (status, next_retry_at, created_at);
