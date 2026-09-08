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
