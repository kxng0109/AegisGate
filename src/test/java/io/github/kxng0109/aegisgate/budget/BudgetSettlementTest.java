package io.github.kxng0109.aegisgate.budget;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import io.github.kxng0109.aegisgate.contracts.ProviderType;
import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.security.ratelimit.RateLimitUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the settlement facade with stubbed Lua and repository backends: kill-switch fallback, hold
 * failure tolerance, gap persistence (and its failure tolerance), and sweeper expiry reason mapping with
 * re-arm-on-failure.
 */
@DisplayName("BudgetSettlement")
class BudgetSettlementTest {

	private static final BudgetSettlementProperties ENABLED =
			new BudgetSettlementProperties(true, 4096, 3600L, 30L, 500);

	private static SHA256Hash keyHash() {
		return SHA256Hash.fromRawKey("gw-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
	}

	private static BudgetSettlement settlement(BudgetEnforcer enforcer, BudgetGapRepository repository) {
		return new BudgetSettlement(enforcer, repository, ENABLED);
	}

	@Test
	@DisplayName("disabled kill-switch falls back to the prompt-only gate with no hold")
	void disabledFallsBackToPromptOnly() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		when(enforcer.checkBudget(any(), any(), any(), anyString(), anyInt(), any()))
				.thenReturn(new BudgetDecision.Allowed(-1L, 0L));
		BudgetSettlementProperties off =
				new BudgetSettlementProperties(false, 4096, 3600L, 30L, 500);
		BudgetSettlement settlement = new BudgetSettlement(enforcer, mock(BudgetGapRepository.class), off);

		BudgetEnforcer.HoldAuthorization auth = settlement.authorize(
				keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 100, 50, null);

		assertThat(auth.decision()).isInstanceOf(BudgetDecision.Allowed.class);
		assertThat(auth.holdMicros()).isEqualTo(-1L);
	}

	@Test
	@DisplayName("enabled admission delegates to authorizeHold with the ceiled max tokens")
	void enabledAdmitsThroughAuthorizeHold() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		BudgetEnforcer.HoldAuthorization auth =
				new BudgetEnforcer.HoldAuthorization(new BudgetDecision.Allowed(100L, 60L), 9_000L, "2026-09");
		when(enforcer.authorizeHold(any(), any(), any(), anyString(), anyInt(), anyInt(), any()))
				.thenReturn(auth);

		BudgetEnforcer.HoldAuthorization result = settlement(enforcer, mock(BudgetGapRepository.class))
				.authorize(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 100, 50, null);

