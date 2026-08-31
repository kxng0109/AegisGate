package io.github.kxng0109.aegisgate.admin.dto;

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
public record ProblemDetailResponse(
		String type,
		String title,
		int status,
		String detail,
		String instance,
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
