package io.github.kxng0109.aegisgate.admin;

import java.util.List;
import java.util.UUID;

import io.github.kxng0109.aegisgate.admin.dto.CreateNotificationRequest;
import io.github.kxng0109.aegisgate.admin.dto.NotificationResponse;
import io.github.kxng0109.aegisgate.budget.NotificationPreferenceService;
import io.github.kxng0109.aegisgate.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for opt-in alert delivery subscriptions under {@code /v1/admin/notifications}. Targets are
 * SSRF-validated at save time; secrets travel by environment reference only.
 */
@RestController
@RequestMapping("/v1/admin/notifications")
@RequiredArgsConstructor
@Tag(name = "Admin - Notifications", description = "Opt-in alert delivery (email, Teams, Slack, webhook)")
public class AdminNotificationController {

	private final NotificationPreferenceService preferenceService;

	@Operation(summary = "Create delivery subscription",
			description = "Subscribes a scope to a channel; duplicate (scope, channel, target) is a 409.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			})
	@ApiResponses(value = {
			@ApiResponse(responseCode = "201", description = "Subscription created"),
			@ApiResponse(responseCode = "400", description = "Validation failure"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect"),
			@ApiResponse(responseCode = "409", description = "Subscription already exists")
	})
	@PostMapping
	public ResponseEntity<NotificationResponse> createSubscription(
			@Valid @RequestBody CreateNotificationRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(NotificationResponse.from(
				preferenceService.create(request.scope(), request.channel(), request.target(),
						request.secretRef(), request.minSeverity())));
	}

	@Operation(summary = "List delivery subscriptions",
			description = "All channels subscribed for one alert scope.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			})
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Subscription list"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@GetMapping
	public ResponseEntity<List<NotificationResponse>> listSubscriptions(
			@Parameter(description = "Alert scope, e.g. KEY:<hex>") @RequestParam String scope) {
		return ResponseEntity.ok(preferenceService.list(scope).stream().map(NotificationResponse::from).toList());
	}

	@Operation(summary = "Delete delivery subscription",
			description = "Opts out; unknown ids are no-ops.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			})
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Subscription deleted"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> deleteSubscription(
			@Parameter(description = "Subscription id") @PathVariable UUID id) {
		preferenceService.delete(id);
		return ResponseEntity.noContent().build();
	}
}
