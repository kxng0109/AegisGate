package io.github.kxng0109.aegisgate.ledger;

import io.github.kxng0109.aegisgate.admin.dto.LedgerFilter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Implementation of {@link UsageLedgerRepositoryCustom} using JPA {@link CriteriaBuilder} for safe, dynamic, and
 * high-performance aggregation and pagination queries.
 */
@Repository
@Transactional(readOnly = true)
public class UsageLedgerRepositoryImpl implements UsageLedgerRepositoryCustom {

	private static final Map<String, String> ALLOWED_SORT_PROPERTIES = Map.ofEntries(
			Map.entry("createdat", "createdAt"),
			Map.entry("created_at", "createdAt"),
			Map.entry("date", "createdAt"),
			Map.entry("cost", "costUsdMicros"),
			Map.entry("costusdmicros", "costUsdMicros"),
			Map.entry("tokens", "totalTokens"),
			Map.entry("totaltokens", "totalTokens"),
			Map.entry("duration", "durationMs"),
			Map.entry("durationms", "durationMs"),
			Map.entry("prompttokens", "promptTokens"),
			Map.entry("completiontokens", "completionTokens"),
			Map.entry("ownerid", "ownerId"),
			Map.entry("provider", "provider"),
			Map.entry("model", "model")
	);

	@PersistenceContext
	private EntityManager em;

	public UsageLedgerRepositoryImpl() {
	}

	UsageLedgerRepositoryImpl(EntityManager em) {
		this.em = em;
	}

	@Override
	public UsageTotals getTotals(LedgerFilter filter) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<UsageTotals> cq = cb.createQuery(UsageTotals.class);
		Root<UsageLedgerEntry> root = cq.from(UsageLedgerEntry.class);

		Predicate[] predicates = buildPredicates(cb, root, filter);
		if (predicates.length > 0) {
			cq.where(predicates);
		}

		cq.select(cb.construct(
				UsageTotals.class,
				cb.count(root),
				cb.coalesce(cb.sumAsLong(root.get("promptTokens")), 0L),
				cb.coalesce(cb.sumAsLong(root.get("completionTokens")), 0L),
				cb.coalesce(cb.sumAsLong(root.get("totalTokens")), 0L),
				cb.coalesce(cb.sum(root.get("costUsdMicros")), 0L),
				cb.coalesce(cb.avg(root.get("durationMs")), 0.0)
		));

