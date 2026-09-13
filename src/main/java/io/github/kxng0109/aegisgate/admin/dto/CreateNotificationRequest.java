package io.github.kxng0109.aegisgate.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Opt-in delivery subscription request. URL channels carry an http(s) target validated against SSRF at save
 * time; the email channel carries a recipient address. Secrets are referenced by environment variable name,
 * never sent in the clear.
 */
public record CreateNotificationRequest(
		@NotBlank @Size(max = 160) String scope,
		@NotBlank @Pattern(regexp = "email|teams|slack|webhook") String channel,
		@NotBlank @Size(max = 512) String target,
		@Size(max = 128) String secretRef,
		@Pattern(regexp = "warning|critical") String minSeverity
) {
}
