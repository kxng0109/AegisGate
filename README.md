# AegisGate

AegisGate is an AI gateway built in Java 25 on Spring Boot 4.1. It sits between your applications and large language model providers, exposing a single OpenAI compatible chat completions endpoint while handling authentication, rate limiting, and secure upstream forwarding.

The project is developed in phases. Phase 1 delivered a transparent SSE streaming proxy with SSRF defense and header sanitization. Phase 2 added virtual API key authentication and distributed rate limiting backed by Redis. Later phases will add failover routing, protocol normalization, and an async usage ledger, following the architectural master plan.

## What it does

- Streams chat completions from a configured upstream provider to your client with zero buffering of the full response body. Each request runs on its own virtual thread.
- Authenticates every request with a gateway managed virtual API key. Keys are shown to the caller only once and are stored exclusively as SHA-256 hashes.
- Enforces two independent limits per key: requests per minute (RPM) and tokens per minute (TPM). The check and consume step is atomic, executed in a single Redis Lua script.
- Fails closed. If Redis is unreachable, requests are rejected with a 503 instead of being allowed through unthrottled.
- Rejects oversized request bodies with a 413 before they can exhaust memory.
- Validates upstream URLs against private, loopback, link local, multicast, and cloud metadata ranges before any connection is attempted.
- Strips client supplied identity and authorization headers and injects the configured upstream key.
- Never logs key material. Every sensitive value is wrapped so that its string representation is masked.

## How a request flows

An incoming request to `/v1/chat/completions` passes through three stages before the upstream provider is contacted:

1. `RequestBodyCachingFilter` wraps the request in a `CachedBodyHttpServletRequest`. The body is buffered once, up to a configured cap, so it can be read both by the authentication layer and later by the controller. See `security/filter/RequestBodyCachingFilter.java` and `security/filter/CachedBodyHttpServletRequest.java`.

2. `KeyAuthFilter` authenticates the bearer token, checks the model allow list, estimates the token cost of the request, and consults the rate limiter. It sets the `X-RateLimit` response headers and either continues the chain or answers with 401, 403, 429, or 503. See `security/filter/KeyAuthFilter.java`.

3. `ProxyController` accepts the request and hands it to `ProxyService`, which validates the upstream URL, builds a sanitized request, and streams the SSE response back line by line. See `proxy/ProxyController.java` and `proxy/ProxyService.java`.

Filter registration and ordering are defined in `security/filter/SecurityFilterConfig.java`.

## Project layout

The code is organized by responsibility under `src/main/java/io/github/kxng0109/aegisgate`:

- `contracts` contains the shared immutable types: `SHA256Hash`, `VirtualApiKey`, `RateLimitDecision`, `RateLimitState`, `RejectionReason`, `BootstrapKey`, and `GatewayProperties`.
- `security` contains the Phase 1 controls: `SsrfValidator`, `HeaderSanitizer`, and `CidrRange`.
- `security/filter` contains the servlet filter pipeline: the replayable body wrapper, the authentication filter, and the registration configuration.
- `security/ratelimit` contains the distributed limiter: `RateLimitEngine`, `RateLimitScriptConfig`, `KeyManagementService`, and `BootstrapKeySeeder`.
- `proxy` contains the forwarding layer: `ProxyController`, `ProxyService`, and the shared `HttpClient` bean in `proxy/config/HttpClientConfig.java`.
- `config` contains the upstream configuration binding `UpstreamConfig` and the `SensitiveString` value wrapper.

The Lua script that implements the atomic RPM and TPM counters lives in `src/main/resources/rate_limit.lua`. Runtime configuration lives in `src/main/resources/application.properties`.

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

Provide the upstream provider key and, optionally, a bootstrap key for local testing:

