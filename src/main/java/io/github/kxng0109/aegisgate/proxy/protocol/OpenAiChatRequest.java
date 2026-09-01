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
		@Nullable @JsonProperty("stream_options") JsonNode streamOptions
) {

	/**
	 * Stores defensive copies of the mutable components so a parsed request
	 * cannot be altered by the caller that produced the raw body.
	 */
	public OpenAiChatRequest {
		messages = messages == null ? List.of() : List.copyOf(messages);
		stop = stop == null ? null : stop.deepCopy();
		streamOptions = streamOptions == null ? null : streamOptions.deepCopy();
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
	 * @param role    speaker role (user, assistant, system, tool, and so on)
	 * @param content text content or an array of typed content parts
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@Schema(name = "ChatMessage", description = "Single message in the chat conversation history")
	public record Message(
			@Schema(description = "Role of the message author (system, user, assistant, tool)", example = "user")
			@Nullable String role,

			@Schema(description = "Text content of the message or array of multipart content objects", implementation = Object.class, example = "Hello, world!")
			@Nullable JsonNode content
	) {
	}
}