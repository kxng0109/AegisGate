package io.github.kxng0109.aegisgate.admin.dto;

/**
 * Response payload returned when an administrative cache purge operation completes.
 *
 * @param success      whether the purge operation succeeded
 * @param message      status message
 * @param evictedScope target scope that was purged (e.g. "ALL", or tenant ID)
 */
public record CachePurgeResponse(
		boolean success,
		String message,
		String evictedScope
) {
}
