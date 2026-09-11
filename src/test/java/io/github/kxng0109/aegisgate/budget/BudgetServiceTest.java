package io.github.kxng0109.aegisgate.budget;

import io.github.kxng0109.aegisgate.contracts.BootstrapKey;
import io.github.kxng0109.aegisgate.contracts.GatewayProperties;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.security.SsrfValidator;
import io.github.kxng0109.aegisgate.security.SsrfViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for budget administration: validation, CRUD wiring, audit trail, and backfill.
 */
@DisplayName("BudgetService")
class BudgetServiceTest {

	private final BudgetLimitRepository limits = mock(BudgetLimitRepository.class);

	private final BudgetAuditRepository audits = mock(BudgetAuditRepository.class);

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final HashOperations<String, String, String> hashOps = mock(HashOperations.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

	private final BudgetEnforcer enforcer = mock(BudgetEnforcer.class);

	private final GatewayProperties gatewayProperties = mock(GatewayProperties.class);

	private final SsrfValidator ssrfValidator = mock(SsrfValidator.class);

	private final BudgetService service = new BudgetService(
			limits, audits, redisTemplate, enforcer, gatewayProperties, ssrfValidator);

	@Test
	@DisplayName("create persists, mirrors to Redis, audits, and invalidates")
	void createHappyPath() {
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOps);
		when(limits.findByLevelAndSubjectId("TEAM", "tenant-a")).thenReturn(Optional.empty());
		when(limits.saveAndFlush(any(BudgetLimit.class))).thenAnswer(call -> call.getArgument(0));

		BudgetLimit created = service.create("TEAM", "tenant-a", 100L, 200L, null);

		assertEquals("TEAM", created.getLevel());
		verify(hashOps).putAll(anyString(), any());
		verify(enforcer).invalidateAll();
		verify(audits).save(any(BudgetAuditRecord.class));
	}

	@Test
	@DisplayName("duplicate create is a 409, bad inputs are 400")
	void createValidation() {
		when(limits.findByLevelAndSubjectId("TEAM", "tenant-a"))
				.thenReturn(Optional.of(new BudgetLimit("TEAM", "tenant-a", 1L, 1L, null)));

		assertThrows(ResponseStatusException.class, () -> service.create("TEAM", "tenant-a", 1L, 1L, null));
		assertThrows(ResponseStatusException.class, () -> service.create("NOPE", "tenant-a", 1L, 1L, null));
		assertThrows(ResponseStatusException.class, () -> service.create("TEAM", "Bad Name!", 1L, 1L, null));
		assertThrows(ResponseStatusException.class, () -> service.create("KEY", "not-hex", 1L, 1L, null));
		assertThrows(ResponseStatusException.class, () -> service.create("TEAM", "tenant-a", -1L, 1L, null));
	}

	@Test
	@DisplayName("malicious webhook URLs are rejected before storage")
	void webhookValidated() {
		org.mockito.Mockito.doThrow(new SsrfViolationException("blocked"))
		                   .when(ssrfValidator).validate(any());

		assertThrows(
				ResponseStatusException.class, () ->
						service.create("TEAM", "tenant-a", 1L, 1L, "http://169.254.169.254/")
		);
	}

	@Test
	@DisplayName("update replaces caps and audits before/after")
	void updateHappyPath() {
		BudgetLimit existing = new BudgetLimit("TEAM", "tenant-a", 100L, 200L, null);
		when(limits.findById(existing.getId())).thenReturn(Optional.of(existing));
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOps);
		when(limits.saveAndFlush(any(BudgetLimit.class))).thenAnswer(call -> call.getArgument(0));

		BudgetLimit updated = service.update(existing.getId(), 300L, 400L, null);

