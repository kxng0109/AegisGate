package io.github.kxng0109.aegisgate.admin;

import io.github.kxng0109.aegisgate.admin.dto.ProblemDetailResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Enforces constant-time authentication on administrative endpoints under {@code /v1/admin/**}.
 *
 * <p>Requests must provide the configured master key via {@code Authorization: Bearer <master-key>} or
 * {@code X-Admin-Key: <master-key>}. If the master key is not configured, the filter fails closed and returns HTTP 403
 * Forbidden with an RFC 9457 Problem Details payload.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class AdminAuthFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";
	private static final String HEADER_AUTHORIZATION = "Authorization";
	private static final String HEADER_X_ADMIN_KEY = "X-Admin-Key";

	private final String masterKey;
	private final ObjectMapper objectMapper;

	@Override
	protected void doFilterInternal(
			@NonNull HttpServletRequest request,
			@NonNull HttpServletResponse response,
			@NonNull FilterChain filterChain
	) throws ServletException, IOException {

		if (masterKey == null || masterKey.isBlank()) {
			log.warn(
					"Rejected access to admin endpoint {}: master admin key is not configured",
					request.getRequestURI()
			);
			writeProblem(
					response,
					HttpServletResponse.SC_FORBIDDEN,
					"Admin Interface Disabled",
					"Master admin key is not configured. Administrative access is disabled.",
					request.getRequestURI()
			);
			return;
		}

		String token = extractToken(request);
		if (token == null || token.isBlank()) {
			writeProblem(
					response,
					HttpServletResponse.SC_UNAUTHORIZED,
					"Unauthorized",
					"Missing or empty administrative credentials. Provide Authorization: Bearer <key> or X-Admin-Key.",
					request.getRequestURI()
			);
			return;
		}

		byte[] expectedBytes = masterKey.getBytes(StandardCharsets.UTF_8);
		byte[] actualBytes = token.getBytes(StandardCharsets.UTF_8);

		if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
			log.warn("Invalid admin authentication attempt on {}", request.getRequestURI());
			writeProblem(
					response,
					HttpServletResponse.SC_UNAUTHORIZED,
					"Unauthorized",
					"Invalid master admin key.",
					request.getRequestURI()
			);
			return;
		}

		filterChain.doFilter(request, response);
	}

	private String extractToken(HttpServletRequest request) {
		String authHeader = request.getHeader(HEADER_AUTHORIZATION);
		if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
			return authHeader.substring(BEARER_PREFIX.length()).trim();
		}
		String xAdminKey = request.getHeader(HEADER_X_ADMIN_KEY);
		if (xAdminKey != null) {
			return xAdminKey.trim();
		}
		return null;
	}

	private void writeProblem(
			HttpServletResponse response,
			int status,
			String title,
			String detail,
			String instance
	) throws IOException {
		response.setStatus(status);
		response.setContentType("application/problem+json");
		response.setCharacterEncoding("UTF-8");
		ProblemDetailResponse problem = ProblemDetailResponse.of(title, status, detail, instance);
		objectMapper.writeValue(response.getWriter(), problem);
	}
}
