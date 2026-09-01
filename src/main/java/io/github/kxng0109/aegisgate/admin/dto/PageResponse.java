package io.github.kxng0109.aegisgate.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

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
@Schema(name = "PageResponse", description = "Standardized pagination envelope for administrative queries")
public record PageResponse<T>(
		@Schema(description = "List of items on current page")
		List<T> content,

		@Schema(description = "Current page index (0-based)", example = "0")
		int page,

		@Schema(description = "Page size limit", example = "20")
		int size,

		@Schema(description = "Total number of matching elements across all pages", example = "150")
		long totalElements,

		@Schema(description = "Total number of pages available", example = "8")
		int totalPages,

		@Schema(description = "Whether subsequent pages exist", example = "true")
		boolean hasNext
) {
}
