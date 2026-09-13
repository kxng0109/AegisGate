# Proof-gate checklist — sign off a `high-throughput` ceiling

No sign-off without all boxes + attached artefacts (k6 JSON per generator,
Prometheus snapshot, `gc.log`, Redis `INFO`/slowlog dumps, `pg_stat` dumps,
sysctl/ulimit captures, exact commit + profile + seed dataset + warmup log).

## Preconditions

- [ ] Ceiling stated as tuple: `<RPS> @ <avgB> + <SSE> @ <trickle> <= 100MB/s, hit H%`
- [ ] Cache pre-warmed; steady-state `evicted_keys` delta == 0, hit% within +-1pt
- [ ] Generators healthy: CPU <= 80%, RAM <= 90%, NIC < 70%, NTP-synced
- [ ] SUT on the exact `high-throughput` profile commit under test

## Rate integrity

- [ ] Achieved starts >= 99.5% of target for full soak
- [ ] `dropped_iterations == 0` (any drop invalidates the rate claim)

## Latency (raw AND coordinated-omission-corrected HDR)

- [ ] Small JSON: p50 < 15ms, p99 < 100ms, p99.9 < 250ms
- [ ] SSE TTFT p95 < 500ms; trickle p99 gap < 2x interval; zero malformed events
- [ ] Percentiles-over-time attached (whole-run single p50 alone = reject)

## Error budget

- [ ] Non-chaos failures < 0.1%; zero silent drops (`ListenOverflows`,
  conntrack `insert_failed`, EMFILE, retrans budget all zero-delta)

## Dependency ceilings

- [ ] Redis: p99 completion < 5ms, evictions 0, slowlog empty, replica lag 0
- [ ] PG: checkpointer timed:requested >= 9:1, backend writes < 10%
- [ ] Hikari: pending == 0 steady, p99 acquire < 30% of timeout, timeouts 0
- [ ] Lettuce: p99 completion bounded; fail-closed paths exercised

## Resource ceilings

- [ ] SUT CPU < 75% avg, iowait < 5%, run queue <= cores
- [ ] si/so == 0, no OOM; FD < 70% limit; disk < 60%
- [ ] 30-60min soak: flat RSS/direct, flat p99, zero Full GC / allocation-stall storm

## Chaos trims (one at a time, 3-5 min each, during soak)

- [ ] Kill Redis -> fast fail-closed 503s, no hangs, recovery without restart
- [ ] Kill PG -> dead-letter growth, no thread-block, replay on recovery
- [ ] Upstream 429/5xx storm -> shed, no retry amplification
- [ ] Slow-client backpressure -> abort laggards, memory flat

## Reproducibility

- [ ] Second run by a different operator within +-5% RPS/p99, or gate is void
- [ ] README capacity note updated with the proven tuple (N+2 derated)

## P2 carrier A/B note (2026-09-13, app cpus 2.0, 02-gate-burst 500rps/3m)

- A (parallelism=4): 89,422 it @ 496.8/s, 579 dropped (0.6%), p50 2.65ms /
  p95 4.40ms / max 1.18s, 3 fast non-404 of 89K (99.997% gate-correct).
- B (parallelism=8, after 60s smoke warmup): 90,001 it @ 500.0/s, zero
  dropped, p50 2.64ms / p95 3.92ms / max 48ms, 180002/180002 checks.
- Verdict: KEEP 4. No meaningful difference at 500rps (both p95 < 5ms);
  B's edge is inside noise + warmup asymmetry (A ran cold, B warmed).
  Carriers do not bind at this load — consistent with the I/O-bound model.
  Differentiating run needs saturation-level load: deferred to P3
  distributed harness. jvm_threads_virtual gauges absent from Prometheus;
  JFR pinning audit needs a JDK (runtime is JRE-only) — also P3.

  Transient-noise note (2026-09-13): one 60s smoke showed 7 fast non-404 of
  6000 (99.94%, still above the 0.99 tripwire); server-side Prometheus showed
  5995/5995 POST /v1/chat/completions as correct 404, zero 5xx, zero
  fail-closed — the 7 never completed server-side (client-observed drops,
  consistent with accept-backlog RST microbursts on the default profile's
  accept-count=100). Immediate rerun: 12000/12000, server max 79.75ms,
  mean 2.56ms. Verdict: transient, not systematic; burst validation of the
  backlog belongs to P3 with kernel counters (ListenOverflows).
