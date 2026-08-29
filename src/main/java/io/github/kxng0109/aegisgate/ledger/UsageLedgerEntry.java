package io.github.kxng0109.aegisgate.ledger;

import jakarta.persistence.*;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

/**
 * One persisted row of usage: who asked, which provider and model served the request, how many tokens were billed, and
 * what it cost.
 *
 * <p>Money is stored as micro dollars in a {@code long} so arithmetic never
 * touches floating point. The {@code requestId} is unique, which makes the ledger idempotent: a duplicate event cannot
 * create a second row. The schema is owned by Flyway ({@code V1__usage_ledger.sql}), and Hibernate only validates
 * against it.</p>
 */
@Entity
@Table(name = "usage_ledger", indexes = {
		@Index(name = "idx_usage_ledger_owner_id", columnList = "ownerId"),
		@Index(name = "idx_usage_ledger_created_at", columnList = "createdAt")
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

	/**
	 * No argument constructor required by the JPA specification.
	 */
	protected UsageLedgerEntry() {
	}

	/**
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
	}
}