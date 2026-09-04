package io.github.kxng0109.aegisgate.mcp.hitl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Admin MCP Approval Controller Unit Tests")
class AdminMcpApprovalControllerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	private AdminMcpApprovalController controller;

	@BeforeEach
	void setUp() {
		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		controller = new AdminMcpApprovalController(redisTemplate, objectMapper);
	}

	@Test
	@DisplayName("getPendingApproval retrieves metadata or returns 404 when absent")
	void getPendingApprovalScenarios() {
		// Absent
		when(valueOperations.get("mcp:hitl:pending:tok-absent")).thenReturn(null);
		ResponseEntity<String> resp1 = controller.getPendingApproval("tok-absent");
		assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		// Present
		String mockJson = "{\"tokenId\":\"tok-1\",\"toolName\":\"postgres__execute_sql\"}";
		when(valueOperations.get("mcp:hitl:pending:tok-1")).thenReturn(mockJson);
		ResponseEntity<String> resp2 = controller.getPendingApproval("tok-1");
		assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resp2.getBody()).isEqualTo(mockJson);
	}

	@Test
	@DisplayName("approveToolCall sets APPROVED in Redis and returns 200")
	void approveToolCallSuccess() {
		when(valueOperations.get("mcp:hitl:pending:tok-1")).thenReturn("{\"tokenId\":\"tok-1\"}");

		ResponseEntity<String> response = controller.approveToolCall("tok-1");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("APPROVED");
		verify(valueOperations).set("mcp:hitl:approved:tok-1", "APPROVED", 300, TimeUnit.SECONDS);
	}

	@Test
	@DisplayName("approveToolCall returns 404 when pending token not found")
	void approveToolCallNotFound() {
		when(valueOperations.get("mcp:hitl:pending:tok-missing")).thenReturn(null);
		ResponseEntity<String> response = controller.approveToolCall("tok-missing");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("rejectToolCall purges keys and returns 200")
	void rejectToolCallPurgesKeys() {
		ResponseEntity<String> response = controller.rejectToolCall("tok-1");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("REJECTED");
		verify(redisTemplate).delete("mcp:hitl:pending:tok-1");
		verify(redisTemplate).delete("mcp:hitl:approved:tok-1");
	}
}
