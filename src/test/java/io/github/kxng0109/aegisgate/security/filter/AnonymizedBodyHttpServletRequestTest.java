package io.github.kxng0109.aegisgate.security.filter;

import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AnonymizedBodyHttpServletRequest Tests")
class AnonymizedBodyHttpServletRequestTest {

	@Test
	@DisplayName("wraps and serves anonymized byte array with accurate lengths and stream reads")
	void wrapsAnonymizedBody() throws IOException {
		MockHttpServletRequest rawRequest = new MockHttpServletRequest("POST", "/v1/chat/completions");
		byte[] body = "{\"prompt\": \"Hello <PERSON_1>\"}".getBytes(StandardCharsets.UTF_8);

		AnonymizedBodyHttpServletRequest wrapped = new AnonymizedBodyHttpServletRequest(rawRequest, body);

		assertThat(wrapped.getContentLength()).isEqualTo(body.length);
		assertThat(wrapped.getContentLengthLong()).isEqualTo(body.length);
		assertThat(wrapped.getContentAsByteArray()).isEqualTo(body);

		// Verify getInputStream
		ServletInputStream in = wrapped.getInputStream();
		assertThat(in.isReady()).isTrue();
		assertThat(in.isFinished()).isFalse();

		byte[] readBuf = new byte[body.length];
		int bytesRead = in.read(readBuf, 0, readBuf.length);
		assertThat(bytesRead).isEqualTo(body.length);
		assertThat(readBuf).isEqualTo(body);
		assertThat(in.read()).isEqualTo(-1);
		assertThat(in.isFinished()).isTrue();

		assertThatThrownBy(() -> in.setReadListener(null))
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessage("Asynchronous reads are not supported");

		// Verify getReader
		BufferedReader reader = wrapped.getReader();
		assertThat(reader.readLine()).isEqualTo("{\"prompt\": \"Hello <PERSON_1>\"}");
	}

	@Test
	@DisplayName("handles null body array by defaulting to empty bytes")
	void handlesNullBody() {
		MockHttpServletRequest rawRequest = new MockHttpServletRequest("POST", "/v1/chat/completions");
		AnonymizedBodyHttpServletRequest wrapped = new AnonymizedBodyHttpServletRequest(rawRequest, null);

		assertThat(wrapped.getContentLength()).isEqualTo(0);
		assertThat(wrapped.getContentLengthLong()).isEqualTo(0L);
		assertThat(wrapped.getContentAsByteArray()).isEmpty();
	}
}
