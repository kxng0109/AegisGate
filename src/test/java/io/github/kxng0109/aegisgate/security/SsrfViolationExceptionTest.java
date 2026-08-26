package io.github.kxng0109.aegisgate.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

class SsrfViolationExceptionTest {

	@Test
	@DisplayName("is an unchecked exception")
	void isUncheckedException() {
		assertInstanceOf(RuntimeException.class, new SsrfViolationException("blocked"));
	}

	@Test
	@DisplayName("carries the violation message without a cause")
	void carriesMessageWithoutCause() {
		SsrfViolationException exception = new SsrfViolationException("host resolves to loopback");

		assertEquals("host resolves to loopback", exception.getMessage());
		assertNull(exception.getCause());
	}

	@Test
	@DisplayName("wraps and preserves an underlying DNS failure")
	void wrapsUnderlyingDnsFailure() {
		UnknownHostException cause = new UnknownHostException("metadata.internal: Name or service not known");
		SsrfViolationException exception =
				new SsrfViolationException("cannot resolve host; failing closed", cause);

		assertEquals("cannot resolve host; failing closed", exception.getMessage());
		assertSame(cause, exception.getCause());
		assertInstanceOf(UnknownHostException.class, exception.getCause());
	}
}
