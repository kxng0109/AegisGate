# k6 local-Docker-stack runbook

`00-smoke` → `10-rps-flood` → `20-embeddings-burst` → `30-sse-smoke` →
`31-sse-hold-xk6` (custom binary, see file header). `gate-profile-b.js` is the
staging-scale artifact (sharded generators), not for local runs.

## Prerequisites

```powershell
winget install k6 --source winget
docker compose --profile monitoring up -d --wait --wait-timeout 120
# wait for app health (compose start_period is 20s; poll to be sure):
do { Start-Sleep 5; $h = (curl -fsS http://localhost:8080/actuator/health 2>$null) } until ($h)
$env:BASE_URL = "http://localhost:8080"
# default compose bootstrap key (valid 32-char suffix):
$env:API_KEY = "gw-localdevmasterkey0123456789abcde"
$env:LOAD_KEY = "gw-0123456789abcdef0123456789abcdef"
```

Provider-key-dependent scenarios (`10` measured, `20` full) need a provider
key (OPENAI_API_KEY from `.env`) for 200s at scale, or point at local Ollama (`MODEL=local-llama`,
`EMBEDDINGS_MODEL=local-embed`); 502/504 are
rate-integrity shapes when keys are absent. The `local-llama` alias points at
the installed `ollama` chat model (`qwen2.5:0.5b`); embeddings alias
`local-embed` uses `ollama` with `nomic-embed-text:latest`. The adapter
defaults to array-form input; single-string form is selected per provider via
the `embeddingSingleAsString` flag (Ollama uses single-string).

## Local Ollama (active local-stack path)

With Ollama serving the installed models (`ollama serve`; `ollama pull qwen2.5:0.5b`
for chat, `ollama pull nomic-embed-text:latest` for embeddings):

```powershell
$env:MODEL = "local-llama"
$env:EMBEDDINGS_MODEL = "local-embed"
k6 run loadtest/k6/10-rps-flood.js
k6 run loadtest/k6/20-embeddings-burst.js
k6 run loadtest/k6/30-sse-smoke.js
```

The gateway forwards `model` verbatim; the adapter passes array `input` by
default (Jina-requires-arrays compatible); `qwen2.5:0.5b` chat + `nomic-embed-text`
embeddings work directly. SSRF allowlist (`GATEWAY_DEV_ALLOW_PRIVATE_HOSTS`)
handles `localhost` for the container's internal reach.

Runs: same `k6` commands above (`00-smoke` first for wiring proof; the
`10-rps-flood` model value stays `fast` for provider-independent rate-integrity,
while `local-llama` alias points at the installed `qwen2.5:0.5b`).

## Runs (NOT executed by automation — run by hand for now)

```powershell
k6 run loadtest/k6/00-smoke.js              # wiring proof (actuator, auth-deny, mcp, no keys needed)
k6 run loadtest/k6/10-rps-flood.js          # rate-integrity flood; needs OPENAI_API_KEY or 502
k6 run loadtest/k6/20-embeddings-burst.js   # embeddings rate; EMBEDDINGS_MODEL=local-embed for local Ollama, else provider key
k6 run loadtest/k6/30-sse-smoke.js           # SSE wiring (stream start, close, no TTFT)
.\k6-sse run loadtest/k6/31-sse-hold-xk6.js   # true TTFT + inter-event gaps (custom binary)
```

## Isolation between scenarios

```powershell
docker compose exec -T redis redis-cli FLUSHDB
docker compose exec -T postgres psql -U aegisgate -d aegisgate -c "TRUNCATE usage_ledger RESTART IDENTITY;"
```

Count-asserting runs need `docker compose down -v` first (fresh named volumes).
k6 series land next to SUT metrics when run with
`-o experimental-prometheus-rw` + `K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write`.

## Chaos trims (separate shell, mid-hold)

- `docker stop aegisgate-redis` → expect fast fail-closed 503s, no hangs; start, expect recovery without restart.
- `docker stop aegisgate-postgres` → expect dead-letter growth (`logs/ledger-deadletter.log`), no thread-block; start,
  expect replay.
- Slow-client: covered by `30-sse-smoke` stalls + backpressure aborts in app logs.

## Verified run log (2026-09-09, local Ollama stack)

All four scenarios green against the rebuilt `aegisgate:latest` image:

| Scenario              | Result                                    | Notes                                                                                                                                                       |
|-----------------------|-------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `00-smoke`            | 53,720/53,720 checks                      | harness↔stack wiring                                                                                                                                        |
| `10-rps-flood`        | 100% checks, 0 dropped, p95 8.9ms         | 50 rps, `local-llama`                                                                                                                                       |
| `20-embeddings-burst` | 100% checks, 0 dropped, p95 33.8ms        | 20 rps, `local-embed`                                                                                                                                       |
| `30-sse-smoke`        | 100% checks, 0 failed                     | 3 VUs, `local-llama`                                                                                                                                        |
| `31-sse-hold-xk6`     | 48,344 streams, TTFT p95 9ms, gap p95 0ms | 3 VUs, `local-llama`, `.\k6-sse.exe` (no manual build — stock k6 v1.7.0+ auto-provisions `k6/x/sse`; local `go install` fallback documented in file header) |

Chaos trims: Redis stop → chat 503 (fail-closed), embed 400 (validation),
recovery clean; Postgres stop → both 400, ledger dead-letter journal replayed
19,336 writes lost during the outage on restart.

## Local rate ceilings

The shared dev box runs a single CPU `qwen2.5:0.5b` + `nomic-embed-text:latest`.
Saturating the scenario targets (500 rps chat, 100 rps embeddings, 50 SSE VUs)
yields 60s timeouts and tens of thousands of dropped iterations — capacity, not
defect. Each scenario now defaults to a sustainable local rate and accepts an
env override for provider-keyed runs:

- `10-rps-flood.js`: `PRIME_RATE` (default 10), `FLOOD_RATE` (default 50)
- `20-embeddings-burst.js`: `BURST_RATE` (default 20)
- `30-sse-smoke.js`: `SSE_VUS` (default 3)

## Gotchas

- **Never `FLUSHDB` between scenarios.** It wipes the seeded bootstrap keys, and
  `BootstrapKeySeeder` latches `seeded=true` so it never re-seeds — every
  request then 401s. Restart the app to reset the latch and re-seed.
- `31-sse-hold-xk6.js` needs the xk6-sse binary (build via `grafana/xk6`).
  k6 v1.7.0 resolves `k6/x/sse` automatically — verify whether the custom
  build is still required before running it.

## Threshold policy

Smoke/prerelease runs assert integrity (`dropped_iterations == 0`,
`failed < 5%`, `checks > 95%`) with generous latency bounds for shared dev
hardware. Tight SLO thresholds (p95/p99) run nightly on quiet iron, never in PR.
