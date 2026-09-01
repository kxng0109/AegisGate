package io.github.kxng0109.aegisgate.admin.dto;

import java.util.List;

/**
 * Standardized pagination envelope for administrative listing endpoints.
 *
 * @param <T>           type of item in the page
 * @param content       items on this page
 * @param page          zero-based page index
 * @param size          page size limit
 * @param totalElements total number of matching items across all pages
 * @param totalPages    total number of available pages
 * @param hasNext       {@code true} if subsequent pages exist
 */
public record PageResponse<T>(
		List<T> content,
		int page,
		int size,
		long totalElements,
		int totalPages,
		boolean hasNext
) {
}
