# AegisGate

AegisGate is an AI gateway built in Java 25 on Spring Boot 4.1. It sits between your applications and large language model providers, exposing a single OpenAI compatible chat completions endpoint while handling authentication, rate limiting, secure upstream forwarding, protocol normalization, and usage based cost accounting.

The project is developed in phases. Phase 1 delivered a transparent SSE streaming proxy with SSRF defense and header
sanitization. Phase 2 added virtual API key authentication and distributed rate limiting backed by Redis. Phase 3 added
resilient multi provider failover: every model now maps to a chain of providers with automatic failover, per provider
circuit breakers, and an optional race strategy. Phase 4 added protocol normalization and an asynchronous usage and cost
ledger. The gateway now serves OpenAI shaped SSE to your client no matter which provider dialect answers the request,
and it records the tokens and estimated cost of every completed stream in PostgreSQL with prices refreshed daily from
the LiteLLM catalog. Phase 5 made the per provider circuit breaker distributed across gateway instances: breaker state
lives in Redis so every instance agrees on which providers are healthy, with an in-memory mirror that keeps the gateway
fail closed when Redis is slow or unavailable, following the architectural master plan. Subsequent hardening added an
adaptive SSE flush timer with backpressure detection and a bounded line body handler that enforces per-line byte caps
and per-stream rate limits during byte decoding to prevent memory exhaustion from oversized upstream events.

## What it does

- Streams chat completions from a configured upstream provider chain to your client with zero buffering of the full response body. Each request runs on its own virtual thread.
- Authenticates every request with a gateway managed virtual API key. Keys are shown to the caller only once and are stored exclusively as SHA-256 hashes.
- Enforces two independent limits per key: requests per minute (RPM) and tokens per minute (TPM). The check and consume step is atomic, executed in a single Redis Lua script.
- Fails closed. If Redis is unreachable, requests are rejected with a 503 instead of being allowed through unthrottled.
- Rejects oversized request bodies with a 413 before they can exhaust memory.
- Routes every model through an ordered provider chain. When a provider returns a transient error (429, a 5xx, a dropped connection, or a timeout), the request automatically fails over to the next provider.
- Protects each provider with a circuit breaker that is shared across all gateway instances through Redis. After a few consecutive failures the provider is skipped for a cooldown period, then a single probe from one instance decides whether normal service resumes.
- Never fails over on 401, 403, or 400, because a client or key problem cannot be fixed by another provider.
- Validates upstream URLs against private, loopback, link local, multicast, and cloud metadata ranges before any connection is attempted.
- Strips client supplied identity and authorization headers and injects the configured upstream key.
- Never logs key material. Every sensitive value is wrapped so that its string representation is masked.
- Enforces a hard byte limit on every upstream SSE line during byte decoding before string materialization, immediately
  cancelling the upstream connection with an RST_STREAM frame if an oversized line arrives. See `proxy/sse`.
- Protects downstream clients with an adaptive SSE flush strategy that batches lines and flushes on line count or
  elapsed time, paired with a write watchdog and hot-reloadable configuration.
- Normalizes every provider dialect to the OpenAI SSE contract, so one client endpoint works with OpenAI compatible, Anthropic, and Ollama upstreams. See `proxy/protocol`.
- Records the token usage and estimated cost of every completed stream into a PostgreSQL ledger. Recording runs asynchronously on its own executor, so billing can never slow a response.
- Keeps prices current without manual edits. The gateway syncs the LiteLLM model pricing catalog into the database once a day, and the price table is seeded at first migration.
- Scans ingress request payloads in real-time for secret and credential leakage (OpenAI, Anthropic, AWS, GCP, GitHub,
  Slack, HuggingFace, private keys) using high-speed prefix filtering, branchless Shannon entropy checks
  ($H (X) \ge 4.2$), and algorithmic checksums with zero false positives.
- Anonymizes PII before prompt forwarding with semantic surrogates (`<PERSON_1>`, `<EMAIL_1>`, `<PHONE_1>`, `<IBAN_1>`,
  `<CARD_1>`) and an ephemeral AES-256-GCM request-scoped vault. Supports Nigerian PII (NCC phone numbering, NIMC NIN,
  CBN/NIBSS BVN, Verve card Luhn, Tax IDs) with a 4-tier disambiguation pipeline.
- Reconstitutes PII tokens in outbound SSE streams on the fly using a bounded lookahead Sliding Window Aho-Corasick
  automaton with zero buffering and <0.1ms latency overhead.
- Defends against prompt injection and jailbreaks with UTS #39 Unicode homoglyph flattening, multi-tier cascaded
  screening, and prevents system prompt exfiltration with 5-gram token shingling and Bloom filters.
- Incrementally validates streaming JSON schema outputs byte-by-byte using a 64-bit integer stack Pushdown Automaton
  (PDA).
- Provides mid-stream guardrail kill-switch (`TERMINATE_WITH_ERROR`) emitting compliant SSE error events and immediately
  sending HTTP/2 `RST_STREAM(CANCEL)` frames upstream to stop GPU token billing.
- Enforces Geo-Sovereignty and Data Residency (`STRICT_SOVEREIGN`, `SOVEREIGN_CASCADE`,
  `PERMISSIVE_FAILOVER_WITH_AUDIT`), Zero Data Retention headers, and cryptographic SHA-256 Merkle audit ledger
  non-repudiation receipts.