		return em.createQuery(cq).getSingleResult();
	}

	@Override
	public List<OwnerUsageRecord> getBreakdownByOwner(LedgerFilter filter) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<OwnerUsageRecord> cq = cb.createQuery(OwnerUsageRecord.class);
		Root<UsageLedgerEntry> root = cq.from(UsageLedgerEntry.class);

		Predicate[] predicates = buildPredicates(cb, root, filter);
		if (predicates.length > 0) {
			cq.where(predicates);
		}

		cq.groupBy(root.get("ownerId"));
		cq.orderBy(cb.desc(cb.sum(root.get("costUsdMicros"))));

		cq.select(cb.construct(
				OwnerUsageRecord.class,
				root.get("ownerId"),
				cb.count(root),
				cb.coalesce(cb.sumAsLong(root.get("promptTokens")), 0L),
				cb.coalesce(cb.sumAsLong(root.get("completionTokens")), 0L),
				cb.coalesce(cb.sumAsLong(root.get("totalTokens")), 0L),
				cb.coalesce(cb.sum(root.get("costUsdMicros")), 0L),
				cb.coalesce(cb.avg(root.get("durationMs")), 0.0)
		));

		return em.createQuery(cq).getResultList();
	}

	@Override
	public List<ModelUsageRecord> getBreakdownByModel(LedgerFilter filter) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<ModelUsageRecord> cq = cb.createQuery(ModelUsageRecord.class);
		Root<UsageLedgerEntry> root = cq.from(UsageLedgerEntry.class);

		Predicate[] predicates = buildPredicates(cb, root, filter);
		if (predicates.length > 0) {
			cq.where(predicates);
		}

		cq.groupBy(root.get("provider"), root.get("model"));
		cq.orderBy(cb.desc(cb.sum(root.get("costUsdMicros"))));

		cq.select(cb.construct(
				ModelUsageRecord.class,
				root.get("provider"),
				root.get("model"),
				cb.count(root),
				cb.coalesce(cb.sumAsLong(root.get("promptTokens")), 0L),
				cb.coalesce(cb.sumAsLong(root.get("completionTokens")), 0L),
				cb.coalesce(cb.sumAsLong(root.get("totalTokens")), 0L),
				cb.coalesce(cb.sum(root.get("costUsdMicros")), 0L),
				cb.coalesce(cb.avg(root.get("durationMs")), 0.0)
		));

		return em.createQuery(cq).getResultList();
	}

	@Override
	public List<ProviderUsageRecord> getBreakdownByProvider(LedgerFilter filter) {
		CriteriaBuilder cb = em.getCriteriaBuilder();
		CriteriaQuery<ProviderUsageRecord> cq = cb.createQuery(ProviderUsageRecord.class);
		Root<UsageLedgerEntry> root = cq.from(UsageLedgerEntry.class);

		Predicate[] predicates = buildPredicates(cb, root, filter);
		if (predicates.length > 0) {
			cq.where(predicates);
		}

		cq.groupBy(root.get("provider"));
		cq.orderBy(cb.desc(cb.sum(root.get("costUsdMicros"))));

		cq.select(cb.construct(
				ProviderUsageRecord.class,
				root.get("provider"),
				cb.count(root),
				cb.coalesce(cb.sumAsLong(root.get("promptTokens")), 0L),
				cb.coalesce(cb.sumAsLong(root.get("completionTokens")), 0L),
				cb.coalesce(cb.sumAsLong(root.get("totalTokens")), 0L),
				cb.coalesce(cb.sum(root.get("costUsdMicros")), 0L),
				cb.coalesce(cb.avg(root.get("durationMs")), 0.0)
		));

		return em.createQuery(cq).getResultList();
	}

	@Override
	public Page<UsageLedgerEntry> findEntries(LedgerFilter filter, Pageable pageable) {
		CriteriaBuilder cb = em.getCriteriaBuilder();

		CriteriaQuery<Long> countCq = cb.createQuery(Long.class);
		Root<UsageLedgerEntry> countRoot = countCq.from(UsageLedgerEntry.class);
		Predicate[] countPredicates = buildPredicates(cb, countRoot, filter);
		if (countPredicates.length > 0) {
			countCq.where(countPredicates);
		}
		countCq.select(cb.count(countRoot));
		long total = em.createQuery(countCq).getSingleResult();

		if (total == 0) {
			return new PageImpl<>(List.of(), pageable, 0);
		}

		CriteriaQuery<UsageLedgerEntry> cq = cb.createQuery(UsageLedgerEntry.class);
		Root<UsageLedgerEntry> root = cq.from(UsageLedgerEntry.class);
		Predicate[] predicates = buildPredicates(cb, root, filter);
		if (predicates.length > 0) {
			cq.where(predicates);
		}

		List<Order> orders = sanitizeSort(cb, root, pageable.getSort());
		cq.orderBy(orders);

		List<UsageLedgerEntry> content = em.createQuery(cq)
		                                   .setFirstResult((int) pageable.getOffset())
		                                   .setMaxResults(pageable.getPageSize())
		                                   .getResultList();

		return new PageImpl<>(content, pageable, total);
	}

	private Predicate[] buildPredicates(CriteriaBuilder cb, Root<UsageLedgerEntry> root, LedgerFilter filter) {
		List<Predicate> predicates = new ArrayList<>();
		if (filter.ownerId() != null) {
			predicates.add(cb.equal(root.get("ownerId"), filter.ownerId()));
		}
		if (filter.provider() != null) {
			predicates.add(cb.equal(root.get("provider"), filter.provider()));
		}
		if (filter.model() != null) {
			predicates.add(cb.equal(root.get("model"), filter.model()));
		}
		if (filter.from() != null) {
			predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
		}
		if (filter.to() != null) {
			predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), filter.to()));
		}
		return predicates.toArray(new Predicate[0]);
	}

	private List<Order> sanitizeSort(CriteriaBuilder cb, Root<UsageLedgerEntry> root, Sort sort) {
		List<Order> orders = new ArrayList<>();
		if (sort.isSorted()) {
			for (Sort.Order order : sort) {
				String property = order.getProperty().toLowerCase().replace("-", "").replace("_", "");
				String entityProperty = ALLOWED_SORT_PROPERTIES.get(property);
				if (entityProperty != null) {
					orders.add(order.isAscending() ? cb.asc(root.get(entityProperty)) : cb.desc(root.get(entityProperty)));
				}
			}
		}
		if (orders.isEmpty()) {
			orders.add(cb.desc(root.get("createdAt")));
		}
		orders.add(cb.desc(root.get("id")));
		return orders;
	}
}
