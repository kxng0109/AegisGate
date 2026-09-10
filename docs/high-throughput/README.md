# AegisGate high-throughput operations guide

> **Honest ceiling (1Gbps = 125MB/s ≈ 100MB/s usable):**
> ceiling = tuple, never a single number.
>
> | Profile | Mix | Egress | Status |
> |---|---|---|---|
> | A — RPS-max | 95K small JSON (~950B) + 5K idle SSE | ~98MB/s | Achievable cache-heavy |
> | B — mixed (recommended gate) | 80K × 1KB + 25K SSE @ trickle | ~97MB/s | Achievable, balanced |
> | C — stream-max | 60K × 1KB + 50K SSE @ trickle | ~90MB/s | Achievable, stream-heavy |
> | Fantasy | 50K *active* completions / 100K large-JSON rps | 2–50× over line | Physically impossible here |
>
> Activate the application profile with
> `SPRING_PROFILES_ACTIVE=high-throughput` (or `--spring.profiles.active=`).
> Defaults (no profile) serve light/moderate load unchanged.

## Files in this directory

| File                         | Purpose                                                                                                                       |
|------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| `redis.conf`                 | Single-instance reference (3GB, `volatile-lru`, persistence OFF + re-seed runbook, `io-threads 2`, per-key TTL+jitter matrix) |
| `postgresql.conf`            | Ledger primary reference (shared 2.5GB, async ledger scope, checkpoints, autovacuum, replication senders)                     |
| `99-aegisgate-highconc.conf` | sysctl for 50K concurrent connections + systemd `LimitNOFILE` notes                                                           |
| `jvm.options.zgc`            | Java 25 Generational ZGC flags (8g heap, direct-memory cap, vthread scheduler) + 8GB-floor variant                            |
| `replicas.md`                | Redis + PostgreSQL read-replica topology, routing rules, lag monitoring, failover                                             |
| `gate-checklist.md`          | Proof-harness pass/fail criteria for signing off a ceiling                                                                    |

## RAM budget that closes (16GB)

OS 1G + Redis 2.3G + PG 2G + JVM heap 8G + direct 1G + non-heap 0.8G + headroom 0.5G.
8GB floor: OS 0.75G + Redis 1G + PG 1G + heap 4G + direct 0.5G + non-heap 0.5G;
SSE caps at 8KiB, OTel off-box, ZGC headroom violated → cap concurrency, do not tune around physics.

## Durability tradeoffs (stated plainly)

- Redis persistence OFF: rate windows (60s), cache, HITL pending (300s) are reconstructible;
  API keys re-seed from `GATEWAY_BOOTSTRAPKEYS_*`; HITL loss fails closed (deny resume).
  NEVER auto-restart the master (empty restart wipes replicas).
- `synchronous_commit=off` scoped to ledger writes only: ≤600ms loss window on PG crash,
  covered by the app spillway WAL replay. Pricing/migrations stay synchronous.
- UNLOGGED staging vanishes on crash by design; spillway file/Redis journal is the backstop.

## Key-design rules (Cluster-ready, no rework later)

- `{tag}` every multi-key group: `ratelimit:{hex}:rpm|:tpm` (single-slot Lua).
- Prefixes: `ratelimit:` / `apikey:` / `admin:keys` / `aegis:cache:exact:` /
  `aegis:cache:vec:` / `mcp:hitl:` / `circuit:`.
- All cache/vector/HITL keys carry TTL (volatile-evictable); `apikey:*` and
  `admin:keys` carry none (protected under `volatile-lru`).

## SSE reconnect + retry contract (client-facing)

- Streams are TCP-pinned: a dying pod/instance breaks the stream visibly. There
  is no server-side replay buffer — clients MUST reconnect with backoff and
  refetch (no `Last-Event-ID` resume).
- Send `Idempotency-Key` (1–255 printable ASCII) per logical operation and reuse
  it on every retry. The gateway derives a deterministic usage-ledger id from
  tenant + route + key + body fingerprint, so a retried request cannot create a
  second ledger row on any instance (effectively-once; true exactly-once is not
  claimed). Malformed keys are rejected with HTTP 400; absent keys keep the
  previous random-id behavior.
- Budget/rate counters still charge per attempt (atomic per call); the ledger
  row is what deduplicates. Keep client timeouts above p99 so retries stay rare.
