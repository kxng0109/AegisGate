package io.github.kxng0109.aegisgate.mcp.router;

import io.github.kxng0109.aegisgate.mcp.config.McpGatewayProperties;
import io.github.kxng0109.aegisgate.mcp.contracts.*;
import io.github.kxng0109.aegisgate.mcp.protocol.McpHeaderNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Aggregates and federates tools, resources, and prompts across multiple upstream Model Context Protocol (MCP) servers.
 * Executes parallel virtual-thread fan-out and maintains deterministic catalog sorting for LLM prompt caching.
 */
@Slf4j
@Service
public class McpCatalogAggregator {

	private final McpGatewayProperties properties;
	private final McpCatalogCache catalogCache;
	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;

	public McpCatalogAggregator(
			McpGatewayProperties properties,
			McpCatalogCache catalogCache,
			@Qualifier("mcpHttpClient") HttpClient httpClient,
			ObjectMapper objectMapper
	) {
		this.properties = properties;
		this.catalogCache = catalogCache;
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
	}

	/**
	 * Returns the federated catalog from L0 cache, or fetches it dynamically if expired.
	 */
	public McpAggregatedCatalog getAggregatedCatalog() {
		return catalogCache.getOrCompute(this::refreshCatalog);
	}

	/**
	 * Scheduled background catalog refresh to pre-warm the L0 cache.
	 */
	@Scheduled(cron = "${gateway.mcp.catalog-refresh-cron:0 */5 * * * *}")
	public void scheduledRefresh() {
		if (!properties.isEnabled()) {
			return;
		}
		try {
			log.debug("Starting scheduled MCP catalog refresh");
			McpAggregatedCatalog refreshed = refreshCatalog();
			catalogCache.put(refreshed);
		} catch (Exception e) {
			log.warn("Scheduled MCP catalog refresh encountered an error: {}", e.getMessage());
		}
	}

	/**
	 * Synchronously refreshes the aggregated catalog by querying all enabled upstream servers in parallel.
	 */
	public McpAggregatedCatalog refreshCatalog() {
		Map<String, McpServerConfig> servers = properties.getServers();
		if (servers.isEmpty()) {
			return McpAggregatedCatalog.empty();
		}

		List<CompletableFuture<ServerCatalogPartial>> futures = new ArrayList<>();
		for (McpServerConfig server : servers.values()) {
			if (!server.enabled()) {
				continue;
			}
			futures.add(CompletableFuture.supplyAsync(
					() -> fetchServerCatalog(server),
					r -> Thread.ofVirtual().name("mcp-catalog-", 0).start(r)
			));
		}

		List<McpToolDefinition> aggregatedTools = new ArrayList<>();
		List<McpResourceDefinition> aggregatedResources = new ArrayList<>();
		List<McpPromptDefinition> aggregatedPrompts = new ArrayList<>();

		for (CompletableFuture<ServerCatalogPartial> future : futures) {
			try {
				ServerCatalogPartial partial = future.join();
				aggregatedTools.addAll(partial.tools());
				aggregatedResources.addAll(partial.resources());
				aggregatedPrompts.addAll(partial.prompts());
			} catch (Exception e) {
				log.warn("Failed to retrieve catalog slice from upstream server: {}", e.getMessage());
			}
		}

		// Deterministic sort by name to optimize LLM prompt cache hit rate
		aggregatedTools.sort(Comparator.comparing(McpToolDefinition::name));
		aggregatedResources.sort(Comparator.comparing(McpResourceDefinition::uri));
		aggregatedPrompts.sort(Comparator.comparing(McpPromptDefinition::name));

		return new McpAggregatedCatalog(
				aggregatedTools,
				aggregatedResources,
				aggregatedPrompts,
				Instant.now()
		);
	}

	private ServerCatalogPartial fetchServerCatalog(McpServerConfig server) {
		List<McpToolDefinition> tools = fetchTools(server);
		List<McpResourceDefinition> resources = fetchResources(server);
		List<McpPromptDefinition> prompts = fetchPrompts(server);
		return new ServerCatalogPartial(tools, resources, prompts);
	}