		assertEquals(300L, updated.getMinuteMicros());
		verify(audits).save(any(BudgetAuditRecord.class));
	}

	@Test
	@DisplayName("update of a missing budget is a 404")
	void updateMissingIs404() {
		UUID id = UUID.randomUUID();
		when(limits.findById(id)).thenReturn(Optional.empty());

		assertThrows(ResponseStatusException.class, () -> service.update(id, 1L, 1L, null));
	}

	@Test
	@DisplayName("delete snapshots spend, drops config, and audits")
	void deleteSnapshotsSpend() {
		BudgetLimit existing = new BudgetLimit("KEY", "ab".repeat(32), 100L, 200L, null);
		when(limits.findById(existing.getId())).thenReturn(Optional.of(existing));
		when(redisTemplate.<String, String>opsForValue()).thenReturn(valueOps);
		when(valueOps.get(anyString())).thenReturn("40");

		service.delete(existing.getId());

		verify(limits).delete(existing);
		verify(redisTemplate).delete(anyString());
		verify(audits).save(any(BudgetAuditRecord.class));
	}

	@Test
	@DisplayName("balance reads limits plus live counters, zeros when absent")
	void balanceReadsCounters() {
		when(limits.findByLevelAndSubjectId("TEAM", "tenant-a"))
				.thenReturn(Optional.of(new BudgetLimit("TEAM", "tenant-a", 100L, 200L, null)));
		when(redisTemplate.<String, String>opsForValue()).thenReturn(valueOps);
		when(valueOps.get(anyString())).thenReturn("40", "10");

		BudgetService.BalanceView balance = service.balance("TEAM", "tenant-a");

		assertEquals(100L, balance.minuteLimitMicros());
		assertEquals(40L, balance.minuteSpentMicros());
		assertEquals(200L, balance.monthLimitMicros());
		assertEquals(10L, balance.monthSpentMicros());
	}

	@Test
	@DisplayName("backfill mirrors every durable limit into Redis")
	void backfillMirrorsAll() {
		BootstrapKey key = new BootstrapKey(
				"owner-1", "k", "gw-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
				1, 1, Set.of(), Set.of()
		);
		when(gatewayProperties.getBootstrapKeys()).thenReturn(List.of(key));
		when(limits.findAll()).thenReturn(List.of(
				new BudgetLimit("TEAM", "owner-1", 100L, 200L, null),
				new BudgetLimit("ORG", "global", 300L, 400L, null)
		));
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOps);

		service.backfill();

		verify(hashOps, times(2)).putAll(anyString(), any());
		verify(enforcer, atLeastOnce()).markBudgeted(anyString());
	}

	@Test
	@DisplayName("null subject is a 400")
	@SuppressWarnings("DataFlowIssue")
	void nullSubjectIs400() {
		assertThrows(ResponseStatusException.class, () -> service.create("TEAM", null, 1L, 1L, null));
	}

	@Test
	@DisplayName("backfill marks key-level presence without touching bootstrap keys")
	void backfillMarksKeyPresence() {
		String hex = "ab".repeat(32);
		when(limits.findAll()).thenReturn(List.of(new BudgetLimit("KEY", hex, 100L, 200L, null)));
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOps);

		service.backfill();

		verify(enforcer).markBudgeted(hex);
		verify(gatewayProperties, never()).getBootstrapKeys();
	}

	@Test
	@DisplayName("backfill skips bootstrap keys with blank secrets")
	void backfillSkipsBlankSecrets() {
		BootstrapKey valid = new BootstrapKey(
				"owner-9", "ok", "gw-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
				1, 1, Set.of(), Set.of()
		);
		BootstrapKey nullSecret = new BootstrapKey(
				"owner-9", "null-secret", null,
				1, 1, Set.of(), Set.of()
		);
		BootstrapKey blankSecret = new BootstrapKey(
				"owner-9", "blank-secret", "  ",
				1, 1, Set.of(), Set.of()
		);
		when(gatewayProperties.getBootstrapKeys()).thenReturn(List.of(valid, nullSecret, blankSecret));
		when(limits.findAll()).thenReturn(List.of(new BudgetLimit("ORG", "global", 300L, 400L, null)));
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOps);

		service.backfill();

		verify(enforcer, times(1)).markBudgeted(anyString());
		verify(enforcer).markBudgeted(SHA256Hash.fromRawKey("gw-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb").hex());
	}

	@Test
	@DisplayName("backfill marks only keys owned by the budgeted team")
	void backfillMarksOwningTeamOnly() {
		BootstrapKey owned = new BootstrapKey(
				"tenant-x", "owned", "gw-cccccccccccccccccccccccccccccccc",
				1, 1, Set.of(), Set.of()
		);
		BootstrapKey ownedNullSecret = new BootstrapKey(
				"tenant-x", "owned-null", null,
				1, 1, Set.of(), Set.of()
		);
		BootstrapKey foreign = new BootstrapKey(
				"tenant-y", "foreign", "gw-dddddddddddddddddddddddddddddddd",
				1, 1, Set.of(), Set.of()
		);
		when(gatewayProperties.getBootstrapKeys()).thenReturn(List.of(owned, ownedNullSecret, foreign));
		when(limits.findAll()).thenReturn(List.of(new BudgetLimit("TEAM", "tenant-x", 100L, 200L, null)));
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOps);

		service.backfill();

		verify(enforcer, times(1)).markBudgeted(anyString());
		verify(enforcer).markBudgeted(SHA256Hash.fromRawKey("gw-cccccccccccccccccccccccccccccccc").hex());
	}

	@Test
	@DisplayName("balance treats missing counters as zero spend")
	void balanceTreatsMissingCountersAsZero() {
		when(limits.findByLevelAndSubjectId("TEAM", "tenant-a"))
				.thenReturn(Optional.of(new BudgetLimit("TEAM", "tenant-a", 100L, 200L, null)));
		when(redisTemplate.<String, String>opsForValue()).thenReturn(valueOps);
		when(valueOps.get(anyString())).thenReturn(null);

		BudgetService.BalanceView balance = service.balance("TEAM", "tenant-a");

		assertEquals(0L, balance.minuteSpentMicros());
		assertEquals(0L, balance.monthSpentMicros());
	}

	@Test
	@DisplayName("blank webhook is treated as absent")
	void blankWebhookTreatedAsAbsent() {
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOps);
		when(limits.findByLevelAndSubjectId("TEAM", "tenant-a")).thenReturn(Optional.empty());
		when(limits.saveAndFlush(any(BudgetLimit.class))).thenAnswer(call -> call.getArgument(0));

		BudgetLimit created = service.create("TEAM", "tenant-a", 100L, 200L, "  ");

		assertEquals("TEAM", created.getLevel());
		assertNull(created.getWebhookUrl());
		verify(hashOps).putAll(anyString(), any());
	}
}

