package io.github.kxng0109.aegisgate.admin.dto;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Filter parameters for querying usage ledger summaries and entries.
 *
 * @param ownerId  optional tenant/owner identifier filter
 * @param provider optional upstream provider name filter (e.g. "openai", "anthropic", "ollama")
 * @param model    optional upstream model identifier filter
 * @param from     optional start timestamp (inclusive)
 * @param to       optional end timestamp (inclusive)
 */
public record LedgerFilter(
		@Nullable String ownerId,
		@Nullable String provider,
		@Nullable String model,
		@Nullable Instant from,
		@Nullable Instant to
) {
	/**
	 * Canonical constructor with sanitization of blank strings to {@code null}.
	 */
	public LedgerFilter {
		ownerId = (ownerId == null || ownerId.isBlank()) ? null : ownerId.trim();
		provider = (provider == null || provider.isBlank()) ? null : provider.trim();
		model = (model == null || model.isBlank()) ? null : model.trim();
	}
}