## How a request flows

An incoming request to `/v1/chat/completions` passes through several stages:

1. `RequestBodyCachingFilter` (Order 0) wraps the request in a `CachedBodyHttpServletRequest`. The body is buffered
   once, up to a configured cap, so it can be re-read by downstream filters. See
   `security/filter/RequestBodyCachingFilter.java` and `security/filter/CachedBodyHttpServletRequest.java`.

2. `KeyAuthFilter` (Order 1) authenticates the bearer token, checks the model allow list, estimates the token cost of
   the request, and consults the rate limiter. It sets the `X-RateLimit` response headers and either continues the chain
   or answers with 401, 403, 429, or 503. See `security/filter/KeyAuthFilter.java`.

3. `IngressSecurityFilter` (Order 2) executes high-throughput ingress guardrail inspection. In `ENFORCE` mode, it
   rejects requests containing hardcoded credentials (OpenAI, Anthropic, AWS, GCP, GitHub, Slack, etc.) or prompt
   injection attempts with an RFC 9457 `ProblemDetail` (HTTP 422). If PII is present, it encrypts the entities into a
   request-scoped `EphemeralPiiVault` (AES-256-GCM), replaces values with semantic surrogate tokens (`<PERSON_1>`,
   `<EMAIL_1>`, `<PHONE_1>`, etc.), and forwards an `AnonymizedBodyHttpServletRequest`. See
   `security/filter/IngressSecurityFilter.java`.

4. `ProxyController` resolves the requested model to a `ModelAlias` and asks `FailoverOrchestrator` to pick a winning
   provider. The orchestrator filters providers against the tenant's geo-sovereignty policy (`STRICT_SOVEREIGN`,
   `SOVEREIGN_CASCADE`, `PERMISSIVE_FAILOVER_WITH_AUDIT`), walks the chain, and returns the streaming response. During
   streaming relay, the controller incrementally validates JSON outputs via `StreamingJsonPdaValidator`, de-anonymizes
   surrogates in real-time via `SlidingWindowAhoCorasick`, monitors for system prompt exfiltration, injects
   `X-Aegis-Audit-Receipt` and Zero Data Retention headers, and executes a mid-stream kill-switch
   (`TERMINATE_WITH_ERROR`) if violations occur. See `proxy/ProxyController.java`, `security/guardrail/*`, and
   `security/compliance/*`.

Filter registration and ordering are defined in `security/filter/SecurityFilterConfig.java`.

## Multi provider failover

Providers are configured under `gateway.providers`, each with a name, a protocol type, a base URL, an API key, and timeouts. Models are mapped to a chain under `gateway.aliases`:

```yaml
gateway:
  providers:
    openai:
      type: OPENAI
      base-url: https://api.openai.com
      api-key: ${OPENAI_API_KEY:}
      connect-timeout: 5s
      request-timeout: 60s
    openrouter:
      type: OPENAI
      base-url: https://openrouter.ai/api
      api-key: ${OPENROUTER_API_KEY:}
      connect-timeout: 5s
      request-timeout: 60s
    anthropic:
      type: ANTHROPIC
      base-url: https://api.anthropic.com
      api-key: ${ANTHROPIC_API_KEY:}
      connect-timeout: 5s
      request-timeout: 60s
    ollama:
      type: OLLAMA
      base-url: http://localhost:11434
      api-key: ""
      connect-timeout: 3s
      request-timeout: 120s
  aliases:
    gpt-56-sol:
      chain:
        - provider-name: openai
          model-override: gpt-5.6-sol
      strategy: SEQUENTIAL
    claude-sonnet-5:
      chain:
        - provider-name: anthropic
      strategy: SEQUENTIAL
    local-llama:
      chain:
        - provider-name: ollama
          model-override: llama3.2
      strategy: SEQUENTIAL
    fast:
      chain:
        - provider-name: openai
          model-override: gpt-5.6-luna
        - provider-name: openrouter
          model-override: gpt-5.6-luna
        - provider-name: ollama
          model-override: llama3.2
      strategy: SEQUENTIAL
```

The `type` field selects the protocol dialect. `OPENAI` covers OpenAI itself, OpenRouter, Groq, DeepSeek, Mistral, Together, vLLM, and most local servers. `ANTHROPIC` speaks the Anthropic Messages API, and `OLLAMA` speaks the native Ollama chat API. The full set of shipped providers and aliases lives in `src/main/resources/application.yml`. The `model-override` on a chain step pins the concrete upstream model for that provider, which is how a client facing name maps to a provider specific id.

The behavior of a chain is decided by the classification rules in `FailoverOrchestrator`:

- A 200 response with a streaming content type, either an event stream or newline delimited JSON, is a success.
- A 429 or any 5xx is transient and fails over to the next provider.
- A 401, 403, or 400 is non transient and is returned to the client as is.
- Timeouts and connection failures are transient and fail over.
- Failover happens only before the first byte is sent to the client. Once streaming starts, switching providers is impossible.

Each provider has a circuit breaker whose state is held in Redis and shared by every gateway instance, with a local in-memory mirror as a fallback. It starts closed, opens after three consecutive failures, stays open for thirty seconds, then admits a single probe owned by one instance. A successful probe closes the circuit; a failed probe reopens it. See `proxy/failover/RedisCircuitBreaker.java` and `proxy/failover/ProviderCircuitBreaker.java`.

