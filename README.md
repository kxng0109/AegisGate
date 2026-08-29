# AegisGate

AegisGate is an AI gateway built in Java 25 on Spring Boot 4.1. It sits between your applications and large language model providers, exposing a single OpenAI compatible chat completions endpoint while handling authentication, rate limiting, and secure upstream forwarding.

The project is developed in phases. Phase 1 delivered a transparent SSE streaming proxy with SSRF defense and header sanitization. Phase 2 added virtual API key authentication and distributed rate limiting backed by Redis. Phase 3 added resilient multi provider failover: every model now maps to a chain of providers with automatic failover, per provider circuit breakers, and an optional race strategy. Later phases add protocol normalization and an async usage ledger, following the architectural master plan.

## What it does

- Streams chat completions from a configured upstream provider chain to your client with zero buffering of the full response body. Each request runs on its own virtual thread.
- Authenticates every request with a gateway managed virtual API key. Keys are shown to the caller only once and are stored exclusively as SHA-256 hashes.
- Enforces two independent limits per key: requests per minute (RPM) and tokens per minute (TPM). The check and consume step is atomic, executed in a single Redis Lua script.
- Fails closed. If Redis is unreachable, requests are rejected with a 503 instead of being allowed through unthrottled.
- Rejects oversized request bodies with a 413 before they can exhaust memory.
- Routes every model through an ordered provider chain. When a provider returns a transient error (429, a 5xx, a dropped connection, or a timeout), the request automatically fails over to the next provider.
- Protects each provider with a circuit breaker. After a few consecutive failures the provider is skipped for a cooldown period, then a single probe decides whether normal service resumes.
- Never fails over on 401, 403, or 400, because a client or key problem cannot be fixed by another provider.
- Validates upstream URLs against private, loopback, link local, multicast, and cloud metadata ranges before any connection is attempted.
- Strips client supplied identity and authorization headers and injects the configured upstream key.
- Never logs key material. Every sensitive value is wrapped so that its string representation is masked.

## How a request flows

An incoming request to `/v1/chat/completions` passes through several stages:

1. `RequestBodyCachingFilter` wraps the request in a `CachedBodyHttpServletRequest`. The body is buffered once, up to a configured cap, so it can be read twice. See `security/filter/RequestBodyCachingFilter.java` and `security/filter/CachedBodyHttpServletRequest.java`.

2. `KeyAuthFilter` authenticates the bearer token, checks the model allow list, estimates the token cost of the request, and consults the rate limiter. It sets the `X-RateLimit` response headers and either continues the chain or answers with 401, 403, 429, or 503. See `security/filter/KeyAuthFilter.java`.

3. `ProxyController` resolves the requested model to a `ModelAlias` and asks `FailoverOrchestrator` to pick a winning provider. The orchestrator walks the chain, applies the circuit breakers, and returns the winning provider's streaming response. The controller relays that response line by line with zero buffering. See `proxy/ProxyController.java` and `proxy/failover/FailoverOrchestrator.java`.

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
  aliases:
    gpt-5.6-luna:
      chain:
        - provider-name: openai
      strategy: SEQUENTIAL
    fast:
      chain:
        - provider-name: openai
        - provider-name: openrouter
      strategy: SEQUENTIAL
    race-demo:
      chain:
        - provider-name: openai
        - provider-name: openrouter
      strategy: RACE
