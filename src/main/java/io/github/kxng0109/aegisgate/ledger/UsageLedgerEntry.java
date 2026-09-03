package io.github.kxng0109.aegisgate.ledger;

import jakarta.persistence.*;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * One persisted row of usage: who asked, which provider and model served the request, how many tokens were billed,
 * prompt caching telemetry, and financial cost.
 *
 * <p>Money is stored as micro dollars in a {@code long} so arithmetic never
 * touches floating point. The {@code requestId} is unique, which makes the ledger idempotent: a duplicate event cannot
 * create a second row. The schema is owned by Flyway ({@code V1__usage_ledger.sql} through {@code V4__finops_focus_prompt_caching.sql}),
 * and Hibernate validates against it.</p>
 */
@Entity
@Table(name = "usage_ledger", indexes = {
		@Index(name = "idx_usage_ledger_owner_id", columnList = "ownerId"),
		@Index(name = "idx_usage_ledger_created_at", columnList = "createdAt"),
		@Index(name = "idx_usage_ledger_request_hash", columnList = "requestHash")
})
@Getter
public class UsageLedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, unique = true)
	private UUID requestId;

	@Column(nullable = false, length = 64)
	private String ownerId;

	@Column(nullable = false, length = 64)
	private String provider;

	@Column(nullable = false, length = 128)
	private String model;

	@Column(nullable = false)
	private int promptTokens;

	@Column(nullable = false)
	private int completionTokens;

	@Column(nullable = false)
	private int totalTokens;

	@Column(nullable = false)
	private long costUsdMicros;

	@Column(nullable = false)
	private long durationMs;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "uncached_prompt_tokens", nullable = false)
	private int uncachedPromptTokens;

	@Column(name = "cache_read_tokens", nullable = false)
	private int cacheReadTokens;

	@Column(name = "cache_write_tokens", nullable = false)
	private int cacheWriteTokens;

	@Column(name = "reasoning_tokens", nullable = false)
	private int reasoningTokens;

	@Column(name = "effective_cost_micros", nullable = false)
	private long effectiveCostMicros;

	@Column(name = "billed_cost_micros", nullable = false)
	private long billedCostMicros;

	@Column(name = "request_hash", length = 64)
	private @Nullable String requestHash;

	/**
	 * No argument constructor required by the JPA specification.
	 */
	protected UsageLedgerEntry() {
	}

	/**
	 * Backwards-compatible constructor.
	 *
	 * @param requestId        correlation id of the proxied request
	 * @param ownerId          owner of the virtual API key that authenticated it
	 * @param provider         name of the provider that served the request
	 * @param model            model id reported by the provider
	 * @param promptTokens     input tokens billed by the provider
	 * @param completionTokens output tokens billed by the provider
	 * @param totalTokens      the two token counts summed
	 * @param costUsdMicros    cost in micro dollars
	 * @param durationMs       wall clock time of the stream in milliseconds
	 * @param createdAt        when the request completed
	 */
	public UsageLedgerEntry(
			UUID requestId,
			@Nullable String ownerId,
			String provider,
			String model,
			int promptTokens,
			int completionTokens,
			int totalTokens,
			long costUsdMicros,
			long durationMs,
			Instant createdAt
	) {
		this(
				requestId,
				ownerId,
				provider,
				model,
				promptTokens,
				completionTokens,
				totalTokens,
				costUsdMicros,
				durationMs,
				createdAt,
				promptTokens,
				0,
				0,
				0,
				costUsdMicros,
				costUsdMicros,
				null
		);
	}

	/**
	 * Full FOCUS 1.4 FinOps constructor with granular prompt caching and cryptographic request hashes.
	 *
	 * @param requestId            correlation id of the proxied request
	 * @param ownerId              owner of the virtual API key that authenticated it
	 * @param provider             name of the provider that served the request
	 * @param model                model id reported by the provider
	 * @param promptTokens         total input tokens billed by the provider
	 * @param completionTokens     output tokens billed by the provider
	 * @param totalTokens          the two token counts summed
	 * @param costUsdMicros        list cost in micro dollars
	 * @param durationMs           wall clock time of the stream in milliseconds
	 * @param createdAt            when the request completed
	 * @param uncachedPromptTokens input tokens not served from cache
	 * @param cacheReadTokens      input tokens served from cache
	 * @param cacheWriteTokens     input tokens written to cache
	 * @param reasoningTokens      reasoning / thinking tokens emitted during generation
	 * @param effectiveCostMicros  effective cost in micro dollars
	 * @param billedCostMicros     billed cost in micro dollars
	 * @param requestHash          optional cryptographic HMAC request fingerprint
	 */
	public UsageLedgerEntry(
			UUID requestId,
			@Nullable String ownerId,
			String provider,
			String model,
			int promptTokens,
			int completionTokens,
			int totalTokens,
			long costUsdMicros,
			long durationMs,
			Instant createdAt,
			int uncachedPromptTokens,
			int cacheReadTokens,
			int cacheWriteTokens,
			int reasoningTokens,
			long effectiveCostMicros,
			long billedCostMicros,
			@Nullable String requestHash
	) {
		this.requestId = requestId;
		this.ownerId = ownerId == null ? "unknown" : ownerId;
		this.provider = provider;
		this.model = model;
		this.promptTokens = promptTokens;
		this.completionTokens = completionTokens;
		this.totalTokens = totalTokens;
		this.costUsdMicros = costUsdMicros;
		this.durationMs = durationMs;
		this.createdAt = createdAt;
		this.uncachedPromptTokens = uncachedPromptTokens;
		this.cacheReadTokens = cacheReadTokens;
		this.cacheWriteTokens = cacheWriteTokens;
		this.reasoningTokens = reasoningTokens;
		this.effectiveCostMicros = effectiveCostMicros;
		this.billedCostMicros = billedCostMicros;
		this.requestHash = requestHash;
	}
}