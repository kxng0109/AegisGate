package io.github.kxng0109.aegisgate.proxy.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Parsed form of an OpenAI shaped chat completion request body.
 *
 * <p>The gateway accepts one client contract, the OpenAI chat completions
 * shape, and translates it to the native protocol of whichever provider wins the failover. This type carries the fields
 * the translators care about; unknown fields are ignored so newer client payloads never break parsing.</p>
 *
 * @param model               requested model name
 * @param messages            conversation messages
 * @param temperature         sampling temperature, may be {@code null}
 * @param maxTokens           maximum completion tokens, may be {@code null}
 * @param maxCompletionTokens newer alias for the maximum, may be {@code null}
 * @param topP                nucleus sampling parameter, may be {@code null}
 * @param stop                stop sequences as a string or an array, may be {@code null}
 * @param stream              streaming flag, defaults to {@code true} for the proxy
 * @param streamOptions       streaming options, may be {@code null}
 * @param tools               list of function tool definitions available for the model to call, may be {@code null}
 * @param toolChoice          tool selection mode or specific function choice, may be {@code null}
 * @param parallelToolCalls   whether to allow parallel function calls, may be {@code null}
 * @param responseFormat      structured output response format specification, may be {@code null}
 * @param reasoningEffort     reasoning effort constraint for thinking models, may be {@code null}
 * @param thinking            reasoning configuration for hybrid thinking models, may be {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(name = "OpenAiChatRequest", description = "OpenAI-compatible chat completion request payload")
public record OpenAiChatRequest(
		@Schema(description = "Configured model alias identifier", example = "gpt-56-luna", requiredMode = Schema.RequiredMode.REQUIRED)
		String model,

		@Schema(description = "Conversation message history", requiredMode = Schema.RequiredMode.REQUIRED)
		List<Message> messages,

		@Schema(description = "Sampling temperature (0.0 = deterministic, 1.0 = creative)", example = "0.0")
		@Nullable Double temperature,

		@Schema(description = "Maximum token limit for generation", example = "1000")
		@Nullable @JsonProperty("max_tokens") Integer maxTokens,

		@Schema(description = "Modern alias for maximum token limit", example = "1000")
		@Nullable @JsonProperty("max_completion_tokens") Integer maxCompletionTokens,

		@Schema(description = "Nucleus sampling probability threshold", example = "1.0")
		@Nullable @JsonProperty("top_p") Double topP,

		@Schema(description = "Stop sequence string or list of strings", implementation = Object.class, example = "[\"\\n\", \"STOP\"]")
		@Nullable JsonNode stop,

		@Schema(description = "Whether to stream partial token chunks as Server-Sent Events (SSE)", example = "true")
		@Nullable Boolean stream,

		@Schema(description = "Optional stream options (e.g. include_usage)", implementation = Object.class)
		@Nullable @JsonProperty("stream_options") JsonNode streamOptions,

		@Schema(description = "List of tools available for the model to call", implementation = Object.class)
		@Nullable JsonNode tools,

		@Schema(description = "Tool choice directive (auto, none, required, or specific function)", implementation = Object.class)
		@Nullable @JsonProperty("tool_choice") JsonNode toolChoice,

		@Schema(description = "Whether to enable parallel function calling during tool use", example = "true")
		@Nullable @JsonProperty("parallel_tool_calls") Boolean parallelToolCalls,

		@Schema(description = "Structured output response format specification (e.g. json_object or json_schema)", implementation = Object.class)
		@Nullable @JsonProperty("response_format") JsonNode responseFormat,

		@Schema(description = "Reasoning effort level for thinking models (low, medium, high, max)", example = "high")
		@Nullable @JsonProperty("reasoning_effort") String reasoningEffort,

		@Schema(description = "Thinking mode configuration object for hybrid reasoning models", implementation = Object.class)
		@Nullable JsonNode thinking
) {

	/**
	 * Convenience constructor for standard chat requests without tool definitions or reasoning parameters.
	 */
	public OpenAiChatRequest(String model, List<Message> messages, Double temperature,
	                         Integer maxTokens, Integer maxCompletionTokens, Double topP,
	                         JsonNode stop, Boolean stream, JsonNode streamOptions) {
		this(
				model, messages, temperature, maxTokens, maxCompletionTokens, topP, stop, stream, streamOptions,
				null, null, null, null, null, null
		);
	}

	/**
	 * Stores defensive copies of the mutable components so a parsed request
	 * cannot be altered by the caller that produced the raw body.
	 */
	public OpenAiChatRequest {
		messages = messages == null ? List.of() : List.copyOf(messages);
		stop = stop == null ? null : stop.deepCopy();
		streamOptions = streamOptions == null ? null : streamOptions.deepCopy();
		tools = tools == null ? null : tools.deepCopy();
		toolChoice = toolChoice == null ? null : toolChoice.deepCopy();
		responseFormat = responseFormat == null ? null : responseFormat.deepCopy();
		thinking = thinking == null ? null : thinking.deepCopy();
	}

	/**
	 * Whether the client asked for the usage chunk to be streamed back.
	 *
	 * @return {@code true} when {@code stream_options.include_usage} is set
	 */
	public boolean requestsUsage() {
		if (streamOptions == null || !streamOptions.isObject()) {
			return false;
		}
		return streamOptions.path("include_usage").asBoolean(false);
	}

	/**
	 * The largest usable token bound from either of the two accepted fields.
	 *
	 * @return the token bound, or {@code null} when neither is present
	 */
	public @Nullable Integer effectiveMaxTokens() {
		if (maxCompletionTokens != null) {
			return maxCompletionTokens;
		}
		return maxTokens;
	}

	/**
	 * One conversation message in its client supplied form.
	 *
	 * @param role             speaker role (user, assistant, system, tool, and so on)
	 * @param content          text content or an array of typed content parts
	 * @param name             optional author or tool name
	 * @param toolCallId       tool call identifier when role is "tool"
	 * @param toolCalls        tool calls generated by the assistant
	 * @param reasoningContent chain-of-thought reasoning content from thinking models
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Schema(name = "ChatMessage", description = "Single message in the chat conversation history")
	public record Message(
			@Schema(description = "Role of the message author (system, user, assistant, tool)", example = "user")
			@Nullable String role,

			@Schema(description = "Text content of the message or array of multipart content objects", implementation = Object.class, example = "Hello, world!")
			@Nullable JsonNode content,

			@Schema(description = "Optional author or tool name", example = "get_weather")
			@Nullable String name,

			@Schema(description = "Tool call ID matching the tool call that produced this result", example = "call_abc123")
			@Nullable @JsonProperty("tool_call_id") String toolCallId,

			@Schema(description = "Tool calls requested by the model in an assistant message", implementation = Object.class)
			@Nullable @JsonProperty("tool_calls") JsonNode toolCalls,

			@Schema(description = "Reasoning content generated during chain-of-thought thinking", example = "Analyzing user intent...")
			@Nullable @JsonProperty("reasoning_content") String reasoningContent
	) {
		/**
		 * Convenience constructor for basic messages with role and content only.
		 */
		public Message(String role, JsonNode content) {
			this(role, content, null, null, null, null);
		}

		public Message {
			content = content == null ? null : content.deepCopy();
			toolCalls = toolCalls == null ? null : toolCalls.deepCopy();
		}
	}
}