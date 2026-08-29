package io.github.kxng0109.aegisgate.proxy.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Parsed form of an OpenAI shaped chat completion request body.
 *
 * <p>The gateway accepts one client contract, the OpenAI chat completions
 * shape, and translates it to the native protocol of whichever provider wins
 * the failover. This type carries the fields the translators care about;
 * unknown fields are ignored so newer client payloads never break parsing.</p>
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
public record OpenAiChatRequest(
		String model,
		List<Message> messages,
		@Nullable Double temperature,
		@Nullable @JsonProperty("max_tokens") Integer maxTokens,
		@Nullable @JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
		@Nullable @JsonProperty("top_p") Double topP,
		@Nullable JsonNode stop,
		@Nullable Boolean stream,
		@Nullable @JsonProperty("stream_options") JsonNode streamOptions
) {

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
	public record Message(
			@Nullable String role,
			@Nullable JsonNode content
	) {
	}
}