```bash
export GATEWAY_API_KEY=your-provider-key
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

All configuration is documented inside `src/main/resources/application.properties`. The most important settings:

- `gateway.base-url` and `gateway.api-key.value` point at the upstream provider. The key is injected from the `GATEWAY_API_KEY` environment variable.
- `spring.data.redis.*` controls the Redis connection and the Lettuce pool. The pool is bounded so that Redis exhaustion surfaces quickly instead of blocking threads forever.
- `gateway.bootstrap-keys.seed-interval` controls how often key seeding is retried if Redis was unavailable at startup.

Bootstrap keys are provisioned exclusively through environment variables such as `GATEWAY_BOOTSTRAPKEYS_0_PLAINTEXTKEY`. Plaintext keys never belong in the repository. The `BootstrapKeySeeder` in `security/ratelimit/BootstrapKeySeeder.java` seeds them after the application is ready and retries on a schedule until Redis is reachable.

## API

### POST `/v1/chat/completions`

The request body is an OpenAI style chat completion payload. The gateway forwards it to the upstream provider, including `stream` semantics, and returns the SSE stream.

Authentication uses the `Authorization` header:

```bash
curl -N http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer gw-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o","messages":[{"role":"user","content":"Hello"}]}'
```

Status codes:

- `200` the stream started and is being relayed.
- `401` the key is missing, malformed, or unknown.
- `403` the key is disabled or the requested model is not allowed for it.
- `413` the request body exceeds the configured limit.
- `429` a rate limit was exceeded. The `Retry-After` header tells the client when to retry.
- `503` the authentication or rate limiting service is unavailable. The gateway refuses to serve rather than serving unthrottled.

On success the response carries the rate limit state in the `X-RateLimit-Limit-RPM`, `X-RateLimit-Remaining-RPM`, `X-RateLimit-Reset-RPM`, and the matching TPM headers. The reset values are epoch seconds.

## Security model

- Keys are 32 random base64url characters behind a `gw-` prefix, giving 192 bits of entropy. They are generated with `SecureRandom` in `KeyManagementService`.
- Only the SHA-256 digest is stored in Redis. `SHA256Hash.toString()` is masked, and no log statement ever receives a plaintext key.
- Key metadata is cached locally with a five second expiry and a bounded size. Confirmed misses are cached as well, so invalid keys cannot flood Redis.
- The gateway fails closed. Redis connectivity errors and pool exhaustion are caught in `RateLimitEngine` and mapped to 503, and lookup failures in the authentication path are handled the same way.
- Upstream URLs are validated against a private address block list before any connection is made, and redirects are never followed.
- Client supplied headers that could spoof identity are stripped before forwarding.

## Testing

Run the full suite with coverage and the packaging step:

```bash
./mvnw clean verify
```

The suite currently has 241 tests:

- Unit tests for hashing, key management, the rate limit engine, both filters, the body wrapper, and the Phase 1 proxy and security components.
- A Testcontainers integration test in `security/ratelimit/RateLimitIntegrationTest.java` that runs the whole application against a real Redis container and exercises authentication, both limits, the model allow list, and the fail closed path.
- A context load test that verifies the application starts without a live Redis.

JaCoCo enforces a minimum coverage of 95 percent on every counter at the bundle level. The current coverage is around 99 percent on instructions and lines. The Mockito inline mock maker is attached as a Java agent through the `argLine` Maven property, so the suite is future proof against the JDK restriction on self attachment.

## Design notes

The request body wrapper exists because Spring's `ContentCachingRequestWrapper` cannot replay the body. It caches bytes as they are read and hands the same exhausted stream to any later reader, which would silently deliver an empty body to the controller. `CachedBodyHttpServletRequest` buffers once at construction and serves a fresh stream on every call, which keeps byte level fidelity including multi byte UTF-8 input.

The rate limiter uses a fixed window per key. Redis documents this as the simplest approach for per client quotas; its only weakness is a possible double burst at window boundaries, which is an acceptable trade for per minute limits. The entire check and consume sequence runs inside one Lua script so concurrent requests cannot overshoot the limit. See `src/main/resources/rate_limit.lua`.