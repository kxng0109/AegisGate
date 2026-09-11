package io.github.kxng0109.aegisgate.admin;

import io.github.kxng0109.aegisgate.admin.dto.BudgetBalanceResponse;
import io.github.kxng0109.aegisgate.admin.dto.CreateBudgetRequest;
import io.github.kxng0109.aegisgate.admin.dto.UpdateBudgetRequest;
import io.github.kxng0109.aegisgate.budget.BudgetLimit;
import io.github.kxng0109.aegisgate.budget.BudgetService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for budget administration endpoints with a mocked service.
 */
@DisplayName("AdminBudgetController")
class AdminBudgetControllerTest {

	private final BudgetService budgetService = mock(BudgetService.class);

	private final AdminBudgetController controller = new AdminBudgetController(budgetService);

	@Test
	@DisplayName("create returns 201 with the created budget")
	void createReturns201() {
		BudgetLimit limit = new BudgetLimit("TEAM", "tenant-a", 100L, 200L, null);
		when(budgetService.create("TEAM", "tenant-a", 100L, 200L, null)).thenReturn(limit);

		var response = controller.createBudget(new CreateBudgetRequest("TEAM", "tenant-a", 100L, 200L, null));

		assertEquals(HttpStatus.CREATED, response.getStatusCode());
		assertEquals("TEAM", response.getBody().level());
		verify(budgetService).create("TEAM", "tenant-a", 100L, 200L, null);
	}

	@Test
	@DisplayName("balance returns 200 with the live snapshot")
	void balanceReturns200() {
		BudgetService.BalanceView view = new BudgetService.BalanceView("TEAM", "tenant-a", 100L, 40L, 200L, 10L);
		when(budgetService.balance("TEAM", "tenant-a")).thenReturn(view);

		var response = controller.balance("TEAM", "tenant-a");

		assertEquals(HttpStatus.OK, response.getStatusCode());
		BudgetBalanceResponse body = response.getBody();
		assertEquals(40L, body.minuteSpentMicros());
		assertEquals(10L, body.monthSpentMicros());
	}

	@Test
	@DisplayName("update returns 200 with the replaced budget")
	void updateReturns200() {
		UUID id = UUID.randomUUID();
		BudgetLimit limit = new BudgetLimit("TEAM", "tenant-a", 300L, 400L, null);
		when(budgetService.update(id, 300L, 400L, null)).thenReturn(limit);

		var response = controller.updateBudget(id, new UpdateBudgetRequest(300L, 400L, null));

		assertEquals(HttpStatus.OK, response.getStatusCode());
		assertEquals(300L, response.getBody().minuteMicros());
	}

	@Test
	@DisplayName("delete returns 204")
	void deleteReturns204() {
		UUID id = UUID.randomUUID();

		var response = controller.deleteBudget(id);

		assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
		verify(budgetService).delete(id);
	}

	@Test
	@DisplayName("service errors propagate with their status")
	void serviceErrorsPropagate() {
		UUID id = UUID.randomUUID();
		when(budgetService.update(id, 1L, 2L, null))
				.thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "budget not found"));

		assertThrows(ResponseStatusException.class, () ->
				controller.updateBudget(id, new UpdateBudgetRequest(1L, 2L, null)));
	}
}
