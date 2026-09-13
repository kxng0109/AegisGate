-- Replay store (durable tier): exact re-delivery payloads for idempotent retries.
--
-- Design (Track 4): Redis holds the 24h hot tier (`aegis:replay:*` hashes with TTL); this table is the durable
-- tier for forensics and re-warm. The request path reads Redis only — a hot miss re-proxies (the budget
-- dedupe still prevents double-charge), so this table is never on the latency path.
--
-- Partitioned by RANGE on expires_at (monthly): retention is detach-and-drop, never DELETE. Twelve partitions
-- are pre-created; the Phase-4 maintenance job extends the horizon. The primary key includes the partition
-- key (PostgreSQL requirement); point reads always supply (id, expires_at) from the hot-tier hash, so every
-- read prunes to exactly one partition. Bodies are TOASTed out of line (up to 1MiB, enforced app-side).
CREATE TABLE replay_store
(
    id         UUID        NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    body       BYTEA       NOT NULL,
    body_hash  VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (id, expires_at)
) PARTITION BY RANGE (expires_at);

CREATE TABLE replay_store_2026_09 PARTITION OF replay_store
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE replay_store_2026_10 PARTITION OF replay_store
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE replay_store_2026_11 PARTITION OF replay_store
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE replay_store_2026_12 PARTITION OF replay_store
    FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
CREATE TABLE replay_store_2027_01 PARTITION OF replay_store
    FOR VALUES FROM ('2027-01-01') TO ('2027-02-01');
CREATE TABLE replay_store_2027_02 PARTITION OF replay_store
    FOR VALUES FROM ('2027-02-01') TO ('2027-03-01');
CREATE TABLE replay_store_2027_03 PARTITION OF replay_store
    FOR VALUES FROM ('2027-03-01') TO ('2027-04-01');
CREATE TABLE replay_store_2027_04 PARTITION OF replay_store
    FOR VALUES FROM ('2027-04-01') TO ('2027-05-01');
CREATE TABLE replay_store_2027_05 PARTITION OF replay_store
    FOR VALUES FROM ('2027-05-01') TO ('2027-06-01');
CREATE TABLE replay_store_2027_06 PARTITION OF replay_store
    FOR VALUES FROM ('2027-06-01') TO ('2027-07-01');
CREATE TABLE replay_store_2027_07 PARTITION OF replay_store
    FOR VALUES FROM ('2027-07-01') TO ('2027-08-01');
CREATE TABLE replay_store_2027_08 PARTITION OF replay_store
    FOR VALUES FROM ('2027-08-01') TO ('2027-09-01');

CREATE INDEX ix_replay_store_created ON replay_store (created_at);
