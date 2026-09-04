package io.github.kxng0109.aegisgate.mcp.security;

import io.github.kxng0109.aegisgate.contracts.SHA256Hash;
import io.github.kxng0109.aegisgate.contracts.VirtualApiKey;
import io.github.kxng0109.aegisgate.mcp.contracts.McpToolDefinition;
import io.github.kxng0109.aegisgate.mcp.router.McpAggregatedCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MCP Tool RBAC Policy Engine Unit Tests")
class McpToolRbacPolicyEngineTest {

	private McpToolRbacPolicyEngine rbacEngine;

	@BeforeEach
	void setUp() {
		rbacEngine = new McpToolRbacPolicyEngine();
	}

	@Test
	@DisplayName("matchesPattern handles wildcards, single character tokens, and regex metacharacters correctly")
	void matchesPatternScenarios() {
		// Global wildcard
		assertThat(McpToolRbacPolicyEngine.matchesPattern("postgres__run_query", "*")).isTrue();

		// Prefix wildcard
		assertThat(McpToolRbacPolicyEngine.matchesPattern("postgres__run_query", "postgres__*")).isTrue();
		assertThat(McpToolRbacPolicyEngine.matchesPattern("github__list_prs", "postgres__*")).isFalse();

		// Suffix wildcard
		assertThat(McpToolRbacPolicyEngine.matchesPattern("postgres__delete_user", "*:delete_*")).isFalse();
		assertThat(McpToolRbacPolicyEngine.matchesPattern("postgres:delete_user", "*:delete_*")).isTrue();

		// Single character wildcard ?
		assertThat(McpToolRbacPolicyEngine.matchesPattern("tool_1", "tool_?")).isTrue();
		assertThat(McpToolRbacPolicyEngine.matchesPattern("tool_12", "tool_?")).isFalse();

		// Metacharacter escaping
		assertThat(McpToolRbacPolicyEngine.matchesPattern("api.v1__query", "api.v1__*")).isTrue();
		assertThat(McpToolRbacPolicyEngine.matchesPattern("apiXv1__query", "api.v1__*")).isFalse();

		// Null/blank handling
		assertThat(McpToolRbacPolicyEngine.matchesPattern("tool", null)).isFalse();
		assertThat(McpToolRbacPolicyEngine.matchesPattern("tool", "   ")).isFalse();
	}

	@Test
	@DisplayName("isToolAllowed enforces deny list precedence and allow list restrictions")
	void isToolAllowedEvaluations() {
		VirtualApiKey keyWithDeny = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-key-1"),
				"gw-",
				"tenant-1",
				"key1",
				100,
				1000,
				Set.of(),
				Set.of(),
				Set.of("postgres__*"),
				Set.of("*:delete_*", "*:drop_*"),
				true,
				Instant.now()
		);

		// Allowed by allow-list and not denied
		assertThat(rbacEngine.isToolAllowed("postgres__run_query", keyWithDeny)).isTrue();
		assertThat(rbacEngine.isToolAllowed("postgres__explain_plan", keyWithDeny)).isTrue();

		// Denied by deny-list even though matches postgres__*
		assertThat(rbacEngine.isToolAllowed("postgres:delete_record", keyWithDeny)).isFalse();
		assertThat(rbacEngine.isToolAllowed("postgres:drop_table", keyWithDeny)).isFalse();

		// Not in allow list
		assertThat(rbacEngine.isToolAllowed("github__list_prs", keyWithDeny)).isFalse();

		// Null parameter checks
		assertThat(rbacEngine.isToolAllowed(null, keyWithDeny)).isFalse();
		assertThat(rbacEngine.isToolAllowed("", keyWithDeny)).isFalse();
		assertThat(rbacEngine.isToolAllowed("tool", null)).isFalse();
	}

	@Test
	@DisplayName("filterCatalog prunes unauthorized tools and handles null catalogs cleanly")
	void filterCatalogScenarios() {
		McpToolDefinition tool1 = new McpToolDefinition("postgres__query", "Query DB", null, null);
		McpToolDefinition tool2 = new McpToolDefinition("github__create_issue", "Create issue", null, null);
		McpAggregatedCatalog globalCatalog = new McpAggregatedCatalog(
				List.of(tool1, tool2),
				List.of(),
				List.of(),
				Instant.now()
		);

		VirtualApiKey pgOnlyKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-key-pg"),
				"gw-",
				"tenant-1",
				"pg-key",
				100,
				1000,
				Set.of(),
				Set.of(),
				Set.of("postgres__*"),
				Set.of(),
				true,
				Instant.now()
		);

		McpAggregatedCatalog filtered = rbacEngine.filterCatalog(globalCatalog, pgOnlyKey);
		assertThat(filtered.tools()).hasSize(1);
		assertThat(filtered.tools().getFirst().name()).isEqualTo("postgres__query");

		// Null handling
		assertThat(rbacEngine.filterCatalog(null, pgOnlyKey).tools()).isEmpty();
		assertThat(rbacEngine.filterCatalog(globalCatalog, null).tools()).isEmpty();
	}
}
