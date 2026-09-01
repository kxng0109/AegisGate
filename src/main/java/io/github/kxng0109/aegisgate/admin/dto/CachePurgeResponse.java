package io.github.kxng0109.aegisgate.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response payload returned when an administrative cache purge operation completes.
 *
 * @param success      whether the purge operation succeeded
 * @param message      status message
 * @param evictedScope target scope that was purged (e.g. "ALL", or tenant ID)
 */
@Schema(name = "CachePurgeResponse", description = "Confirmation details for an executed cache purge operation")
public record CachePurgeResponse(
		@Schema(description = "Whether the purge succeeded", example = "true")
		boolean success,

		@Schema(description = "Human-readable status message", example = "Purged cache for tenant tenant-corp")
		String message,

		@Schema(description = "Evicted tenant scope or ALL for global purge", example = "tenant-corp")
		String evictedScope
) {
}
