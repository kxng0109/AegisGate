package io.github.kxng0109.aegisgate.ledger;

import io.github.kxng0109.aegisgate.admin.dto.LedgerFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("UsageLedgerRepositoryImpl")
@SuppressWarnings({"unchecked", "rawtypes", "DataFlowIssue"})
class UsageLedgerRepositoryImplTest {

	private final EntityManager em = mock(EntityManager.class);
	private final CriteriaBuilder cb = mock(CriteriaBuilder.class);
	private final UsageLedgerRepositoryImpl repository = new UsageLedgerRepositoryImpl(em);

	@Test
	@DisplayName("no-arg constructor initializes successfully")
	void defaultConstructorInitializes() {
		UsageLedgerRepositoryImpl noArg = new UsageLedgerRepositoryImpl();
		assertThat(noArg).isNotNull();
	}

	@Test
	@DisplayName("getTotals builds criteria query and returns single result")
	void getTotalsExecutes() {
		CriteriaQuery<UsageTotals> cq = mock(CriteriaQuery.class);
		Root<UsageLedgerEntry> root = mock(Root.class);
		TypedQuery<UsageTotals> typedQuery = mock(TypedQuery.class);
		UsageTotals expected = new UsageTotals(10L, 1000L, 500L, 1500L, 14_000L, 120.0);

		when(em.getCriteriaBuilder()).thenReturn(cb);
		when(cb.createQuery(UsageTotals.class)).thenReturn(cq);
		when(cq.from(UsageLedgerEntry.class)).thenReturn(root);
		when(em.createQuery(cq)).thenReturn(typedQuery);
		when(typedQuery.getSingleResult()).thenReturn(expected);

		Instant now = Instant.now();
		LedgerFilter filter = new LedgerFilter("owner-1", "openai", "gpt-4o", now.minusSeconds(60), now);
		UsageTotals actual = repository.getTotals(filter);

		assertThat(actual).isEqualTo(expected);
		verify(cq).where(any(Predicate[].class));

		// Without filter predicates
		when(typedQuery.getSingleResult()).thenReturn(expected);
		UsageTotals noFilterActual = repository.getTotals(new LedgerFilter(null, null, null, null, null));
		assertThat(noFilterActual).isEqualTo(expected);
	}

	@Test
	@DisplayName("getBreakdownByOwner builds grouped criteria query and returns list")
	void getBreakdownByOwnerExecutes() {
		CriteriaQuery<OwnerUsageRecord> cq = mock(CriteriaQuery.class);
		Root<UsageLedgerEntry> root = mock(Root.class);
		TypedQuery<OwnerUsageRecord> typedQuery = mock(TypedQuery.class);
		OwnerUsageRecord record = new OwnerUsageRecord("owner-1", 10L, 1000L, 500L, 1500L, 14_000L, 120.0);

		when(em.getCriteriaBuilder()).thenReturn(cb);
		when(cb.createQuery(OwnerUsageRecord.class)).thenReturn(cq);
		when(cq.from(UsageLedgerEntry.class)).thenReturn(root);
		when(em.createQuery(cq)).thenReturn(typedQuery);
		when(typedQuery.getResultList()).thenReturn(List.of(record));

		// Without filter
		LedgerFilter filter = new LedgerFilter(null, null, null, null, null);
		List<OwnerUsageRecord> actual = repository.getBreakdownByOwner(filter);
		assertThat(actual).containsExactly(record);

		// With active filter
		LedgerFilter activeFilter = new LedgerFilter(
				"owner-1",
				"openai",
				"gpt-4o",
				Instant.now().minusSeconds(10),
				Instant.now()
		);
		List<OwnerUsageRecord> activeActual = repository.getBreakdownByOwner(activeFilter);
		assertThat(activeActual).containsExactly(record);
		verify(cq).where(any(Predicate[].class));
	}

	@Test
	@DisplayName("getBreakdownByModel builds grouped criteria query and returns list")
	void getBreakdownByModelExecutes() {
		CriteriaQuery<ModelUsageRecord> cq = mock(CriteriaQuery.class);
		Root<UsageLedgerEntry> root = mock(Root.class);
		TypedQuery<ModelUsageRecord> typedQuery = mock(TypedQuery.class);
		ModelUsageRecord record = new ModelUsageRecord("openai", "gpt-4o", 10L, 1000L, 500L, 1500L, 14_000L, 120.0);

		when(em.getCriteriaBuilder()).thenReturn(cb);
		when(cb.createQuery(ModelUsageRecord.class)).thenReturn(cq);
		when(cq.from(UsageLedgerEntry.class)).thenReturn(root);
		when(em.createQuery(cq)).thenReturn(typedQuery);
		when(typedQuery.getResultList()).thenReturn(List.of(record));

		// Without filter
		LedgerFilter filter = new LedgerFilter(null, null, null, null, null);
		List<ModelUsageRecord> actual = repository.getBreakdownByModel(filter);
		assertThat(actual).containsExactly(record);

		// With active filter
		LedgerFilter activeFilter = new LedgerFilter(
				"owner-1",
				"openai",
				"gpt-4o",
				Instant.now().minusSeconds(10),
				Instant.now()
		);
		List<ModelUsageRecord> activeActual = repository.getBreakdownByModel(activeFilter);
		assertThat(activeActual).containsExactly(record);
		verify(cq).where(any(Predicate[].class));
	}

