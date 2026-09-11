# AegisGate feature roadmap

Live build: `1.7.0` (next minor: `1.8.0` — everything through budgets ships there; no `1.7.x` line).
Full `verify`: 1,320 green, JaCoCo branch gate 0.95 (currently 0.9503 — every new branch needs a test).

## Shipped (Phase 2 → budgets)

- Ollama-first local stack (iGPU-served, zero-cost); pricing V5; semantic L2 cache with dynamic dims.
- k6 proof suite (`00-smoke`, `10-flood`, `20-burst`, `30-sse-smoke`, `31-xk6`, `32-overload-ramp`, `33-knees`,
  `34-witness-60s`); chaos trims (Redis/PG kills, 19K dead-letter replays).
- JVM memory bundle (2G app limit, ZGC tuning); overload hunt (weak-spot order: generator → queueing, never
  memory); knees hunt (6.4K served req/s host vs 1.36K containerized; front-door refusals, app idle).
- 51-panel Grafana dashboard, 20 Prometheus alerts, redis/postgres exporters, custom meters.
- Latency trims (20ms SSE flush default, embed `keep_alive: 30m`, single-parse paths).
- Phase 0 multi-instance correctness (atomic seed, idempotency keys, pool math, SSE contract, V6 shared journal).
- Spend budgets + chargeback (V7, Lua gate, admin CRUD/balances/audit); SSE tail-drop race fix.

## Optimization & efficiency section (the 2-vCPU stretch program)

Direction of travel (operator-mandated): extract maximum sustained throughput and minimum p99/TTFT from a
2-vCPU node while keeping multi-instance (multi-VM today, K8s pods next) strictly correct. Every item must be
measured before/after (flood p95, burst p95, RSS, `usec_per_call` on `evalsha`) — no tuning on faith.

### Committed, in order

1. **Redis 8 cutover** ✅ SHIPPED (`redis:8.8.2-alpine3.23`; all 5 modules verified live; RDB index restored;
   L2 HIT 16.5ms vs 458ms fresh; flood 43,200/43,200, 0 dropped, p95 9.57ms; `verify` 1,320 green): compose swap
   with `REDIS_ARGS` folded into `command:`, `FT.INFO` backfill check, Testcontainers re-pinned (kept
   `RedisContainer` type for `@ServiceConnection`, image string only). No backup taken (cache/ledger disposable;
   PG is source of truth). ACL hardening deferred — compose runs without an ACL user.
2. **Carriers A/B + Path A re-hunt**: thread-carrier sizing, Lettuce/Hikari sizing, keep-alive/backlog/`somaxconn`,
   ECDSA check; 2→4→8 goodput sweep to re-baseline the per-instance ceiling.
3. **Budget Lua fast-path**: single-RTT consolidation audit, presence-cache TTL/miss-cost measurement, epoch-fencing
   follow-up; per-request Redis command accounting (target: budget gate adds ~0 measurable p99).
4. **Anomaly/forecast job** (standalone): burn-rate 50/90/100% alerts + webhooks; reuses the audit table, never the
   hot path.
5. **Response-replay store** (dedupe-only agreed scope): completes Stripe-semantics idempotency for spend (fixes the
   retry double-spend gap); bounded retention, no unbounded growth.
6. **CRITICAL-1 + fail-open remediation**: `/v1/embeddings` filter-bypass verdict, C-9 fail-closed paths, C-10
   outbox ordering, C-12 audit hardening, C-13 idempotent spend (research complete — see session notes).
7. **CRITICAL-2 + counter hardening**: `{b:orgId}` slot tags, overflow guards, month-edge single-instant derivation,
   result-contract versioning, negative-cache epoch fencing.
8. **SSE at scale**: heartbeat frames vs LB idle timeouts, server replay ring, single-pass byte relay, chat-side
   `keep_alive` parity, crypto-holder allocation removal (ThreadLocal forbidden on virtual threads — bounded pool
   or per-request).
9. **k6 revisit on the final tree** + distributed generation when single-box k6 saturates (~10K VUs).

### Explicitly deferred (with rationale logged in session notes)

- Test parallelism (A4/A5), F-11–F-28 mediums, branch-margin watch: no ROI until the ceiling moves.
- mTLS (Linkerd recommended when K8s lands), PgBouncer vs smaller pools (decide at 10 instances), heartbeat
  scheduler (lands with SSE-resume work), OOM-companion alert (needs exporter `err`-label verification).
- Per-tenant webhooks: column dropped; events ship via audit poll + operator alerting until proven demand.
- `INCREX`: stay-Lua for the multi-key admission gate (single-key atomicity cannot express first-denied-wins);
  revisit for single-window counters only.