	private List<McpToolDefinition> fetchTools(McpServerConfig server) {
		try {
			String rpcRequest = "{\"jsonrpc\":\"2.0\",\"id\":\"cat-tools\",\"method\":\"tools/list\"}";
			HttpRequest request = buildRpcRequest(server, "tools/list", rpcRequest);
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				JsonNode root = objectMapper.readTree(response.body());
				JsonNode toolsArr = root.path("result").path("tools");
				if (toolsArr.isArray()) {
					List<McpToolDefinition> list = new ArrayList<>();
					for (JsonNode toolNode : toolsArr) {
						String rawName = toolNode.path("name").asText("");
						if (rawName.isBlank()) {
							continue;
						}
						// Apply server-level allow/deny filters
						if (!server.allowedTools().isEmpty() && !server.allowedTools().contains(rawName)) {
							continue;
						}
						if (!server.deniedTools().isEmpty() && server.deniedTools().contains(rawName)) {
							continue;
						}

						String namespacedName = McpRouter.formatNamespacedName(server.name(), rawName);
						String description = toolNode.has("description") ? toolNode.path("description")
						                                                           .asText(null) : null;
						JsonNode inputSchema = toolNode.path("inputSchema");
						JsonNode meta = toolNode.has("_meta") ? toolNode.path("_meta") : null;

						list.add(new McpToolDefinition(namespacedName, description, inputSchema, meta));
					}
					return list;
				}
			}
		} catch (Exception e) {
			log.warn("Failed to fetch tools/list from upstream MCP server '{}': {}", server.name(), e.getMessage());
		}
		return List.of();
	}

	private List<McpResourceDefinition> fetchResources(McpServerConfig server) {
		try {
			String rpcRequest = "{\"jsonrpc\":\"2.0\",\"id\":\"cat-res\",\"method\":\"resources/list\"}";
			HttpRequest request = buildRpcRequest(server, "resources/list", rpcRequest);
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				JsonNode root = objectMapper.readTree(response.body());
				JsonNode resArr = root.path("result").path("resources");
				if (resArr.isArray()) {
					List<McpResourceDefinition> list = new ArrayList<>();
					for (JsonNode resNode : resArr) {
						String uri = resNode.path("uri").asText("");
						String name = resNode.path("name").asText(uri);
						String description = resNode.has("description") ? resNode.path("description")
						                                                         .asText(null) : null;
						String mimeType = resNode.has("mimeType") ? resNode.path("mimeType").asText(null) : null;
						JsonNode meta = resNode.has("_meta") ? resNode.path("_meta") : null;
						list.add(new McpResourceDefinition(uri, name, description, mimeType, meta));
					}
					return list;
				}
			}
		} catch (Exception e) {
			log.debug("resources/list not supported or failed on MCP server '{}': {}", server.name(), e.getMessage());
		}
		return List.of();
	}

	private List<McpPromptDefinition> fetchPrompts(McpServerConfig server) {
		try {
			String rpcRequest = "{\"jsonrpc\":\"2.0\",\"id\":\"cat-prm\",\"method\":\"prompts/list\"}";
			HttpRequest request = buildRpcRequest(server, "prompts/list", rpcRequest);
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				JsonNode root = objectMapper.readTree(response.body());
				JsonNode prmArr = root.path("result").path("prompts");
				if (prmArr.isArray()) {
					List<McpPromptDefinition> list = new ArrayList<>();
					for (JsonNode pNode : prmArr) {
						String rawName = pNode.path("name").asText("");
						String namespacedName = McpRouter.formatNamespacedName(server.name(), rawName);
						String description = pNode.has("description") ? pNode.path("description").asText(null) : null;
						List<McpPromptArgument> args = new ArrayList<>();
						JsonNode argsArr = pNode.path("arguments");
						if (argsArr.isArray()) {
							for (JsonNode a : argsArr) {
								args.add(new McpPromptArgument(
										a.path("name").asText(""),
										a.has("description") ? a.path("description").asText(null) : null,
										a.path("required").asBoolean(false)
								));
							}
						}
						JsonNode meta = pNode.has("_meta") ? pNode.path("_meta") : null;
						list.add(new McpPromptDefinition(namespacedName, description, args, meta));
					}
					return list;
				}
			}
		} catch (Exception e) {
			log.debug("prompts/list not supported or failed on MCP server '{}': {}", server.name(), e.getMessage());
		}
		return List.of();
	}

	private HttpRequest buildRpcRequest(McpServerConfig server, String method, String rpcBody) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
		                                         .uri(server.baseUrl())
		                                         .timeout(server.requestTimeout())
		                                         .header("Content-Type", "application/json")
		                                         .header("Accept", "application/json")
		                                         .header(
				                                         McpHeaderNormalizer.HEADER_PROTOCOL_VERSION,
				                                         McpProtocolVersion.LATEST
		                                         )
		                                         .header(McpHeaderNormalizer.HEADER_MCP_METHOD, method)
		                                         .POST(HttpRequest.BodyPublishers.ofString(rpcBody));

		if (server.apiKey() != null && !server.apiKey().value().isBlank()) {
			builder.header("Authorization", "Bearer " + server.apiKey().value());
		}
		return builder.build();
	}

	private record ServerCatalogPartial(
			List<McpToolDefinition> tools,
			List<McpResourceDefinition> resources,
			List<McpPromptDefinition> prompts
	) {
	}
}
