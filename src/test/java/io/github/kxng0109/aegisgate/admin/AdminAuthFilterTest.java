package io.github.kxng0109.aegisgate.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("AdminAuthFilter")
class AdminAuthFilterTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final FilterChain filterChain = mock(FilterChain.class);

	@Test
	@DisplayName("fails closed with 403 Forbidden when master admin key is null or blank")
	void failsClosedWhenMasterKeyUnconfigured() throws ServletException, IOException {
		AdminAuthFilter filterNull = new AdminAuthFilter(null, objectMapper);
		AdminAuthFilter filterBlank = new AdminAuthFilter("   ", objectMapper);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		MockHttpServletResponse response1 = new MockHttpServletResponse();
		MockHttpServletResponse response2 = new MockHttpServletResponse();

		filterNull.doFilter(request, response1, filterChain);
		filterBlank.doFilter(request, response2, filterChain);

		assertThat(response1.getStatus()).isEqualTo(403);
		assertThat(response1.getContentType()).contains("application/problem+json");
		assertThat(response1.getContentAsString()).contains("Admin Interface Disabled");

		assertThat(response2.getStatus()).isEqualTo(403);
		assertThat(response2.getContentAsString()).contains("Admin Interface Disabled");

		verifyNoInteractions(filterChain);
	}

	@Test
	@DisplayName("rejects with 401 Unauthorized when credentials are missing")
	void rejectsMissingCredentials() throws ServletException, IOException {
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentType()).contains("application/problem+json");
		assertThat(response.getContentAsString()).contains("Missing or empty administrative credentials");
		verifyNoInteractions(filterChain);
	}

	@Test
	@DisplayName("rejects with 401 Unauthorized when Bearer token is incorrect")
	void rejectsInvalidBearerToken() throws ServletException, IOException {
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		request.addHeader("Authorization", "Bearer wrong-secret");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).contains("Invalid master admin key");
		verifyNoInteractions(filterChain);
	}

	@Test
	@DisplayName("rejects with 401 Unauthorized when X-Admin-Key is incorrect")
	void rejectsInvalidXAdminKey() throws ServletException, IOException {
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		request.addHeader("X-Admin-Key", "wrong-secret-header");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).contains("Invalid master admin key");
		verifyNoInteractions(filterChain);
	}

	@Test
	@DisplayName("allows request when Authorization Bearer token matches master key")
	void allowsValidBearerToken() throws ServletException, IOException {
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		request.addHeader("Authorization", "Bearer master-secret-12345");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(200);
		verify(filterChain).doFilter(request, response);
	}

	@Test
	@DisplayName("allows request when X-Admin-Key matches master key")
	void allowsValidXAdminKey() throws ServletException, IOException {
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper);

		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/admin/keys");
		request.addHeader("X-Admin-Key", "master-secret-12345");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, filterChain);

		assertThat(response.getStatus()).isEqualTo(200);
		verify(filterChain).doFilter(request, response);
	}

	@Test
	@DisplayName("rejects when Authorization or X-Admin-Key is blank or non-bearer")
	void rejectsBlankOrNonBearerHeaders() throws ServletException, IOException {
		AdminAuthFilter filter = new AdminAuthFilter("master-secret-12345", objectMapper);

		// Blank Bearer
		MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/v1/admin/keys");
		req1.addHeader("Authorization", "Bearer   ");
		MockHttpServletResponse resp1 = new MockHttpServletResponse();
		filter.doFilter(req1, resp1, filterChain);
		assertThat(resp1.getStatus()).isEqualTo(401);

		// Non-Bearer Auth without X-Admin-Key
		MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/v1/admin/keys");
		req2.addHeader("Authorization", "Basic dXNlcjpwYXNz");
		MockHttpServletResponse resp2 = new MockHttpServletResponse();
		filter.doFilter(req2, resp2, filterChain);
		assertThat(resp2.getStatus()).isEqualTo(401);

		// Blank X-Admin-Key
		MockHttpServletRequest req3 = new MockHttpServletRequest("GET", "/v1/admin/keys");
		req3.addHeader("X-Admin-Key", "   ");
		MockHttpServletResponse resp3 = new MockHttpServletResponse();
		filter.doFilter(req3, resp3, filterChain);
		assertThat(resp3.getStatus()).isEqualTo(401);
	}
}
