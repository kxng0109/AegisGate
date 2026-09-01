package io.github.kxng0109.aegisgate.admin;

import io.github.kxng0109.aegisgate.admin.dto.LedgerEntryResponse;
import io.github.kxng0109.aegisgate.admin.dto.LedgerFilter;
import io.github.kxng0109.aegisgate.admin.dto.LedgerSummaryResponse;
import io.github.kxng0109.aegisgate.admin.dto.PageResponse;
import io.github.kxng0109.aegisgate.config.OpenApiConfig;
import io.github.kxng0109.aegisgate.ledger.UsageLedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * REST controller exposing tenant billing summaries and audit queries under {@code /v1/admin/ledger}.
 */
@RestController
@RequestMapping("/v1/admin/ledger")
@RequiredArgsConstructor
@Tag(name = "Admin - Usage Ledger", description = "Multi-dimensional tenant billing summaries and transaction audit log queries")
public class AdminLedgerController {

	private final UsageLedgerService usageLedgerService;

	/**
	 * Returns aggregated token consumption, USD costs, and duration metrics with multi-dimensional breakdowns.
	 *
	 * @param ownerId  optional tenant/owner filter
	 * @param provider optional provider filter
	 * @param model    optional model filter
	 * @param from     optional start timestamp (inclusive ISO-8601)
	 * @param to       optional end timestamp (inclusive ISO-8601)
	 * @return HTTP 200 OK with aggregated usage summary
	 */
	@Operation(
			summary = "Get aggregated billing and token usage summary",
			description = "Calculates exact token totals, micro-dollar financial costs, and latency metrics across PostgreSQL 16+ covering indexes with breakdowns by tenant owner, model, and provider.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "Usage summary aggregation computed successfully",
					content = @Content(
							mediaType = "application/json",
							schema = @Schema(implementation = LedgerSummaryResponse.class),
							examples = @ExampleObject(
									name = "Billing Summary Response",
									value = """
											{
											  "totalRequests": 1500,
											  "totalPromptTokens": 750000,
											  "totalCompletionTokens": 250000,
											  "totalTokens": 1000000,
											  "totalCostMicros": 1500000,
											  "totalCostUsd": "1.500000",
											  "averageDurationMs": 142.5,
											  "byOwner": [],
											  "byModel": [],
											  "byProvider": []
											}
											"""
							)
					)
			),
			@ApiResponse(responseCode = "400", description = "Invalid date range (max 90 days allowed) or future start date"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@GetMapping("/summary")
	public ResponseEntity<LedgerSummaryResponse> getSummary(
			@Parameter(description = "Optional tenant owner filter", example = "tenant-corp")
			@RequestParam(value = "ownerId", required = false) String ownerId,
			@Parameter(description = "Optional provider filter (e.g. openai, anthropic, ollama)", example = "openai")
			@RequestParam(value = "provider", required = false) String provider,
			@Parameter(description = "Optional model identifier filter", example = "gpt-56-luna")
			@RequestParam(value = "model", required = false) String model,
			@Parameter(description = "Start timestamp (ISO-8601)", example = "2026-08-01T00:00:00Z")
			@RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@Parameter(description = "End timestamp (ISO-8601)", example = "2026-09-01T00:00:00Z")
			@RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
	) {
		LedgerFilter filter = new LedgerFilter(ownerId, provider, model, from, to);
		LedgerSummaryResponse response = usageLedgerService.getSummary(filter);
		return ResponseEntity.ok(response);
	}

	/**
	 * Returns paginated raw ledger entries matching the filter for auditing and reporting.
	 *
	 * @param ownerId  optional tenant/owner filter
	 * @param provider optional provider filter
	 * @param model    optional model filter
	 * @param from     optional start timestamp (inclusive ISO-8601)
	 * @param to       optional end timestamp (inclusive ISO-8601)
	 * @param pageable pagination and sorting parameters
	 * @return HTTP 200 OK with paginated entries
	 */
	@Operation(
			summary = "Query paginated transaction audit logs",
			description = "Returns paginated audit records for completed client requests with allowlisted sort parameters (`id`, `ownerId`, `provider`, `model`, `createdAt`, `costUsdMicros`, `durationMs`).",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Paginated audit logs retrieved"),
			@ApiResponse(responseCode = "400", description = "Invalid sort property or page parameter"),
			@ApiResponse(responseCode = "401", description = "Unauthorized")
	})
	@GetMapping("/entries")
	public ResponseEntity<PageResponse<LedgerEntryResponse>> getEntries(
			@Parameter(description = "Optional tenant owner filter", example = "tenant-corp")
			@RequestParam(value = "ownerId", required = false) String ownerId,
			@Parameter(description = "Optional provider filter", example = "openai")
			@RequestParam(value = "provider", required = false) String provider,
			@Parameter(description = "Optional model filter", example = "gpt-56-luna")
			@RequestParam(value = "model", required = false) String model,
			@Parameter(description = "Start timestamp (ISO-8601)", example = "2026-08-01T00:00:00Z")
			@RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@Parameter(description = "End timestamp (ISO-8601)", example = "2026-09-01T00:00:00Z")
			@RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
	) {
		LedgerFilter filter = new LedgerFilter(ownerId, provider, model, from, to);
		PageResponse<LedgerEntryResponse> response = usageLedgerService.getEntries(filter, pageable);
		return ResponseEntity.ok(response);
	}

	/**
	 * Retrieves a single ledger entry by its request correlation ID.
	 *
	 * @param requestId request correlation ID
	 * @return HTTP 200 OK with entry details, or HTTP 404 Not Found
	 */
	@Operation(
			summary = "Get transaction details by request ID",
			description = "Retrieves token breakdown, duration, and cost coordinates for a specific correlated request UUID.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Transaction entry retrieved", content = @Content(mediaType = "application/json", schema = @Schema(implementation = LedgerEntryResponse.class))),
			@ApiResponse(responseCode = "404", description = "Transaction not found"),
			@ApiResponse(responseCode = "401", description = "Unauthorized")
	})
	@GetMapping("/entries/{requestId}")
	public ResponseEntity<LedgerEntryResponse> getEntryByRequestId(
			@Parameter(description = "Request correlation UUID", example = "123e4567-e89b-12d3-a456-426614174000")
			@PathVariable("requestId") UUID requestId
	) {
		return usageLedgerService.getEntryByRequestId(requestId)
		                         .map(ResponseEntity::ok)
		                         .orElseGet(() -> ResponseEntity.notFound().build());
	}
}