```

The `type` field selects the protocol dialect. Today every configured provider speaks the OpenAI compatible protocol (`OPENAI`), which covers OpenAI itself, OpenRouter, Groq, DeepSeek, Mistral, Together, vLLM, and most local servers. Anthropic and Ollama dialects are planned for the protocol normalization phase.

The behavior of a chain is decided by the classification rules in `FailoverOrchestrator`:

- A 200 response with an event stream content type is a success.
- A 429 or any 5xx is transient and fails over to the next provider.
- A 401, 403, or 400 is non transient and is returned to the client as is.
- Timeouts and connection failures are transient and fail over.
- Failover happens only before the first byte is sent to the client. Once streaming starts, switching providers is impossible.

Each provider has a `ProviderCircuitBreaker`. It starts closed, opens after three consecutive failures, stays open for thirty seconds, then admits a single probe. A successful probe closes the circuit; a failed probe reopens it. See `proxy/failover/ProviderCircuitBreaker.java`.

When every provider fails, the client sees a clean error: 502 when providers returned errors, 503 when nothing usable was reachable, 504 when the chain timed out. See `proxy/failover/GatewayExceptionHandler.java`.

## Project layout

The code is organized by responsibility under `src/main/java/io/github/kxng0109/aegisgate`:

- `contracts` contains the shared immutable types: `SHA256Hash`, `VirtualApiKey`, `RateLimitDecision`, `RateLimitState`, `RejectionReason`, `BootstrapKey`, `ProviderConfig`, `ProviderRef`, `ModelAlias`, `ProviderType`, `FailoverStrategy`, and `GatewayProperties`.
- `security` contains the Phase 1 controls: `SsrfValidator`, `HeaderSanitizer`, and `CidrRange`.
- `security/filter` contains the servlet filter pipeline: the replayable body wrapper, the authentication filter, and the registration configuration.
- `security/ratelimit` contains the distributed limiter: `RateLimitEngine`, `RateLimitScriptConfig`, `KeyManagementService`, and `BootstrapKeySeeder`.
- `proxy/failover` contains the Phase 3 routing layer: `FailoverOrchestrator`, `ProviderCircuitBreaker`, `ProviderClientAdapter`, `ProviderResponse`, `UpstreamUnavailableException`, and `GatewayExceptionHandler`.
- `proxy` contains the controller and the shared `HttpClient` bean in `proxy/config/HttpClientConfig.java`.
- `config` contains the `SensitiveString` value wrapper.

The Lua script that implements the atomic RPM and TPM counters lives in `src/main/resources/rate_limit.lua`. Runtime configuration lives in `src/main/resources/application.yml`.

## Technology stack

- Java 25 LTS with virtual threads enabled
- Spring Boot 4.1 and Spring MVC
- Redis via Spring Data Redis and Lettuce, with a connection pool
- Caffeine for the short lived key lookup cache
- JSpecify nullness annotations at package level
- JUnit Jupiter, Mockito, and Testcontainers for testing
- JaCoCo with a strict coverage gate

Dependency versions are managed by the Spring Boot 4.1 BOM. See `pom.xml`.

## Prerequisites

- JDK 25
- Redis 7 or newer, reachable at `localhost:6379` by default
- Docker, only if you want to run the Testcontainers integration tests

The Maven wrapper is included, so no separate Maven installation is needed.

## Getting started

Start Redis. On a development machine the fastest option is a container:

```bash
docker run -d --name aegisgate-redis -p 6379:6379 redis:7-alpine
```

Provide a provider key and, optionally, a bootstrap key for local testing:

```bash
export OPENAI_API_KEY=your-provider-key
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
- `gateway.aliases` maps each client facing model name to a provider chain and a strategy.
- `spring.data.redis.*` controls the Redis connection and the Lettuce pool.
- `gateway.bootstrap-keys-seed-interval` controls how often key seeding is retried if Redis was unavailable at startup.

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
  -d '{"model":"gpt-5.6-luna","messages":[{"role":"user","content":"Hello"}]}'
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

The suite currently has 247 tests:

- Unit tests for hashing, key management, the rate limit engine, both filters, the body wrapper, the circuit breaker, the provider adapter, the orchestrator, the error handler, and the Phase 1 security components.
- MockWebServer based tests in `proxy/failover/FailoverOrchestratorTest` that stand in for real providers and verify failover on 500 and 429, no failover on 401 and 400, circuit opening and recovery, timeout behavior, and the RACE strategy.
- A Testcontainers integration test in `security/ratelimit/RateLimitIntegrationTest.java` that runs the whole application against a real Redis container and exercises authentication, both limits, the model allow list, the failover path, and the fail closed behavior.
- A context load test that verifies the application starts without a live Redis.

JaCoCo enforces a minimum coverage of 95 percent on every counter at the bundle level. The circuit breaker and orchestrator retry and race coordination branches are excluded from the gate because they cannot be reached deterministically; the state transitions and failover semantics themselves are fully covered. The Mockito inline mock maker is attached as a Java agent through the `argLine` Maven property, so the suite is future proof against the JDK restriction on self attachment.

## Design notes

The request body wrapper exists because Spring's `ContentCachingRequestWrapper` cannot replay the body. It caches bytes as they are read and hands the same exhausted stream to any later reader, which would silently deliver an empty body to the controller. `CachedBodyHttpServletRequest` buffers once at construction and serves a fresh stream on every call, which keeps byte level fidelity including multi byte UTF-8 input.

The rate limiter uses a fixed window per key. Redis documents this as the simplest approach for per client quotas; its only weakness is a possible double burst at window boundaries, which is an acceptable trade for per minute limits. The entire check and consume sequence runs inside one Lua script so concurrent requests cannot overshoot the limit. See `src/main/resources/rate_limit.lua`.

The circuit breaker follows the canonical pattern described by Fowler and the microservices community: closed, open, and half open states with a cooldown and a single probe. State lives in an atomic reference with compare and set transitions, so the hot path is lock free. No background thread or timer exists; transitions out of open happen lazily on the next attempt. See `proxy/failover/ProviderCircuitBreaker.java`.

Failover happens only before the first byte. The orchestrator returns a response whose status and content type are already known, so the controller never begins streaming before a winner exists. In the RACE strategy every losing attempt is cancelled at the socket level, which also stops the upstream provider from generating and billing further tokens.