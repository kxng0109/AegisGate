# Read-replica topology (Redis + PostgreSQL)

Replicas live on **other hosts** — never on the shared 4c/16GB box (a same-box
replica doubles dataset RAM + fork cost on the same 4 cores).

## Redis

- **Primary:** all writes + rate-limit Lua + denylist writes + vector writes.
- **Replicas:** L1 `GET` + L2 `FT.SEARCH` only (`replica-read-only yes`).
- **Never replica-read:** `ratelimit:*`, `mcp:hitl:*` replay checks, `apikey:*`
  auth reads-after-write (stale = over-admit or replay-accept).
- Lettuce static master/replica routes reads automatically; keep a second
  template pinned to primary (250ms timeout) for rate/denylist/breaker.
- Lag/failure: drop lagging replicas from rotation (`INFO replication`
  `master_link_status`, `slave_repl_offset`); collapse reads to primary with an
  alert (then fail closed fast, never silently).
- Backlog: `repl-backlog-size 64mb` ≈ seconds of blips at gateway write rates;
  raise to 128mb only if `sync_full` > 0/week.
- **Cluster tripwire:** primary Lua >60K/s sustained, dataset >3GB, or >3 replicas
  with no relief → shard. Keys are already hash-tagged (`ratelimit:{hex}:*`),
  so migration needs no rekeying.

## PostgreSQL (PG16 streaming replication)

- Primary: `wal_level=replica`, `max_wal_senders=5`, `max_slot_wal_keep_size=10GB`
  (dead replica sacrifices itself instead of filling 256GB), per-replica slots.
- Standby: `pg_basebackup -R`, `hot_standby=on`, `hot_standby_feedback=on`,
  `max_standby_streaming_delay=30s`.
- **Replica-safe (≤5s staleness SLA):** admin summaries, owner/model/provider
  breakdowns, paginated entries, rollup reads. Route 100% there.
- **Primary-only:** idempotency checks, credit-gate decisions, read-after-write
  confirmations (sticky 5s window or LSN-gated).
- Lag monitor (1s poll → `db.replica.lag_ms`): warn >5s, page >30s; circuit
  opens → reads fall back to primary automatically.
- **Failover: manual promote + re-point** (`pg_promote()` most-caught-up replica,
  rebuild others, replay spillway WAL). RTO minutes, RPO = lag window.
  No Patroni/repmgr on the shared box (no quorum RAM/CPU; split-brain risk).
- Read/write split: two `HikariDataSource`s + `AbstractRoutingDataSource` on
  `@Transactional(readOnly=true)` + `LazyConnectionDataSourceProxy`;
  enforce replica read-only at the DB role level.
- Pooling: writers 2–4 direct connections (COPY staging); readers via PgBouncer
  `transaction` mode once client count grows (no session state on pooled conns).