When every provider fails, the client sees a clean error: 502 when providers returned errors, 503 when nothing usable was reachable, 504 when the chain timed out. See `proxy/failover/GatewayExceptionHandler.java`.

## Protocol normalization

AegisGate keeps one client contract, the OpenAI chat completions shape, and translates each provider's native protocol behind it. The `type` field on a provider selects the dialect:

- `OPENAI` speaks the OpenAI chat completions protocol directly. This covers OpenAI itself, OpenRouter, Groq, Mistral,
  Together, vLLM, and most local servers.
- `ANTHROPIC` speaks the Anthropic Messages API (`/v1/messages`). Requests are translated with tool declarations, tool
  choices, and Anthropic streaming events are rewritten into OpenAI shaped chunks.
- `GEMINI` speaks the Google AI Studio Gemini Developer API (`generativelanguage.googleapis.com`), supporting
  `systemInstruction`, `contents`, and streaming reasoning thoughts (`thought: true`).
- `VERTEX_AI` speaks the Google Cloud Vertex AI REST API (`aiplatform.googleapis.com`) with OAuth2/Bearer
  authentication.
- `DEEPSEEK` speaks the DeepSeek API (`api.deepseek.com`), supporting hybrid reasoning thinking mode (`thinking`,
  `reasoning_effort`), prompt caching telemetry, and streaming `reasoning_content`.
- `OLLAMA` speaks the native Ollama chat API (`/api/chat`), which streams newline delimited JSON.

The translation lives in `proxy/protocol`. `ProtocolAdapterResolver` picks the adapter for a provider type. Each adapter
builds the native URL, headers, and request body, and each normalizer rewrites the upstream stream back to the client
contract. A normalizer is created fresh per stream and captures the token counts plus the model the provider reports,
which feed the ledger. See `proxy/protocol/UniversalToolNormalizer.java`, `proxy/protocol/GeminiAdapter.java`,
`proxy/protocol/DeepSeekAdapter.java`, `proxy/protocol/AnthropicAdapter.java`, `proxy/protocol/OllamaAdapter.java`, and
`proxy/protocol/OpenAiPassthroughAdapter.java`.

### Universal Tool & Function Calling Normalization

AegisGate provides universal tool and function calling across all upstream providers:

- Translates canonical OpenAI `tools` definitions to Anthropic `input_schema` and Google Gemini OpenAPI 3.0 UPPERCASE
  types (`OBJECT`, `STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`, `ARRAY`).
- Maps `tool_choice` directives (`"auto"`, `"required"`, `"none"`, and named function descriptors) across dialects.
- Normalizes conversational multi-turn tool loops: rewrites OpenAI `role: "tool"` execution results into Anthropic
  `role: "user"` `tool_result` blocks and Gemini `role: "user"` `functionResponse` parts.
- Generates stateless, deterministic synthetic tool call IDs (`call_gen_...`) for providers that omit IDs natively.
- Relays streaming tool arguments incrementally across SSE chunk deltas in real time.

Cost is attributed against the model the provider reports, falling back to the requested model when the provider never reports one. On the OpenAI compatible path the gateway always asks the upstream for usage so it can bill, but the usage chunk is only relayed to a client that explicitly asked for it.

## Usage and cost ledger

Every completed stream that carries token usage is written to a PostgreSQL ledger. After the last byte is flushed, `ProxyController` publishes a `TokenUsageEvent`. `UsageLedgerListener` consumes it asynchronously on a dedicated bounded executor, so billing work can never delay a response. Duplicate request ids are skipped, and a database outage is logged with the record appended to a dead letter file rather than lost. See `ledger/UsageLedgerListener.java` and `ledger/LedgerConfig.java`.

The schema is owned by Flyway migrations under `src/main/resources/db/migration`. The gateway does not require PostgreSQL at startup. Boot's Flyway autoconfiguration is disabled, Hibernate never creates or validates the schema, and `config/DatabaseMigrator.java` applies the migrations once the database is reachable, retrying on a schedule. While the database is down, ledger writes degrade to a warning and the dead letter file, and the proxy hot path keeps working.

Costs come from a pricing catalog. `ledger/PricingSyncService.java` fetches the LiteLLM model pricing file (the URL is configurable and can be pinned to a tag or commit), keeps the chat oriented entries, and upserts them into `model_pricing`. It runs at startup and then daily at 03:00. `ledger/ModelPriceCatalog.java` serves lookups from a short lived cache with exact id, provider composite, and longest prefix matching, and `ledger/CostCalculator.java` computes cost in micro dollars with `BigDecimal`. The sync is strictly best effort. A failed fetch leaves the previous rows in place, and the seed rows in `V2__model_pricing.sql` cover the shipped aliases from the first migration.

## Project layout

The code is organized by responsibility under `src/main/java/io/github/kxng0109/aegisgate`:

