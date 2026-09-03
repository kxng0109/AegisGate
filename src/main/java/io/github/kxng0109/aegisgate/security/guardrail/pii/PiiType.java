package io.github.kxng0109.aegisgate.security.guardrail.pii;

/**
 * Categories of Personally Identifiable Information (PII) recognized by AegisGate.
 */
public enum PiiType {
	US_SSN("<US_SSN_"),
	EMAIL("<EMAIL_"),
	PHONE_E164("<PHONE_"),
	PHONE_NG_MOBILE("<PHONE_"),
	PHONE_NG_FIXED("<PHONE_"),
	IBAN("<IBAN_"),
	CREDIT_CARD("<CARD_"),
	VERVE_CARD("<CARD_"),
	NIGERIAN_BVN("<BVN_"),
	NIGERIAN_NIN("<NIN_"),
	NIGERIAN_TIN("<TAX_ID_"),
	PERSON_NAME("<PERSON_");

	private final String surrogatePrefix;

	PiiType(String surrogatePrefix) {
		this.surrogatePrefix = surrogatePrefix;
	}

	public String getSurrogatePrefix() {
		return surrogatePrefix;
	}
}