	@Test
	@DisplayName("getBreakdownByProvider builds grouped criteria query and returns list")
	void getBreakdownByProviderExecutes() {
		CriteriaQuery<ProviderUsageRecord> cq = mock(CriteriaQuery.class);
		Root<UsageLedgerEntry> root = mock(Root.class);
		TypedQuery<ProviderUsageRecord> typedQuery = mock(TypedQuery.class);
		ProviderUsageRecord record = new ProviderUsageRecord("openai", 10L, 1000L, 500L, 1500L, 14_000L, 120.0);

		when(em.getCriteriaBuilder()).thenReturn(cb);
		when(cb.createQuery(ProviderUsageRecord.class)).thenReturn(cq);
		when(cq.from(UsageLedgerEntry.class)).thenReturn(root);
		when(em.createQuery(cq)).thenReturn(typedQuery);
		when(typedQuery.getResultList()).thenReturn(List.of(record));

		// Without filter
		LedgerFilter filter = new LedgerFilter(null, null, null, null, null);
		List<ProviderUsageRecord> actual = repository.getBreakdownByProvider(filter);
		assertThat(actual).containsExactly(record);

		// With active filter
		LedgerFilter activeFilter = new LedgerFilter(
				"owner-1",
				"openai",
				"gpt-4o",
				Instant.now().minusSeconds(10),
				Instant.now()
		);
		List<ProviderUsageRecord> activeActual = repository.getBreakdownByProvider(activeFilter);
		assertThat(activeActual).containsExactly(record);
		verify(cq).where(any(Predicate[].class));
	}

	@Test
	@DisplayName("findEntries returns empty page when count is zero")
	void findEntriesZeroCount() {
		CriteriaQuery<Long> countCq = mock(CriteriaQuery.class);
		Root<UsageLedgerEntry> countRoot = mock(Root.class);
		TypedQuery<Long> countQuery = mock(TypedQuery.class);

		when(em.getCriteriaBuilder()).thenReturn(cb);
		when(cb.createQuery(Long.class)).thenReturn(countCq);
		when(countCq.from(UsageLedgerEntry.class)).thenReturn(countRoot);
		when(em.createQuery(countCq)).thenReturn(countQuery);
		when(countQuery.getSingleResult()).thenReturn(0L);

		LedgerFilter filter = new LedgerFilter(null, null, null, null, null);
		Page<UsageLedgerEntry> page = repository.findEntries(filter, PageRequest.of(0, 20));

		assertThat(page.getContent()).isEmpty();
		assertThat(page.getTotalElements()).isZero();
	}

	@Test
	@DisplayName("findEntries executes count and content queries with sort allowlist and fallback")
	void findEntriesWithContentAndSort() {
		CriteriaQuery<Long> countCq = mock(CriteriaQuery.class);
		Root<UsageLedgerEntry> countRoot = mock(Root.class);
		TypedQuery<Long> countQuery = mock(TypedQuery.class);

		CriteriaQuery<UsageLedgerEntry> cq = mock(CriteriaQuery.class);
		Root<UsageLedgerEntry> root = mock(Root.class);
		TypedQuery<UsageLedgerEntry> dataQuery = mock(TypedQuery.class);

		UsageLedgerEntry entry = new UsageLedgerEntry(
				java.util.UUID.randomUUID(), "owner-1", "openai", "gpt-4o",
				100, 50, 150, 1400L, 200L, Instant.now()
		);

		when(em.getCriteriaBuilder()).thenReturn(cb);
		when(cb.createQuery(Long.class)).thenReturn(countCq);
		when(countCq.from(UsageLedgerEntry.class)).thenReturn(countRoot);
		when(em.createQuery(countCq)).thenReturn(countQuery);
		when(countQuery.getSingleResult()).thenReturn(1L);

		when(cb.createQuery(UsageLedgerEntry.class)).thenReturn(cq);
		when(cq.from(UsageLedgerEntry.class)).thenReturn(root);
		when(em.createQuery(cq)).thenReturn(dataQuery);
		when(dataQuery.setFirstResult(anyInt())).thenReturn(dataQuery);
		when(dataQuery.setMaxResults(anyInt())).thenReturn(dataQuery);
		when(dataQuery.getResultList()).thenReturn(List.of(entry));

		LedgerFilter filter = new LedgerFilter("owner-1", null, null, null, null);
		Sort sort = Sort.by(
				Sort.Order.asc("cost"),
				Sort.Order.desc("tokens"),
				Sort.Order.asc("duration"),
				Sort.Order.desc("promptTokens"),
				Sort.Order.asc("completionTokens"),
				Sort.Order.desc("ownerId"),
				Sort.Order.asc("provider"),
				Sort.Order.desc("model"),
				Sort.Order.asc("created_at"),
				Sort.Order.desc("date"),
				Sort.Order.asc("unknownField")
		);

		Page<UsageLedgerEntry> page = repository.findEntries(filter, PageRequest.of(0, 20, sort));

		assertThat(page.getContent()).hasSize(1);
		assertThat(page.getTotalElements()).isEqualTo(1L);
		verify(dataQuery).setFirstResult(0);
		verify(dataQuery).setMaxResults(20);

		// Unsorted query
		Page<UsageLedgerEntry> unsortedPage = repository.findEntries(filter, PageRequest.of(0, 20, Sort.unsorted()));
		assertThat(unsortedPage.getContent()).hasSize(1);
	}
}