- `contracts` contains the shared immutable types: `SHA256Hash`, `VirtualApiKey`, `RateLimitDecision`, `RateLimitState`, `RejectionReason`, `BootstrapKey`, `ProviderConfig`, `ProviderRef`, `ModelAlias`, `ProviderType`, `FailoverStrategy`, and `GatewayProperties`.
- `security` contains the Phase 1 controls: `SsrfValidator`, `HeaderSanitizer`, and `CidrRange`.
- `security/filter` contains the servlet filter pipeline: the replayable body wrapper, the authentication filter, and the registration configuration.
- `security/ratelimit` contains the distributed limiter: `RateLimitEngine`, `RateLimitScriptConfig`, `KeyManagementService`, and `BootstrapKeySeeder`.
- `proxy/failover` contains the routing and resilience layer: `FailoverOrchestrator`, `CircuitBreaker`, `CircuitBreakerFactory`, `RedisCircuitBreaker`, `RedisCircuitBreakerFactory`, `ProviderCircuitBreaker`, `CircuitBreakerConfig`, `CircuitBreakerMetrics`, `CircuitBreakerProperties`, `InstanceId`, `ProviderClientAdapter`, `ProviderResponse`, `UpstreamUnavailableException`, and `GatewayExceptionHandler`.
- `proxy/protocol` contains the Phase 4 dialect layer: `ProtocolAdapterResolver`, the request adapters, and the SSE normalizers for the OpenAI, Anthropic, and Ollama dialects.
- `proxy/sse` contains the streaming protection and guard layer: `BoundedLineBodyHandler`, `DefaultSseLineGuard`,
  `DefaultSseLineGuardFactory`, `SseLineGuardProperties`, `SseLineGuardAutoConfig`, `AdaptiveSseFlushStrategy`,
  `SseFlushStrategy`, `SseFlushProperties`, `SseFlushAutoConfig`, `SseFlushConfigReloader`, `SseFlushHealthIndicator`,
  `TokenBucket`, and `LineTooLongException`.
- `ledger` contains the Phase 4 usage and cost ledger: `UsageLedgerListener`, `UsageLedgerRepository`, `CostCalculator`, `ModelPriceCatalog`, `ModelPricingRepository`, and `PricingSyncService`.
- `proxy` contains the controller and the shared `HttpClient` bean in `proxy/config/HttpClientConfig.java`.
- `config` contains the `SensitiveString` value wrapper and the retrying `DatabaseMigrator`.

The Lua script that implements the atomic RPM and TPM counters lives in `src/main/resources/rate_limit.lua`. The circuit breaker state machine lives in `src/main/resources/circuit_try_acquire.lua`, `circuit_record_failure.lua`, and `circuit_record_success.lua`. Runtime configuration lives in `src/main/resources/application.yml`, and the database schema is defined by the Flyway migrations under `src/main/resources/db/migration`.

## Technology stack

- Java 25 LTS with virtual threads enabled
- Spring Boot 4.1 and Spring MVC
- Redis via Spring Data Redis and Lettuce, with a connection pool
- PostgreSQL via Spring Data JPA and Hibernate, with Flyway owning the schema
- Caffeine for the short lived key lookup cache and the pricing catalog
- JSpecify nullness annotations at package level
- JUnit Jupiter, Mockito, and Testcontainers for testing
- JaCoCo with a strict coverage gate

Dependency versions are managed by the Spring Boot 4.1 BOM. See `pom.xml`.

## Prerequisites

- JDK 25
- Redis 7 or newer, reachable at `localhost:6379` by default. Redis is also required for the distributed circuit breaker, which fails closed when Redis is unreachable.
- PostgreSQL, reachable at `localhost:5432` by default. The gateway starts without it, but the ledger and pricing table need it.
- Docker, only if you want to run the Testcontainers integration tests

The Maven wrapper is included, so no separate Maven installation is needed.

## Getting started

### Running with Docker Compose

The gateway includes production-ready multi-stage containers and pre-configured Docker Compose profiles:

```bash
# Start only the dependencies (Redis 7 & PostgreSQL 16 for local IDE development)
docker compose --profile deps up -d

# Start the gateway with backing databases and the full observability stack (Prometheus & Grafana)
docker compose --profile monitoring up -d

# Start the full containerized stack
docker compose --profile all up -d --build
```

Copy `.env.docker.example` to `.env` to configure ports, provider API keys, and Grafana credentials:

- **AegisGate Gateway**: `http://localhost:8080` (Actuator & Health: `http://localhost:8080/actuator/health`)
- **Grafana Dashboard**: `http://localhost:3000` (Pre-configured `AegisGate — Production Operations` dashboard)
- **Prometheus TSDB**: `http://localhost:9090` (Scraping `/actuator/prometheus` with pre-loaded alert rules)

### Running manually

If you prefer starting containers individually:

```bash
docker run -d --name aegisgate-redis -p 6379:6379 redis:7-alpine
docker run -d --name aegisgate-postgres -p 5432:5432 -e POSTGRES_USER=aegisgate -e POSTGRES_PASSWORD=<your-password> -e POSTGRES_DB=aegisgate postgres:16-alpine
```

Provide your provider keys and, optionally, a bootstrap key for local testing:

```bash
export OPENAI_API_KEY=your-provider-key
export ANTHROPIC_API_KEY=your-provider-key
export POSTGRES_URL=jdbc:postgresql://localhost:5432/aegisgate
export POSTGRES_USER=aegisgate
export POSTGRES_PASSWORD=<your-password>
export GATEWAY_BOOTSTRAPKEYS_0_OWNERID=local
export GATEWAY_BOOTSTRAPKEYS_0_NAME=local-dev
export GATEWAY_BOOTSTRAPKEYS_0_PLAINTEXTKEY=gw-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
export GATEWAY_BOOTSTRAPKEYS_0_RPMLIMIT=60
export GATEWAY_BOOTSTRAPKEYS_0_TPMLIMIT=100000
```

