package io.github.kxng0109.aegisgate.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * RFC 9457 Problem Details for HTTP APIs.
 *
 * @param type      URI reference identifying the problem type
 * @param title     short, human-readable summary of the problem type
 * @param status    HTTP status code
 * @param detail    human-readable explanation specific to this occurrence
 * @param instance  URI reference identifying the specific occurrence
 * @param timestamp time when error occurred
 */
@Schema(name = "ProblemDetailResponse", description = "RFC 9457 compliant error response payload")
public record ProblemDetailResponse(
		@Schema(description = "URI reference identifying the problem type", example = "about:blank")
		String type,

		@Schema(description = "Short human-readable summary of the problem type", example = "Unauthorized")
		String title,

		@Schema(description = "HTTP status code", example = "401")
		int status,

		@Schema(description = "Human-readable explanation specific to this error occurrence", example = "Invalid or missing master admin authentication credentials")
		String detail,

		@Schema(description = "URI reference identifying the specific occurrence", example = "/v1/admin/keys")
		String instance,

		@Schema(description = "Timestamp when the error occurred (ISO-8601)", example = "2026-09-01T12:00:00Z")
		Instant timestamp
) {
	/**
	 * Convenience factory for standard problem details.
	 *
	 * @param title    problem title
	 * @param status   HTTP status
	 * @param detail   explanatory message
	 * @param instance request path or instance URI
	 * @return new ProblemDetailResponse
	 */
	public static ProblemDetailResponse of(String title, int status, String detail, String instance) {
		return new ProblemDetailResponse(
				"https://aegisgate.io/errors/" + status,
				title,
				status,
				detail,
				instance,
				Instant.now()
		);
	}
}
