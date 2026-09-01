package io.github.kxng0109.aegisgate.admin;

import io.github.kxng0109.aegisgate.admin.dto.LedgerEntryResponse;
import io.github.kxng0109.aegisgate.admin.dto.LedgerFilter;
import io.github.kxng0109.aegisgate.admin.dto.LedgerSummaryResponse;
import io.github.kxng0109.aegisgate.admin.dto.PageResponse;
import io.github.kxng0109.aegisgate.ledger.UsageLedgerService;
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
	@GetMapping("/summary")
	public ResponseEntity<LedgerSummaryResponse> getSummary(
			@RequestParam(value = "ownerId", required = false) String ownerId,
			@RequestParam(value = "provider", required = false) String provider,
			@RequestParam(value = "model", required = false) String model,
			@RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
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
	@GetMapping("/entries")
	public ResponseEntity<PageResponse<LedgerEntryResponse>> getEntries(
			@RequestParam(value = "ownerId", required = false) String ownerId,
			@RequestParam(value = "provider", required = false) String provider,
			@RequestParam(value = "model", required = false) String model,
			@RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
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
	@GetMapping("/entries/{requestId}")
	public ResponseEntity<LedgerEntryResponse> getEntryByRequestId(@PathVariable("requestId") UUID requestId) {
		return usageLedgerService.getEntryByRequestId(requestId)
		                         .map(ResponseEntity::ok)
		                         .orElseGet(() -> ResponseEntity.notFound().build());
	}
}