Build and run:

```bash
./mvnw clean verify
./mvnw spring-boot:run
```

The service listens on port 8080.

## Configuration

All configuration lives in `src/main/resources/application.yml`. The most important settings:

- `gateway.providers` describes every upstream provider with its dialect, URL, key, and per request timeout.
- `gateway.aliases` maps each client facing model name to a provider chain and a strategy. A step can pin its upstream model with `model-override`.
- `spring.data.redis.*` controls the Redis connection and the Lettuce pool.
- `spring.data.redis.sentinel.*`, `spring.data.redis.cluster.*`, and `spring.data.redis.masterreplica.*` switch the Redis connection used by the rate limiter and the circuit breaker to Sentinel, Cluster, or master/replica topology; the factories in `proxy/failover/CircuitBreakerConfig.java` derive the topology the same way Boot's auto-configuration does. Without any of these the gateway connects to a single host and port.
- `gateway.circuit-breaker.redis-timeout` bounds how long the breaker waits on Redis before it fails closed to the local mirror. The default is 250ms. Setting `spring.application.instance-id` gives each instance a stable name used to arbitrate the single probe.
- `spring.datasource.*` controls the PostgreSQL connection that backs the usage ledger and pricing catalog.
- `gateway.pricing.source-url` and `gateway.pricing.refresh-cron` control where the LiteLLM pricing file is fetched from and how often. The default is a daily sync at 03:00.
- `gateway.ledger.dead-letter-path` is where ledger records go when the database is unavailable.
- `gateway.database-migrate-enabled` and `gateway.database-migrate-interval` control the non fatal migration retry.
- `gateway.bootstrap-keys-seed-interval` controls how often key seeding is retried if Redis was unavailable at startup.
- `aegisgate.sse.flush.*` controls the adaptive downstream SSE flush strategy: `max-lines-per-flush` (default 16),
  `max-interval-ms` (default 100ms), `flush-backpressure-threshold-ms` (default 500ms), `max-buffer-bytes` (default
  64KB), `max-flushes-per-second` (default 1000), `enabled` (default true), and `reload-interval` (default 30s).
- `aegisgate.sse.line-guard.*` controls the upstream SSE line guard: `global-default-bytes` (default 16KB),
  `safety-margin-percent` (default 10%), `action` (`REJECT_LINE_AND_CLOSE` or `REJECT_LINE_CONTINUE`), `per-provider`
  (overrides for `OPENAI`, `ANTHROPIC`, `OLLAMA`), `write-timeout` (default 30s), `write-timeout-check-interval`
  (default 5s), and `reload-interval` (default 30s).

The per provider `request-timeout` bounds the time to the first byte of the response for that attempt. It is the failover timer; it does not limit a long lived SSE stream. Per provider `connect-timeout` bounds connection establishment. The shared `HttpClient` in `proxy/config/HttpClientConfig.java` applies a conservative connect timeout and never follows redirects.

Bootstrap keys are provisioned exclusively through environment variables such as `GATEWAY_BOOTSTRAPKEYS_0_PLAINTEXTKEY`. Plaintext keys never belong in the repository. The `BootstrapKeySeeder` in `security/ratelimit/BootstrapKeySeeder.java` seeds them after the application is ready and retries on a schedule until Redis is reachable.

## API

### POST `/v1/chat/completions`

The request body is an OpenAI style chat completion payload. The gateway forwards it to the winning provider, including `stream` semantics, and returns the SSE stream.

Authentication uses the `Authorization` header:

```bash
curl -N http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer gw-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-56-luna","messages":[{"role":"user","content":"Hello"}]}'
```

Status codes:

- `200` the stream started and is being relayed.
- `400` the request body is empty, malformed, or missing its model.
- `401` the key is missing, malformed, or unknown.
- `403` the key is disabled or the requested model is not allowed for it.
- `404` the requested model has no configured alias.
- `413` the request body exceeds the configured limit.
- `429` a rate limit was exceeded. The `Retry-After` header tells the client when to retry.
- `502` every provider returned an error.
- `503` nothing usable was reachable, or the authentication and rate limiting services are unavailable.
- `504` the provider chain timed out.

On success the response carries the rate limit state in the `X-RateLimit-Limit-RPM`, `X-RateLimit-Remaining-RPM`, `X-RateLimit-Reset-RPM`, and the matching TPM headers. The reset values are epoch seconds.

### Embeddings API (`/v1/embeddings`)

Accepts OpenAI-compatible embedding requests, automatically handles transparent batch partitioning across upstream
provider limits (e.g., Cohere max 96, Ollama max 32), dispatches sub-batches concurrently over Virtual Threads, and
reassembles dense vector results with deterministic `0..N-1` index preservation:

```bash
curl http://localhost:8080/v1/embeddings \
  -H "Authorization: Bearer gw-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"text-embedding-3-small","input":["First text to embed","Second text to embed"]}'
```

Supports:

- Single string input (`"input": "text"`), multi-text arrays (`"input": ["text1", "text2"]`), and token ID arrays.
- High-efficiency Little-Endian IEEE 754 Base64 binary float encoding (`"encoding_format": "base64"`).
- Matryoshka Representation Learning (MRL) dimension truncation (`"dimensions": 512`).
- Seamless routing to OpenAI, Cohere (`v2/embed`), and Ollama (`/api/embed`).

### Administrative Endpoints (`/v1/admin/**`)

