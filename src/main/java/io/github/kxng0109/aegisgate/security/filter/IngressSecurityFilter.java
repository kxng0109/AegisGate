package io.github.kxng0109.aegisgate.security.filter;

import io.github.kxng0109.aegisgate.security.guardrail.common.GuardrailMode;
import io.github.kxng0109.aegisgate.security.guardrail.common.GuardrailProperties;
import io.github.kxng0109.aegisgate.security.guardrail.injection.InjectionScanResult;
import io.github.kxng0109.aegisgate.security.guardrail.injection.PromptInjectionException;
import io.github.kxng0109.aegisgate.security.guardrail.injection.PromptInjectionScanner;
import io.github.kxng0109.aegisgate.security.guardrail.pii.EphemeralPiiVault;
import io.github.kxng0109.aegisgate.security.guardrail.pii.PiiAnonymizer;
import io.github.kxng0109.aegisgate.security.guardrail.secret.IngressSecretScanner;
import io.github.kxng0109.aegisgate.security.guardrail.secret.SecretLeakageException;
import io.github.kxng0109.aegisgate.security.guardrail.secret.SecretScanResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Ingress servlet filter executing real-time secret leakage scanning, prompt injection defense, and PII anonymization
 * prior to upstream model execution.
 *
 * <p>Registered at order {@value #ORDER}, running directly after {@link KeyAuthFilter}.</p>
 */
public class IngressSecurityFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(IngressSecurityFilter.class);

	public static final int ORDER = 2;
	public static final String TARGET_PATH = "/v1/chat/completions";
	public static final String PII_VAULT_ATTRIBUTE = "aegis.piiVault";

	private final IngressSecretScanner secretScanner;
	private final PromptInjectionScanner injectionScanner;
	private final PiiAnonymizer piiAnonymizer;
	private final GuardrailProperties properties;
	private final ObjectMapper objectMapper;

	public IngressSecurityFilter(
			IngressSecretScanner secretScanner,
			PromptInjectionScanner injectionScanner,
			PiiAnonymizer piiAnonymizer,
			GuardrailProperties properties,
			ObjectMapper objectMapper
	) {
		this.secretScanner = secretScanner;
		this.injectionScanner = injectionScanner;
		this.piiAnonymizer = piiAnonymizer;
		this.properties = properties != null ? properties : new GuardrailProperties();
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {
		if (!HttpMethod.POST.matches(request.getMethod()) || !TARGET_PATH.equals(request.getRequestURI())) {
			filterChain.doFilter(request, response);
			return;
		}

		CachedBodyHttpServletRequest wrapper =
				WebUtils.getNativeRequest(request, CachedBodyHttpServletRequest.class);
		byte[] bodyBytes = wrapper != null ? wrapper.getContentAsByteArray() : new byte[0];
		if (bodyBytes.length == 0) {
			filterChain.doFilter(request, response);
			return;
		}

		String textPayload = new String(bodyBytes, StandardCharsets.UTF_8);

		// 1. Ingress Secret & Credential Leakage Scanner
		if (properties.isSecretScanningEnabled()) {
			SecretScanResult secretResult = secretScanner.scan(bodyBytes, textPayload);
			if (secretResult.detected()) {
				if (properties.getMode() == GuardrailMode.ENFORCE) {
					log.warn(
							"Ingress secret leakage blocked: rule={}, path={}",
							secretResult.ruleId(),
							secretResult.jsonPath()
					);
					writeProblemDetail(
							response,
							new SecretLeakageException(secretResult).toProblemDetail(request.getRequestURI())
					);
					return;
				} else {
					log.warn("Ingress secret leakage detected (AUDIT_ONLY): rule={}", secretResult.ruleId());
					request.setAttribute("aegis.guardrail.secretLeakage", secretResult);
				}
			}
		}

		// 2. Prompt Injection & Jailbreak Defense
		if (properties.isPromptInjectionDefenseEnabled()) {
			InjectionScanResult injectionResult = injectionScanner.scan(textPayload);
			if (injectionResult.detected()) {
				if (properties.getMode() == GuardrailMode.ENFORCE) {
					log.warn(
							"Prompt injection blocked: category={}, matched={}",
							injectionResult.category(),
							injectionResult.matchedPattern()
					);
					writeProblemDetail(
							response,
							new PromptInjectionException(injectionResult).toProblemDetail(request.getRequestURI())
					);
					return;
				} else {
					log.warn("Prompt injection detected (AUDIT_ONLY): category={}", injectionResult.category());
					request.setAttribute("aegis.guardrail.promptInjection", injectionResult);
				}
			}
		}

		// 3. PII Anonymization & Ephemeral Vault
		if (properties.isPiiAnonymizationEnabled()) {
			EphemeralPiiVault vault = new EphemeralPiiVault();
			String anonymized = piiAnonymizer.anonymize(textPayload, vault);

			if (!vault.isEmpty()) {
				request.setAttribute(PII_VAULT_ATTRIBUTE, vault);
				byte[] anonymizedBytes = anonymized.getBytes(StandardCharsets.UTF_8);
				HttpServletRequest anonymizedRequest = new AnonymizedBodyHttpServletRequest(request, anonymizedBytes);
				filterChain.doFilter(anonymizedRequest, response);
				return;
			} else {
				vault.close();
			}
		}

		filterChain.doFilter(request, response);
	}

	private void writeProblemDetail(HttpServletResponse response, ProblemDetail problem) throws IOException {
		response.setStatus(problem.getStatus());
		response.setContentType("application/problem+json");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getOutputStream(), problem);
	}
}
