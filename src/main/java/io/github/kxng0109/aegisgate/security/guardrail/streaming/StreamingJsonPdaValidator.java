package io.github.kxng0109.aegisgate.security.guardrail.streaming;

/**
 * Incremental Pushdown Automaton (PDA) for validating streaming JSON payloads byte-by-byte.
 *
 * <p>Uses a zero-allocation primitive {@code long[]} stack with depth 64, early aborting syntax errors,
 * scope mismatches, and malformed structured outputs at token 15 rather than token 500.</p>
 */
public final class StreamingJsonPdaValidator {

	private static final int MAX_DEPTH = 64;

	private static final int SCOPE_ROOT = 0;
	private static final int SCOPE_OBJECT = 1;
	private static final int SCOPE_ARRAY = 2;

	private static final int STATE_EXPECT_KEY_OR_CLOSE = 1;
	private static final int STATE_EXPECT_COLON = 2;
	private static final int STATE_EXPECT_VALUE = 3;
	private static final int STATE_EXPECT_COMMA_OR_CLOSE = 4;

	private final long[] stack = new long[MAX_DEPTH];
	private int depth = 0;

	private boolean inString = false;
	private boolean escaping = false;
	private boolean started = false;
	private boolean finished = false;
	private boolean rejected = false;

	public StreamingJsonPdaValidator() {
		// Initialize root frame
		stack[0] = encodeFrame(SCOPE_ROOT, STATE_EXPECT_VALUE);
	}

	/**
	 * Ingests an incremental piece of JSON text emitted by the LLM.
	 *
	 * @param delta text chunk from SSE stream
	 * @return {@code true} if text conforms to valid JSON syntax so far, {@code false} if a violation occurred
	 */
	public boolean ingest(CharSequence delta) {
		if (rejected) {
			return false;
		}
		if (delta == null || delta.length() == 0) {
			return true;
		}

		int len = delta.length();
		for (int i = 0; i < len; i++) {
			char c = delta.charAt(i);

			if (inString) {
				if (escaping) {
					escaping = false;
					continue;
				}
				if (c == '\\') {
					escaping = true;
					continue;
				}
				if (c == '"') {
					inString = false;
					onStringClosed();
				}
				continue;
			}

			// Outside of string literal
			if (Character.isWhitespace(c)) {
				continue;
			}

			if (c == '"') {
				inString = true;
				onStringOpened();
				continue;
			}

			if (!processStructureChar(c)) {
				rejected = true;
				return false;
			}
		}

		return true;
	}

	private boolean processStructureChar(char c) {
		int scope = getScope(stack[depth]);
		int state = getState(stack[depth]);

		switch (c) {
			case '{' -> {
				started = true;
				if (depth >= MAX_DEPTH - 1) {
					return false; // Depth limit exceeded
				}
				depth++;
				stack[depth] = encodeFrame(SCOPE_OBJECT, STATE_EXPECT_KEY_OR_CLOSE);
				return true;
			}
			case '[' -> {
				started = true;
				if (depth >= MAX_DEPTH - 1) {
					return false;
				}
				depth++;
				stack[depth] = encodeFrame(SCOPE_ARRAY, STATE_EXPECT_VALUE);
				return true;
			}
			case '}' -> {
				if (scope != SCOPE_OBJECT) {
					return false; // Mismatched brace
				}
				depth--;
				if (depth == 0) {
					finished = true;
				} else {
					stack[depth] = encodeFrame(getScope(stack[depth]), STATE_EXPECT_COMMA_OR_CLOSE);
				}
				return true;
			}
			case ']' -> {
				if (scope != SCOPE_ARRAY) {
					return false; // Mismatched bracket
				}
				depth--;
				if (depth == 0) {
					finished = true;
				} else {
					stack[depth] = encodeFrame(getScope(stack[depth]), STATE_EXPECT_COMMA_OR_CLOSE);
				}
				return true;
			}
			case ':' -> {
				if (scope != SCOPE_OBJECT || state != STATE_EXPECT_COLON) {
					return false; // Colon outside of object key
				}
				stack[depth] = encodeFrame(scope, STATE_EXPECT_VALUE);
				return true;
			}
			case ',' -> {
				if (state != STATE_EXPECT_COMMA_OR_CLOSE) {
					return false; // Unexpected comma
				}
				int nextState = (scope == SCOPE_OBJECT) ? STATE_EXPECT_KEY_OR_CLOSE : STATE_EXPECT_VALUE;
				stack[depth] = encodeFrame(scope, nextState);
				return true;
			}
			default -> {
				// Primitive literal (number, boolean, null)
				if (Character.isLetterOrDigit(c) || c == '-' || c == '.' || c == '+') {
					stack[depth] = encodeFrame(scope, STATE_EXPECT_COMMA_OR_CLOSE);
					return true;
				}
				return false; // Invalid token character
			}
		}
	}

	private void onStringOpened() {
		started = true;
	}

	private void onStringClosed() {
		int scope = getScope(stack[depth]);
		int state = getState(stack[depth]);
		if (scope == SCOPE_OBJECT && state == STATE_EXPECT_KEY_OR_CLOSE) {
			stack[depth] = encodeFrame(scope, STATE_EXPECT_COLON);
		} else {
			stack[depth] = encodeFrame(scope, STATE_EXPECT_COMMA_OR_CLOSE);
		}
	}

	public boolean isFinished() {
		return started && depth == 0 && !inString && finished;
	}

	public boolean isRejected() {
		return rejected;
	}

	private static long encodeFrame(int scope, int state) {
		return ((long) scope & 0xFF) | (((long) state & 0xFF) << 8);
	}

	private static int getScope(long frame) {
		return (int) (frame & 0xFF);
	}

	private static int getState(long frame) {
		return (int) ((frame >> 8) & 0xFF);
	}
}