Administrative endpoints require the configured master key via `Authorization: Bearer <GATEWAY_ADMIN_MASTERKEY>` or
`X-Admin-Key`:

- **`POST /v1/admin/keys`**: Creates a new virtual API key with custom RPM/TPM quotas and allowlists. Returns the
  single-exposure plaintext key:
  ```json
  {
    "keyId": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "key": "gw-aB3_x9...32chars",
    "keyPrefix": "gw-",
    "ownerId": "tenant-corp",
    "name": "production-key",
    "rpmLimit": 120,
    "tpmLimit": 500000,
    "allowedModels": ["gpt-56-luna", "claude-sonnet-4-5"],
    "allowedProviders": ["openai", "anthropic"],
    "enabled": true,
    "createdAt": "2026-08-31T14:30:00Z"
  }
  ```
- **`GET /v1/admin/keys`**: Lists registered virtual keys with safe public metadata (optional `?ownerId=...` filter).
- **`GET /v1/admin/keys/{keyId}`**: Retrieves metadata for a specific key.
- **`PATCH /v1/admin/keys/{keyId}`**: Dynamically updates name, RPM/TPM quotas, allowlists, or enabled status.
- **`DELETE /v1/admin/keys/{keyId}`**: Permanently deletes a virtual API key and purges caches.
- **`GET /v1/admin/circuits`**: Inspects real-time circuit breaker states (`CLOSED`, `OPEN`, `HALF_OPEN`) across all
  providers.
- **`POST /v1/admin/circuits/{provider}/reset`**: Force-resets an upstream circuit breaker to `CLOSED`.
- **`GET /v1/admin/ledger/summary`**: Returns aggregated token consumption, USD costs, duration, and multi-dimensional
  breakdowns by tenant, model, and provider (supports optional `?ownerId=...&provider=...&model=...&from=...&to=...`).
- **`GET /v1/admin/ledger/entries`**: Returns paginated audit log records with allowlisted sort parameters (supports
  `?page=0&size=20&sort=createdAt,desc`).
- **`GET /v1/admin/ledger/entries/{requestId}`**: Retrieves full transaction and token coordinates for a single
  correlated client request.
- **`GET /v1/admin/cache/stats`**: Inspects active cache configuration, layer statuses, and similarity thresholds.
- **`DELETE /v1/admin/cache`**: Executes an emergency global purge across L0 in-memory, L1 Redis exact keys, and L2
  vector document indexes (supports optional `?ownerId=...` for single-tenant scoped purges).

### Multi-Tier Semantic Caching Layer

AegisGate provides an enterprise-grade, high-throughput (2,000+ concurrent users) multi-tiered caching architecture:

- **L0 (In-Memory)**: Bounded Caffeine cache for sub-millisecond ($<0.1\text{ms}$) exact-match hot prompt lookups.
- **L1 (Distributed Exact Match)**: Redis key-value store partitioned by SHA-256 compound keys.
- **L2 (Vector Similarity Search)**: RediSearch / Redis VSS HNSW vector search executing cosine distance queries over
  dense float32 vectors generated by AegisGate's configured embedding model.
- **Multi-Turn Prefix Partitioning**: Employs hybrid prefix-exact hashing ($H_{\text{prefix}}$) over prior
  turns $[0..N-2]$
  and dense vector embedding on the active user turn $[N-1]$, preventing context drift and infinite replay loops.
- **Anti-Hallucination Guardrails**:
    - **Polarity Guard**: Rejects intent reversals (`enable` vs `disable`, `true` vs `false`).
    - **Entity Guard**: Rejects conflicting named entities and numbers (`Apple` vs `Microsoft`, `42` vs `100`).
    - **Temperature Gating**: Requests with $T > 0.1$ bypass caching to preserve requested stochastic creativity.
- **Synthetic Streaming SSE Replay**: Automatically reconstitutes cached completions into valid OpenAI SSE chunk
  sequences with Time-To-First-Token (**TTFT**) in **$< 5\text{ms}$**.

### Interactive Swagger & OpenAPI 3.1 Documentation

AegisGate provides rich, interactive Swagger UI and OpenAPI 3.1 documentation with group switching, request duration
tracking, syntax highlighting, and live Try-It-Out execution:

