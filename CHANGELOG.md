# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] - 2026-09-01

### Added

- **Java 25 Virtual Thread Core**: High-throughput non-blocking request execution utilizing Java 25 Virtual Threads
  (`spring.threads.virtual.enabled=true`) for zero carrier thread pinning during upstream I/O.
- **Zero-Buffer SSE Streaming Relay**: Chunk-by-chunk HTTP/2 Server-Sent Events (SSE) proxying with
  `BodySubscribers.ofLines()` providing `<1KB` heap memory footprint per connection and bidirectional TCP backpressure
  propagation.
- **Distributed Token-Bucket Rate Limiting**: Atomic Redis Lua scripts enforcing multi-dimensional limits (Requests Per
  Minute, Requests Per Day, Tokens Per Minute, Tokens Per Day, and Concurrent Requests) per Virtual Key and Tenant.
- **Resilient Multi-Provider Failover Matrix**:
    - Zero-data-loss upstream failover buffering only the first $N$ bytes prior to streaming commit.
    - Redis Sliding Window distributed circuit breakers with `CLOSED`, `OPEN`, and `HALF_OPEN` state transitions.
    - Strict Vector Space Invariance guardrails preventing cross-model embedding failovers.
- **Bidirectional Protocol Normalization**:
    - Transparent translation between OpenAI Chat Completions, Anthropic Messages API, and Ollama dialects.
    - Streaming SSE chunk translation converting Anthropic/Ollama event structures into standardized OpenAI chunk
      format.
- **High-Throughput Embeddings Gateway (`/v1/embeddings`)**:
    - Transparent auto-batching splitting oversized embedding payloads across upstream limits (Cohere: 96, Ollama: 32,
      OpenAI: 2048) with a 4-permit virtual-thread semaphore.
    - Native IEEE 754 float32 Little-Endian raw byte packing and Base64 encoding.
- **Multi-Tier Semantic & Exact Caching Layer**:
    - **Tier 0**: In-process Caffeine exact match cache for hot in-memory prompts (<0.1ms latency).
    - **Tier 1**: Distributed Redis exact match cache with deterministic SHA-256 compound keys.
    - **Tier 2**: RediSearch Vector Similarity Search (VSS) using HNSW cosine index
      ($M=16, \text{EF\_CONSTRUCTION}=200$) with conversation history prefix partitioning ($H_{\text{prefix}}$) over
      turns $[0..N-2]$.
    - **Semantic Guardrails**: Anti-hallucination post-retrieval validation rejecting intent reversals (e.g., `enable`/
      `disable`, `with`/`without`, negation tokens) and conflicting proper noun/numerical entities.
    - **SingleFlight Stampede Defense**: Virtual-thread deduplication of concurrent duplicate requests.
    - **Synthetic SSE Reconstitution**: Fast synthetic OpenAI SSE chunk emitter for cache hits with TTFT $<5\text{ms}$.
    - **Admin Control Plane**: Endpoints for `GET /v1/admin/cache/stats` and `DELETE /v1/admin/cache` (tenant and global
      cache purges).
- **Asynchronous Usage & Cost Ledger**:
    - Asynchronous event-driven usage recording via Spring Application Events and Redis Streams.
    - Batch persistence into PostgreSQL with model pricing auto-sync from live catalog.
- **OpenAPI 3.1 & Interactive Swagger UI**:
    - SpringDoc OpenAPI 3.1 configuration at `/swagger-ui.html` and `/v3/api-docs`.
    - Grouped specs for `1-public-gateway`, `2-admin-portal`, and `3-observability` with dynamic tag discovery and
      security schemes.
- **Security & Network Hardening**:
    - SSRF protection rejecting RFC 1918 private subnets, loopbacks, link-local, multicast, and cloud metadata endpoints
      (`169.254.169.254`).
    - Hop-by-hop header stripping and constant-time API key verification (`MessageDigest.isEqual`).
    - Flyway database migration runner with automated checksum self-healing.
- **Testing & Verification**:
    - Comprehensive unit and integration test suite with 721 tests passing (100% green).
    - JaCoCo coverage floor $\ge 95\%$ enforced across all 6 counter metrics.
