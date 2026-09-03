package io.github.kxng0109.aegisgate.proxy.failover;

import io.github.kxng0109.aegisgate.contracts.*;
import io.github.kxng0109.aegisgate.security.compliance.GeoSovereigntyRouter;
import io.github.kxng0109.aegisgate.security.compliance.Jurisdiction;
import io.github.kxng0109.aegisgate.security.compliance.ResidencyPolicy;
import io.github.kxng0109.aegisgate.security.guardrail.common.GuardrailProperties;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * The failover brain: walks a {@link ModelAlias} provider chain, classifies every attempt, and returns the winning
 * provider's streaming response.
 *
 * <p>Two strategies are supported:</p>
 * <ul>
 *   <li>{@link FailoverStrategy#SEQUENTIAL} tries providers one after another
 *       and stops at the first success.</li>
 *   <li>{@link FailoverStrategy#RACE} fires every provider at once and streams
 *       the first successful response, cancelling the losers.</li>
 * </ul>
 *
 * <p>Classification follows the established failover rules:</p>
 * <ul>
 *   <li>Success is a 200 response with an event stream content type.</li>
 *   <li>Transient failures (429, any 5xx, connection problems, timeouts) fail
 *       over to the next provider and trip the circuit breaker.</li>
 *   <li>Non transient statuses (401, 403, 400 and friends) never fail over;
 *       the request is rejected as is, because retrying elsewhere cannot fix
 *       a client or key problem.</li>
 * </ul>
 *
 * <p>Failover is only possible before the first byte: the orchestrator returns
 * a response whose status and content type are already known, so the controller
 * never starts streaming before a winner exists. In RACE, losing attempts are
 * cancelled at the socket level and their bodies closed, which also stops the
 * upstream provider from generating (and billing) further tokens.</p>
 *
 * <p>Per provider circuit breakers skip known broken providers entirely, and
 * provider URLs are validated once against the SSRF control before the first
 * attempt.</p>
 */
@Slf4j
@Service
public class FailoverOrchestrator {

	private final ProviderClientAdapter clientAdapter;
	private final UpstreamUrlValidator urlValidator;
	private final GatewayProperties gatewayProperties;
	private final CircuitBreakerFactory circuitBreakerFactory;
	private final @Nullable GeoSovereigntyRouter sovereigntyRouter;
	private final @Nullable GuardrailProperties guardrailProperties;
	private final Clock clock;

	private final Map<String, Boolean> validatedProviders = new ConcurrentHashMap<>();
	private final Map<String, Boolean> blockedProviders = new ConcurrentHashMap<>();

	/**
	 * Convenience constructor for existing tests and contexts without compliance components.
	 */
	public FailoverOrchestrator(
			ProviderClientAdapter clientAdapter,
			UpstreamUrlValidator urlValidator,
			GatewayProperties gatewayProperties,
			CircuitBreakerFactory circuitBreakerFactory
	) {
		this(clientAdapter, urlValidator, gatewayProperties, circuitBreakerFactory, null, null);
	}

	/**
	 * Full constructor injecting all dependencies including geo-sovereignty router.
	 *
	 * @param clientAdapter          the provider client adapter
	 * @param urlValidator           validates provider URLs before first use
	 * @param gatewayProperties      the configured providers and aliases
	 * @param circuitBreakerFactory  the shared, Redis backed breaker store
	 * @param sovereigntyRouter      geo-sovereignty router
	 * @param guardrailProperties    guardrail configuration properties
	 */
	@Autowired
	public FailoverOrchestrator(
			ProviderClientAdapter clientAdapter,
			UpstreamUrlValidator urlValidator,
			GatewayProperties gatewayProperties,
			CircuitBreakerFactory circuitBreakerFactory,
			@Nullable GeoSovereigntyRouter sovereigntyRouter,
			@Nullable GuardrailProperties guardrailProperties
	) {
		this.clientAdapter = clientAdapter;
		this.urlValidator = urlValidator;
		this.gatewayProperties = gatewayProperties;
		this.circuitBreakerFactory = circuitBreakerFactory;
		this.sovereigntyRouter = sovereigntyRouter;
		this.guardrailProperties = guardrailProperties;
		this.clock = Clock.systemUTC();
	}

	/**
	 * Executes the request against the chain described by the alias, enforcing data residency if active.
	 *
	 * @param alias              the routing plan for the requested model
	 * @param requestBody        the client request body, OpenAI shaped
	 * @param residencyPolicy    tenant residency policy override
	 * @param originJurisdiction tenant origin jurisdiction
	 * @return a future completing with the winning provider response
	 */
	public CompletableFuture<ProviderResponse> execute(
			ModelAlias alias,
			String requestBody,
			@Nullable ResidencyPolicy residencyPolicy,
			@Nullable Jurisdiction originJurisdiction
	) {
		List<ProviderRef> chain = alias.chain();
		if (sovereigntyRouter != null && guardrailProperties != null && guardrailProperties.isDataResidencyEnabled()) {
			ResidencyPolicy policy = residencyPolicy != null ? residencyPolicy
					: ResidencyPolicy.valueOf(guardrailProperties.getDefaultResidencyPolicy());
			Jurisdiction origin = originJurisdiction != null ? originJurisdiction : Jurisdiction.GLOBAL;
			chain = sovereigntyRouter.filterChain(
					chain,
					gatewayProperties.getProviders(),
					policy,
					origin,
					alias.toString()
			);
		}

		if (alias.strategy() == FailoverStrategy.RACE) {
			return executeRace(chain, requestBody);
		}
		return executeSequential(chain, requestBody);
	}

	/**
	 * Executes the request against the chain described by the alias.
	 *
	 * @param alias       the routing plan for the requested model
	 * @param requestBody the client request body, OpenAI shaped
	 * @return a future completing with the winning provider response, or completing exceptionally with
	 * {@link UpstreamUnavailableException}
	 */
	public CompletableFuture<ProviderResponse> execute(ModelAlias alias, String requestBody) {
		return execute(alias, requestBody, null, null);
	}

	// ---------------------------------------------------------------------
	// SEQUENTIAL
	// ---------------------------------------------------------------------

	private CompletableFuture<ProviderResponse> executeSequential(List<ProviderRef> chain, String requestBody) {
		AttemptContext ctx = new AttemptContext();
		try {
			return CompletableFuture.completedFuture(attemptChain(chain, requestBody, ctx));
		} catch (UpstreamUnavailableException ex) {
			return CompletableFuture.failedFuture(ex);
		}
	}

	private ProviderResponse attemptChain(List<ProviderRef> chain, String requestBody, AttemptContext ctx) {
		for (ProviderRef ref : chain) {
			String name = ref.providerName();
			ProviderConfig config = gatewayProperties.getProviders().get(name);
			if (config == null) {
				ctx.tried(name + " (not configured)");
				continue;
			}
			if (!isUsable(name, config)) {
				ctx.tried(name + " (blocked by validation)");
				continue;
			}
			CircuitBreaker breaker = breakerFor(config.name());
			if (!breaker.tryAcquire()) {
				ctx.tried(name + " (circuit open)");
				continue;
			}
			ctx.callsStarted.incrementAndGet();

			try {
				HttpResponse<Stream<String>> response =
						clientAdapter.sendAsync(config, requestBody, ref.modelOverride()).join();
				AttemptOutcome outcome = classify(response);
				switch (outcome) {
					case SUCCESS -> {
						breaker.recordSuccess();
						return new ProviderResponse(config.name(), response);
					}
					case TRANSIENT -> {
						breaker.recordFailure();
						closeQuietly(response);
						ctx.tried(name);
						ctx.lastError.set(new RuntimeException("upstream returned HTTP " + response.statusCode()));
					}
					case NON_TRANSIENT -> {
						closeQuietly(response);
						throw new UpstreamUnavailableException(
								"provider " + name + " rejected the request with status " + response.statusCode(),
								null, false, false, response.statusCode()
						);
					}
				}
			} catch (UpstreamUnavailableException ex) {
				throw ex;
			} catch (CompletionException ex) {
				Throwable cause = unwrap(ex);
				if (cause instanceof CancellationException) {
					continue;
				}
				breaker.recordFailure();
				ctx.tried(name);
				ctx.lastError.set(cause);
				if (cause instanceof HttpTimeoutException) {
					ctx.timeoutSeen.set(true);
				}
			} catch (RuntimeException ex) {
				breaker.recordFailure();
				ctx.tried(name);
				ctx.lastError.set(ex);
			}
		}

		throw ctx.exhaustedError();
	}

	// ---------------------------------------------------------------------
	// RACE
	// ---------------------------------------------------------------------

	private CompletableFuture<ProviderResponse> executeRace(List<ProviderRef> chain, String requestBody) {
		AttemptContext ctx = new AttemptContext();
		List<ProviderAttempt> attempts = new ArrayList<>();

		for (ProviderRef ref : chain) {
			String name = ref.providerName();
			ProviderConfig config = gatewayProperties.getProviders().get(name);
			if (config == null) {
				ctx.tried(name + " (not configured)");
				continue;
			}
			if (!isUsable(name, config)) {
				ctx.tried(name + " (blocked by validation)");
				continue;
			}
			CircuitBreaker breaker = breakerFor(config.name());
			if (!breaker.tryAcquire()) {
				ctx.tried(name + " (circuit open)");
				continue;
			}
			ctx.callsStarted.incrementAndGet();
			attempts.add(new ProviderAttempt(
					config, breaker,
					clientAdapter.sendAsync(config, requestBody, ref.modelOverride())
			));
		}

		CompletableFuture<ProviderResponse> raceResult = new CompletableFuture<>();
		if (attempts.isEmpty()) {
			raceResult.completeExceptionally(ctx.exhaustedError());
			return raceResult;
		}

		AtomicInteger remaining = new AtomicInteger(attempts.size());
		for (ProviderAttempt attempt : attempts) {
attempt.response().whenComplete((response, error) -> {
			boolean winner;
			try {
					if (error != null) {
						Throwable cause = unwrap(error);
						if (!(cause instanceof CancellationException)) {
							attempt.breaker().recordFailure();
							ctx.tried(attempt.config().name());
							ctx.lastError.set(cause);
							if (cause instanceof HttpTimeoutException) {
								ctx.timeoutSeen.set(true);
							}
						}
						return;
					}

					AttemptOutcome outcome = classify(response);
					switch (outcome) {
						case SUCCESS -> {
							attempt.breaker().recordSuccess();
							winner = raceResult.complete(
									new ProviderResponse(attempt.config().name(), response));
							if (winner) {
								cancelLosers(attempts, attempt);
							} else {
								closeQuietly(response);
							}
						}
						case TRANSIENT -> {
							attempt.breaker().recordFailure();
							ctx.tried(attempt.config().name());
							ctx.lastError.set(new RuntimeException(
									"upstream returned HTTP " + response.statusCode()));
							closeQuietly(response);
						}
						case NON_TRANSIENT -> {
							ctx.nonTransientStatus.set(response.statusCode());
							ctx.tried(attempt.config().name());
							closeQuietly(response);
						}
					}
				} finally {
					if (remaining.decrementAndGet() == 0 && !raceResult.isDone()) {
						raceResult.completeExceptionally(ctx.exhaustedError());
					}
				}
			});
		}
		return raceResult;
	}

	private void cancelLosers(List<ProviderAttempt> attempts, ProviderAttempt winner) {
		for (ProviderAttempt attempt : attempts) {
			if (attempt != winner) {
				attempt.response().cancel(true);
			}
		}
	}

	// ---------------------------------------------------------------------
	// Shared helpers
	// ---------------------------------------------------------------------

	/**
	 * @return the configured providers and aliases
	 */
	public GatewayProperties gatewayProperties() {
		return gatewayProperties;
	}

	/**
	 * Closes every circuit breaker, forgetting all recorded failures. Useful after an operator intervention or in tests
	 * between scenarios.
	 */
	public void resetCircuitBreakers() {
		circuitBreakerFactory.reset();
		validatedProviders.clear();
		blockedProviders.clear();
	}

	private CircuitBreaker breakerFor(String providerName) {
		return circuitBreakerFactory.get(providerName);
	}

	private boolean isUsable(String name, ProviderConfig config) {
		if (blockedProviders.containsKey(name)) {
			return false;
		}
		return validatedProviders.computeIfAbsent(
				name, key -> {
					try {
						urlValidator.validate(config.baseUrl());
						return true;
					} catch (RuntimeException ex) {
						log.warn(
								"Provider {} is not usable because its target was rejected: {}",
								name, ex.getMessage()
						);
						blockedProviders.put(name, true);
						return false;
					}
				}
		);
	}

	private AttemptOutcome classify(HttpResponse<Stream<String>> response) {
		int status = response.statusCode();
		if (status == 200 && isStreaming(response)) {
			return AttemptOutcome.SUCCESS;
		}
		if (status == 429 || status >= 500) {
			return AttemptOutcome.TRANSIENT;
		}
		return AttemptOutcome.NON_TRANSIENT;
	}

	private boolean isStreaming(HttpResponse<Stream<String>> response) {
return response.headers().firstValue("Content-Type")
		               .map(value -> value.toLowerCase(Locale.ROOT)
		                                  .contains("text/event-stream")
			               || value.toLowerCase(Locale.ROOT).contains("application/x-ndjson"))
		               .orElse(false);
	}

	private void closeQuietly(HttpResponse<Stream<String>> response) {
		try {
			response.body().close();
		} catch (RuntimeException ignored) {
			// The stream was already closed or never opened; nothing to release.
		}
	}

	private Throwable unwrap(Throwable error) {
		Throwable current = error;
		while (current instanceof CompletionException && current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	private enum AttemptOutcome {
		SUCCESS, TRANSIENT, NON_TRANSIENT
	}

	private record ProviderAttempt(
			ProviderConfig config,
			CircuitBreaker breaker,
			CompletableFuture<HttpResponse<Stream<String>>> response
	) {
	}

	/**
	 * Tracks everything observed across a chain walk so the final error can name what happened without leaking
	 * secrets.
	 */
	private static final class AttemptContext {

		private final List<String> triedProviders = new ArrayList<>();
		private final AtomicInteger callsStarted = new AtomicInteger();
		private final AtomicReference<Throwable> lastError = new AtomicReference<>();
		private final AtomicBoolean timeoutSeen = new AtomicBoolean();
		private final AtomicInteger nonTransientStatus = new AtomicInteger();

		private void tried(String entry) {
			triedProviders.add(entry);
		}

		private UpstreamUnavailableException exhaustedError() {
			String tried = triedProviders.isEmpty() ? "none" : String.join(", ", triedProviders);
			String message = "all providers failed for this request. tried: " + tried;
			Throwable cause = lastError.get();
			boolean serviceUnavailable = callsStarted.get() == 0;
			boolean timedOut = timeoutSeen.get();
			int status = nonTransientStatus.get();
			if (status != 0) {
				return new UpstreamUnavailableException(message, cause, serviceUnavailable, timedOut, status);
			}
			return new UpstreamUnavailableException(message, cause, serviceUnavailable, timedOut);
		}
	}
}