- **Swagger UI**: [`http://localhost:8080/swagger-ui.html`](http://localhost:8080/swagger-ui.html)
- **OpenAPI 3.1 JSON Specification**: [`http://localhost:8080/v3/api-docs`](http://localhost:8080/v3/api-docs)
- **OpenAPI 3.1 YAML Specification**: [`http://localhost:8080/v3/api-docs.yaml`](http://localhost:8080/v3/api-docs.yaml)

#### Swagger UI Group Partitions:

1. **`1. Public Gateway APIs`**: `/v1/chat/**` and `/v1/embeddings/**` with Bearer Virtual API Key authentication
   (`Authorization: Bearer gw-...`).
2. **`2. Administrative APIs`**: `/v1/admin/**` (Keys, Circuits, Ledger, Cache) with Master Admin Key authentication
   (`Authorization: Bearer <master-key>` or `X-Admin-Key: <master-key>`).
3. **`3. Observability & Actuator`**: `/actuator/**` health and Prometheus metrics.

## Security model

- Keys are 32 random base64url characters behind a `gw-` prefix, giving 192 bits of entropy. They are generated with `SecureRandom` in `KeyManagementService`.
- Only the SHA-256 digest is stored in Redis. `SHA256Hash.toString()` is masked, and no log statement ever receives a plaintext key.
- Key metadata is cached locally with a five second expiry and a bounded size. Confirmed misses are cached as well, so invalid keys cannot flood Redis.
- The gateway fails closed. Redis connectivity errors and pool exhaustion are caught in `RateLimitEngine` and mapped to 503, and lookup failures in the authentication path are handled the same way.
- Every provider URL is validated against a private address block list before its first use, and redirects are never followed.
- Client supplied headers that could spoof identity are stripped before forwarding.
- Error responses carry only generic messages. Internal details never reach the client.

## Testing

Run the full suite with coverage and the packaging step:

```bash
./mvnw clean verify
```

The suite currently has 968 tests:

- Real-time guardrail and security scanner tests in `security/guardrail/*`: `ShannonEntropyCalculatorTest`,
  `LuhnValidatorTest`, `ConfusablesFilterTest`, `GuardrailPropertiesTest`, `BytePrefixTrieTest`,
  `SecretScannerRuleDatabaseTest`, `IngressSecretScannerTest`, `SecretLeakageExceptionTest`,
  `PromptInjectionScannerTest`, `PromptInjectionExceptionTest`, `SystemPromptProtectionEngineTest`,
  `PiiScannerTest`, `PiiDisambiguationEngineTest`, `IbanValidatorTest`, `EphemeralPiiVaultTest`,
  `PiiAnonymizerTest`, `SlidingWindowAhoCorasickTest`, `StreamingJsonPdaValidatorTest`, and
  `MidStreamKillSwitchTest` covering branchless LUT Shannon entropy, UTS #39 Unicode homoglyphs, 18 credential patterns,
  Nigerian phone/BVN/NIN 4-tier disambiguation, Verve Luhn checks, AES-256-GCM ephemeral request vault with zero-trace
  memory wiping, bounded lookahead SSE chunk reassembly, and upstream HTTP/2 RST_STREAM cancellation.
- Geo-sovereignty & compliance tests in `security/compliance/*`: `JurisdictionAdequacyTest`,
  `GeoSovereigntyRouterTest`, `MerkleAuditLedgerTest`, and `ZeroDataRetentionEnforcerTest` covering GDPR Art. 45 & NDPA
  2023 cross-border adequacy DAGs, forward-secure SHA-256 hash chains, HMAC-SHA256 receipts (`X-Aegis-Audit-Receipt`),
  and `X-No-Storage` zero-retention headers.
- Ingress filter pipeline tests in `security/filter/*`: `IngressSecurityFilterTest`,
  `SecurityFilterConfigTest`, and `AnonymizedBodyHttpServletRequestTest` covering order 0 -> 1 -> 2 filter chaining, RFC
  9457 HTTP 422 ProblemDetails in ENFORCE mode, audit logging in AUDIT_ONLY mode, and request wrapping.
- OpenAPI 3.1 & documentation tests in `config`: `OpenApiConfigTest` covering global specification metadata, security
  scheme registrations (`BearerAuth`, `AdminKeyAuth`, `AdminBearerAuth`), and GroupedOpenApi partitions.
- Multi-tier semantic caching tests in `cache`: `CacheKeyGeneratorTest`, `CacheGuardrailsTest`,
  `RedisSemanticVectorCacheTest`, `RediSearchVectorClientTest`, `InMemoryExactCacheTest`,
  `RedisExactCacheTest`, `SingleFlightManagerTest`, `CachedStreamReconstitutionTest`,
  `CachePolicyEngineTest`, `AegisCacheServiceTest`, `AdminCacheControllerTest`, `CacheContractsTest`,
  `AegisCachePropertiesTest`, `CacheFullCoverageTest`, and `SemanticCacheIntegrationTest` covering L0 in-memory caching,
  L1 Redis exact matching, L2 RediSearch HNSW vector search, multi-turn prefix partitioning, polarity and entity
  guardrails, RFC 9111 directive inspection, single-flight stampede prevention, synthetic SSE stream reconstitution, and
  administrative purge APIs.
- Embedding gateway tests in `proxy/embeddings`: `VectorEncodingUtilsTest`, `EmbeddingDtoTest`,
  `OpenAiEmbeddingAdapterTest`, `CohereEmbeddingAdapterTest`, `OllamaEmbeddingAdapterTest`,
  `EmbeddingBatchOrchestratorTest`, `EmbeddingAdapterResolverTest`, `EmbeddingServiceTest`, and
  `EmbeddingControllerTest` covering Little-Endian IEEE 754 float32 Base64 encoding/decoding, auto-batching, concurrency
  bounding, index reassembly, and provider normalization.
- Administrative, billing & key management tests in `admin`: `AdminAuthFilterTest`, `AdminKeyControllerTest`,
  `AdminCircuitControllerTest`, `AdminLedgerControllerTest`, `AdminFilterConfigTest`, and `AdminDtoTest` covering
  constant-time master key authentication, fail-closed isolation, key creation (single-exposure plaintext), updates,
  deletions, circuit breaker force-resets, aggregated tenant billing queries, and paginated audit logs.
- Unit tests for hashing, key management, the rate limit engine, both filters, the body wrapper, the circuit breaker, the provider adapter, the orchestrator, the error handler, and the Phase 1 security components.
- Distributed circuit breaker tests in `proxy/failover`: `RedisCircuitBreakerTest` and `CircuitBreakerCrossInstanceIntegrationTest` run against a real Redis container and verify shared state, the single flight probe, and the mirror fallback, while `CircuitBreakerConfigTest`, `CircuitBreakerMetricsTest`, `RedisCircuitBreakerFactoryTest`, and `RedisCircuitBreakerEdgeTest` cover configuration, metrics, and the slow or unavailable Redis paths.
- Unit tests for the streaming protection and guard layer in `proxy/sse`: `AdaptiveSseFlushStrategyTest`,
  `SseFlushConfigReloaderTest`, `SseFlushHealthIndicatorTest`, `SseFlushLoadTest`, `SseFlushSecurityTest`,
  `BoundedLineBodyHandlerTest`, `DefaultSseLineGuardTest`, `TokenBucketTest`, `SseLineGuardPropertiesTest`, and
  `E12CoverageSupportTest` covering adaptive flushing, rate-limit token buckets, bounded byte decoding, OOM prevention,
  and hot-reload.
- Unit tests for the protocol layer in `proxy/protocol`: request translation, header construction, and stream normalization for the OpenAI, Anthropic, and Ollama dialects, including malformed input tolerance.
- Unit tests for the ledger in `ledger`: cost calculation, catalog matching, the pricing sync, and the dead letter fallback.
- MockWebServer based tests in `proxy/failover/FailoverOrchestratorTest` that stand in for real providers and verify failover on 500 and 429, no failover on 401 and 400, circuit opening and recovery, timeout behavior, and the RACE strategy.
- A MockWebServer based integration test in `proxy/protocol/ProtocolNormalizationIntegrationTest.java` that drives the real orchestrator, adapters, and normalizers against fake Anthropic and Ollama upstreams.
- A Testcontainers integration test in `security/ratelimit/RateLimitIntegrationTest.java` that runs the whole application against a real Redis container and exercises authentication, both limits, the model allow list, the failover path, and the fail closed behavior.
- A Testcontainers integration test in `ledger/UsageLedgerIntegrationTest.java` that runs the ledger against a real PostgreSQL container and verifies async persistence, duplicate request handling, the seeded prices, and a pricing refresh.
- A context load test that verifies the application starts without a live Redis or PostgreSQL.

JaCoCo enforces a minimum coverage of 95 percent on every counter at the bundle level. The circuit breaker and orchestrator retry and race coordination branches are excluded from the gate because they cannot be reached deterministically; the state transitions and failover semantics themselves are fully covered. The Mockito inline mock maker is attached as a Java agent through the `argLine` Maven property, so the suite is future proof against the JDK restriction on self attachment.

## Design notes

The request body wrapper exists because Spring's `ContentCachingRequestWrapper` cannot replay the body. It caches bytes as they are read and hands the same exhausted stream to any later reader, which would silently deliver an empty body to the controller. `CachedBodyHttpServletRequest` buffers once at construction and serves a fresh stream on every call, which keeps byte level fidelity including multi byte UTF-8 input.

The rate limiter uses a fixed window per key. Redis documents this as the simplest approach for per client quotas; its only weakness is a possible double burst at window boundaries, which is an acceptable trade for per minute limits. The entire check and consume sequence runs inside one Lua script so concurrent requests cannot overshoot the limit. See `src/main/resources/rate_limit.lua`.

The circuit breaker follows the canonical pattern described by Fowler and the microservices community: closed, open, and half open states with a cooldown and a single probe. State lives in an atomic reference with compare and set transitions, so the hot path is lock free. No background thread or timer exists; transitions out of open happen lazily on the next attempt. See `proxy/failover/ProviderCircuitBreaker.java`.

The distributed version in `proxy/failover/RedisCircuitBreaker.java` keeps that same state machine but stores state in a single Redis hash per provider. The open to half open transition and probe ownership are decided by an atomic Lua script, `src/main/resources/circuit_try_acquire.lua`, keyed on the instance id, so exactly one instance probes after a cooldown and a crashed probe owner is stolen after a lease. A dedicated Redis template with a short command timeout and a `Semaphore` bulkhead keep a slow Redis from stalling virtual threads; on a Redis error the in-memory mirror decides, so the gateway fails closed. Per provider state is exported to Prometheus through `proxy/failover/CircuitBreakerMetrics.java`.

Failover happens only before the first byte. The orchestrator returns a response whose status and content type are already known, so the controller never begins streaming before a winner exists. In the RACE strategy every losing attempt is cancelled at the socket level, which also stops the upstream provider from generating and billing further tokens.

Protocol normalization happens on two sides of the hop. The request adapters translate the client contract into the winning provider's native dialect before the attempt is sent, and the stream normalizer translates the response back as it flows. Each normalizer is a small state machine per stream, which keeps the mapping deterministic and lets the gateway capture exact token counts without buffering the body.

The ledger is deliberately asynchronous and decoupled. The streaming thread publishes a plain record after the last byte, and a bounded executor writes it to PostgreSQL. A failure to persist is logged and sent to a dead letter file, never thrown, because an exception from an asynchronous listener would be silently swallowed and the usage record lost. The database itself is optional at startup: migrations run from `DatabaseMigrator` on a retry schedule, so a gateway deployed before the database is provisioned comes up and starts serving immediately.

## Maintainer

AegisGate is maintained by Joshua Ike.