		assertThat(result.holdMicros()).isEqualTo(9_000L);
		assertThat(result.holdMonth()).isEqualTo("2026-09");
	}

	@Test
	@DisplayName("hold creation failure is tolerated (H stays counted)")
	void holdCreationFailureTolerated() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		when(enforcer.createHold(anyString(), anyString(), anyLong(), anyString(), anyLong(), anyLong()))
				.thenThrow(new RateLimitUnavailableException("down"));
		BudgetEnforcer.HoldAuthorization auth =
				new BudgetEnforcer.HoldAuthorization(new BudgetDecision.Allowed(100L, 60L), 9_000L, "2026-09");

		assertThat(settlement(enforcer, mock(BudgetGapRepository.class))
				.createHold("hold-1", "hex", "owner-1", auth)).isFalse();
	}

	@Test
	@DisplayName("stream settle persists a gap row when the script sets the marker")
	void settlePersistsGapRow() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenReturn(new BudgetEnforcer.SettleOutcome(true, BudgetEnforcer.SETTLE_OK, 4_000L, 10L, true));
		BudgetGapRepository repository = mock(BudgetGapRepository.class);

		BudgetEnforcer.SettleOutcome outcome = settlement(enforcer, repository)
				.settleStream("hold-1", "hex", "owner-1", "2026-09", 4_000L, false);

		assertThat(outcome.gapSet()).isTrue();
		verify(repository).save(any(BudgetGapRecord.class));
	}

	@Test
	@DisplayName("gap persistence failure never breaks settlement")
	void gapPersistenceFailureTolerated() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenReturn(new BudgetEnforcer.SettleOutcome(true, BudgetEnforcer.SETTLE_OK, 4_000L, 10L, true));
		BudgetGapRepository repository = mock(BudgetGapRepository.class);
		when(repository.save(any())).thenThrow(new RuntimeException("db down"));

		BudgetEnforcer.SettleOutcome outcome = settlement(enforcer, repository)
				.settleStream("hold-1", "hex", "owner-1", "2026-09", 4_000L, false);

		assertThat(outcome.gapSet()).isTrue();
	}

	@Test
	@DisplayName("sweeper expiry maps a crashed hold to a CRASH gap row")
	void expiryMapsCrashToGapRow() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		String holdKey = BudgetEnforcer.holdKey("hold-9");
		when(enforcer.readHold(holdKey)).thenReturn(Map.<Object, Object>of(
				"subject", "KEY:hex9", "orig_month", "2026-09", "amount", "10000", "state", "HOLD"));
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenReturn(new BudgetEnforcer.SettleOutcome(false, BudgetEnforcer.SETTLE_EXPIRED, 0L, -1L, true));
		BudgetGapRepository repository = mock(BudgetGapRepository.class);

		assertThat(settlement(enforcer, repository).expireDueHold(holdKey)).isTrue();
		verify(repository).save(any(BudgetGapRecord.class));
	}

	@Test
	@DisplayName("sweeper expiry maps an aborted hold to an ABORTED gap row")
	void expiryMapsAbortToGapRow() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		String holdKey = BudgetEnforcer.holdKey("hold-8");
		when(enforcer.readHold(holdKey)).thenReturn(Map.<Object, Object>of(
				"subject", "KEY:hex8", "orig_month", "2026-09", "amount", "10000", "state", "ABORTED"));
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenReturn(new BudgetEnforcer.SettleOutcome(false, BudgetEnforcer.SETTLE_EXPIRED, 0L, -1L, true));

		settlement(enforcer, mock(BudgetGapRepository.class)).expireDueHold(holdKey);

		verify(enforcer).settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("sweeper re-arms the hold when gap persistence fails")
	void expiryRearmsOnRepoFailure() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		String holdKey = BudgetEnforcer.holdKey("hold-7");
		when(enforcer.readHold(holdKey)).thenReturn(Map.<Object, Object>of(
				"subject", "KEY:hex7", "orig_month", "2026-09", "amount", "10000", "state", "HOLD"));
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenReturn(new BudgetEnforcer.SettleOutcome(false, BudgetEnforcer.SETTLE_EXPIRED, 0L, -1L, true));
		BudgetGapRepository repository = mock(BudgetGapRepository.class);
		when(repository.save(any())).thenThrow(new RuntimeException("db down"));

		assertThat(settlement(enforcer, repository).expireDueHold(holdKey)).isFalse();
		verify(enforcer).rearmHold(anyString(), anyLong());
	}

	@Test
	@DisplayName("due holds delegate to the enforcer scan")
	void dueHoldsDelegateToScan() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		when(enforcer.dueHoldKeys(500, Instant.now().getEpochSecond()))
				.thenReturn(Set.of(BudgetEnforcer.holdKey("hold-1")));

		assertThat(settlement(enforcer, mock(BudgetGapRepository.class)).dueHoldKeys(500)).hasSize(1);
	}

	@Test
	@DisplayName("settle failures propagate for the caller to absorb")
	void settleFailuresPropagate() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenThrow(new RateLimitUnavailableException("down"));

		assertThatThrownBy(() -> settlement(enforcer, mock(BudgetGapRepository.class))
				.settleStream("hold-1", "hex", "owner-1", "2026-09", 4_000L, false))
				.isInstanceOf(RateLimitUnavailableException.class);
		verify(enforcer, never()).rearmHold(anyString(), anyLong());
	}

	@Test
	@DisplayName("null max tokens falls back to the ceiling")
	void nullMaxTokensUsesCeiling() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		BudgetEnforcer.HoldAuthorization auth =
				new BudgetEnforcer.HoldAuthorization(new BudgetDecision.Allowed(100L, 60L), 9_000L, "2026-09");
		when(enforcer.authorizeHold(any(), any(), any(), anyString(), anyInt(), anyInt(), any()))
				.thenReturn(auth);

		settlement(enforcer, mock(BudgetGapRepository.class))
				.authorize(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 100, null, null);

		verify(enforcer).authorizeHold(any(), any(), any(), anyString(),
				eq(BudgetEnforcer.estimatePromptTokens(100)), eq(4096), any());
	}

	@Test
	@DisplayName("non-positive max tokens falls back to the ceiling")
	void nonPositiveMaxTokensUsesCeiling() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		BudgetEnforcer.HoldAuthorization auth =
				new BudgetEnforcer.HoldAuthorization(new BudgetDecision.Allowed(100L, 60L), 9_000L, "2026-09");
		when(enforcer.authorizeHold(any(), any(), any(), anyString(), anyInt(), anyInt(), any()))
				.thenReturn(auth);
		BudgetSettlement settlement = settlement(enforcer, mock(BudgetGapRepository.class));

		settlement.authorize(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 100, 0, null);
		settlement.authorize(keyHash(), "owner-1", ProviderType.OPENAI, "gpt-5.6-luna", 100, 99_999_999, null);

		verify(enforcer, times(2)).authorizeHold(any(), any(), any(), anyString(),
				eq(BudgetEnforcer.estimatePromptTokens(100)), eq(4096), any());
	}

	@Test
	@DisplayName("createHold is a no-op when disabled or without a hold")
	void createHoldNoopWithoutSettlement() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		BudgetSettlementProperties off =
				new BudgetSettlementProperties(false, 4096, 3600L, 30L, 500);
		BudgetSettlement settlement = new BudgetSettlement(enforcer, mock(BudgetGapRepository.class), off);
		BudgetEnforcer.HoldAuthorization auth =
				new BudgetEnforcer.HoldAuthorization(new BudgetDecision.Allowed(100L, 60L), 9_000L, "2026-09");

		assertThat(settlement.createHold("hold-1", "hex", "owner-1", auth)).isTrue();
		assertThat(settlement.createHold("hold-1", "hex", "owner-1",
				new BudgetEnforcer.HoldAuthorization(
						new BudgetDecision.Allowed(100L, 60L), -1L, "2026-09"))).isTrue();
		verify(enforcer, never()).createHold(anyString(), anyString(), anyLong(), anyString(), anyLong(),
				anyLong());
	}

	@Test
	@DisplayName("enabled settlement with a sentinel no-hold skips hold creation")
	void enabledSentinelHoldSkipsCreation() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		BudgetEnforcer.HoldAuthorization auth =
				new BudgetEnforcer.HoldAuthorization(new BudgetDecision.Allowed(100L, 60L), -1L, "2026-09");

		assertThat(settlement(enforcer, mock(BudgetGapRepository.class))
				.createHold("hold-1", "hex", "owner-1", auth)).isTrue();
		verify(enforcer, never()).createHold(anyString(), anyString(), anyLong(), anyString(), anyLong(),
				anyLong());
	}

	@Test
	@DisplayName("createHold encodes a blank owner as an empty team segment")
	void createHoldEncodesBlankOwner() {		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		when(enforcer.createHold(anyString(), anyString(), anyLong(), anyString(), anyLong(), anyLong()))
				.thenReturn(true);
		BudgetEnforcer.HoldAuthorization auth =
				new BudgetEnforcer.HoldAuthorization(new BudgetDecision.Allowed(100L, 60L), 9_000L, "2026-09");

		assertThat(settlement(enforcer, mock(BudgetGapRepository.class))
				.createHold("hold-1", "hex", "  ", auth)).isTrue();
		verify(enforcer).createHold(eq("hold-1"), eq("hex|"), eq(9_000L), eq("2026-09"), anyLong(), anyLong());
	}

	@Test
	@DisplayName("abort settle re-arms the output hold without a gap row yet")
	void abortSettleRearmsWithoutGapRow() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenReturn(new BudgetEnforcer.SettleOutcome(true, BudgetEnforcer.SETTLE_ABORTED, 2_000L, 10L,
						false));
		BudgetGapRepository repository = mock(BudgetGapRepository.class);

		BudgetEnforcer.SettleOutcome outcome = settlement(enforcer, repository)
				.settleStream("hold-1", "hex", "owner-1", "2026-09", 2_000L, true);

		assertThat(outcome.outcome()).isEqualTo(BudgetEnforcer.SETTLE_ABORTED);
		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("straddled abort settle persists a ROLLOVER gap row immediately")
	void straddledAbortPersistsRolloverGap() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenReturn(new BudgetEnforcer.SettleOutcome(true, BudgetEnforcer.SETTLE_ABORTED, 2_000L, 10L,
						true));
		BudgetGapRepository repository = mock(BudgetGapRepository.class);
		org.mockito.ArgumentCaptor<BudgetGapRecord> gap = ArgumentCaptor.forClass(
				BudgetGapRecord.class);

		settlement(enforcer, repository).settleStream("hold-1", "hex", "owner-1", "2000-01", 2_000L, true);

		verify(repository).save(gap.capture());
		assertThat(gap.getValue().getReason()).isEqualTo("ROLLOVER");
	}

	@Test
	@DisplayName("same-month gap settle persists an EXPIRED gap row")
	void sameMonthGapPersistsExpiredRow() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenReturn(new BudgetEnforcer.SettleOutcome(false, BudgetEnforcer.SETTLE_EXPIRED, 0L, -1L,
						true));
		BudgetGapRepository repository = mock(BudgetGapRepository.class);
		org.mockito.ArgumentCaptor<BudgetGapRecord> gap = ArgumentCaptor.forClass(
				BudgetGapRecord.class);
		String month = YearMonth.now(ZoneOffset.UTC).toString();

		settlement(enforcer, repository).settleStream("hold-1", "hex", "owner-1", month, 4_000L, false);

		verify(repository).save(gap.capture());
		assertThat(gap.getValue().getReason()).isEqualTo("EXPIRED");
	}

	@Test
	@DisplayName("expiry without hold fields settles by hold id and records EXPIRED")
	void expiryWithoutFieldsUsesHoldId() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		String holdKey = BudgetEnforcer.holdKey("hold-0");
		when(enforcer.readHold(holdKey)).thenReturn(Map.of());
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenReturn(new BudgetEnforcer.SettleOutcome(false, BudgetEnforcer.SETTLE_EXPIRED, 0L, -1L, true));
		BudgetGapRepository repository = mock(BudgetGapRepository.class);
		org.mockito.ArgumentCaptor<BudgetGapRecord> gap = ArgumentCaptor.forClass(
				BudgetGapRecord.class);

		assertThat(settlement(enforcer, repository).expireDueHold(holdKey)).isTrue();
		verify(repository).save(gap.capture());
		assertThat(gap.getValue().getReason()).isEqualTo("EXPIRED");
		assertThat(gap.getValue().getSubjectId()).isEqualTo("hold-0");
	}

	@Test
	@DisplayName("expiry tolerates subjects without a team separator and malformed amounts")
	void expiryToleratesMalformedFields() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		String holdKey = BudgetEnforcer.holdKey("hold-m");
		java.util.Map<Object, Object> fields = new HashMap<>();
		fields.put("subject", "lonely-subject");
		fields.put("orig_month", "2026-09");
		fields.put("amount", "not-a-number");
		fields.put("state", null);
		when(enforcer.readHold(holdKey)).thenReturn(fields);
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenReturn(new BudgetEnforcer.SettleOutcome(true, BudgetEnforcer.SETTLE_OK, 1L, 1L, false));

		assertThat(settlement(enforcer, mock(BudgetGapRepository.class)).expireDueHold(holdKey)).isTrue();
		verify(enforcer).settle(eq("hold-m"), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("expiry Lua failure re-arms and reports unfinished")
	void expiryLuaFailureRearms() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		String holdKey = BudgetEnforcer.holdKey("hold-f");
		when(enforcer.readHold(holdKey)).thenReturn(Map.<Object, Object>of(
				"subject", "KEY:hexf", "orig_month", "2026-09", "amount", "100", "state", "HOLD"));
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenThrow(new RateLimitUnavailableException("down"));

		assertThat(settlement(enforcer, mock(BudgetGapRepository.class)).expireDueHold(holdKey)).isFalse();
		verify(enforcer).rearmHold(eq(holdKey), anyLong());
	}

	@Test
	@DisplayName("re-arm failure is absorbed when gap persistence already failed")
	void rearmFailureAbsorbed() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		String holdKey = BudgetEnforcer.holdKey("hold-g");
		when(enforcer.readHold(holdKey)).thenReturn(Map.<Object, Object>of(
				"subject", "KEY:hexg", "orig_month", "2026-09", "amount", "100", "state", "HOLD"));
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenReturn(new BudgetEnforcer.SettleOutcome(false, BudgetEnforcer.SETTLE_EXPIRED, 0L, -1L, true));
		BudgetGapRepository repository = mock(BudgetGapRepository.class);
		when(repository.save(any())).thenThrow(new RuntimeException("db down"));
		doThrow(new RateLimitUnavailableException("redis down")).when(enforcer)
				.rearmHold(anyString(), anyLong());

		assertThat(settlement(enforcer, repository).expireDueHold(holdKey)).isFalse();
	}

	@Test
	@DisplayName("expiry without a gap marker needs no persistence")
	void expiryWithoutGapSkipsPersistence() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		String holdKey = BudgetEnforcer.holdKey("hold-n");
		when(enforcer.readHold(holdKey)).thenReturn(Map.<Object, Object>of(
				"subject", "KEY:hexn", "orig_month", "2026-09", "amount", "100", "state", "HOLD"));
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenReturn(new BudgetEnforcer.SettleOutcome(true, BudgetEnforcer.SETTLE_OK, 1L, 1L, false));
		BudgetGapRepository repository = mock(BudgetGapRepository.class);

		assertThat(settlement(enforcer, repository).expireDueHold(holdKey)).isTrue();
		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("createHold encodes a null owner as an empty team segment")
	void createHoldEncodesNullOwner() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		when(enforcer.createHold(anyString(), anyString(), anyLong(), anyString(), anyLong(), anyLong()))
				.thenReturn(true);
		BudgetEnforcer.HoldAuthorization auth =
				new BudgetEnforcer.HoldAuthorization(new BudgetDecision.Allowed(100L, 60L), 9_000L, "2026-09");

		assertThat(settlement(enforcer, mock(BudgetGapRepository.class))
				.createHold("hold-1", "hex", null, auth)).isTrue();
		verify(enforcer).createHold(eq("hold-1"), eq("hex|"), eq(9_000L), eq("2026-09"), anyLong(), anyLong());
	}

	@Test
	@DisplayName("same-month abort with a gap persists an ABORTED row")
	void sameMonthAbortGapPersistsAborted() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenReturn(new BudgetEnforcer.SettleOutcome(true, BudgetEnforcer.SETTLE_ABORTED, 2_000L, 10L,
						true));
		BudgetGapRepository repository = mock(BudgetGapRepository.class);
		ArgumentCaptor<BudgetGapRecord> gap = ArgumentCaptor.forClass(BudgetGapRecord.class);
		String month = YearMonth.now(ZoneOffset.UTC).toString();

		settlement(enforcer, repository).settleStream("hold-1", "hex", "owner-1", month, 2_000L, true);

		verify(repository).save(gap.capture());
		assertThat(gap.getValue().getReason()).isEqualTo("ABORTED");
	}

	@Test
	@DisplayName("expiry with an empty subject falls back to the hold id")
	void expiryEmptySubjectFallsBack() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		String holdKey = BudgetEnforcer.holdKey("hold-e");
		Map<Object, Object> fields = new HashMap<>();
		fields.put("subject", "");
		fields.put("orig_month", "");
		fields.put("amount", "50");
		fields.put("state", "HOLD");
		when(enforcer.readHold(holdKey)).thenReturn(fields);
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenReturn(new BudgetEnforcer.SettleOutcome(false, BudgetEnforcer.SETTLE_EXPIRED, 0L, -1L, true));
		BudgetGapRepository repository = mock(BudgetGapRepository.class);
		ArgumentCaptor<BudgetGapRecord> gap = ArgumentCaptor.forClass(BudgetGapRecord.class);

		assertThat(settlement(enforcer, repository).expireDueHold(holdKey)).isTrue();
		verify(repository).save(gap.capture());
		assertThat(gap.getValue().getSubjectId()).isEqualTo("hold-e");
	}

	@Test
	@DisplayName("expiry with a pipe-separated subject splits the team")
	void expiryPipeSeparatedSubjectSplitsTeam() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		String holdKey = BudgetEnforcer.holdKey("hold-p");
		when(enforcer.readHold(holdKey)).thenReturn(Map.<Object, Object>of(
				"subject", "hexp|owner-1", "orig_month", "2026-09", "amount", "100", "state", "HOLD"));
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenReturn(new BudgetEnforcer.SettleOutcome(false, BudgetEnforcer.SETTLE_EXPIRED, 0L, -1L, true));

		assertThat(settlement(enforcer, mock(BudgetGapRepository.class)).expireDueHold(holdKey)).isTrue();

		verify(enforcer).settle(eq("hold-p"), eq("KEY"), eq("hexp"), eq("owner-1"), eq("hexp"),
				eq("2026-09"), anyString(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("bare hold hash keys resolve to themselves as hold ids")
	void bareHoldKeyResolvesToItself() {
		BudgetEnforcer enforcer = mock(BudgetEnforcer.class);
		when(enforcer.readHold("bare")).thenReturn(Map.of());
		when(enforcer.settle(anyString(), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong()))
				.thenReturn(new BudgetEnforcer.SettleOutcome(false, BudgetEnforcer.SETTLE_EXPIRED, 0L, -1L, true));

		assertThat(settlement(enforcer, mock(BudgetGapRepository.class)).expireDueHold("bare")).isTrue();
		verify(enforcer).settle(eq("bare"), anyString(), anyString(), any(), anyString(), anyString(),
				anyString(), anyLong(), anyLong());
	}
}
