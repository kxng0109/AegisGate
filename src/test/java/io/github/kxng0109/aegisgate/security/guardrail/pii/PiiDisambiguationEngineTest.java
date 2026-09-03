package io.github.kxng0109.aegisgate.security.guardrail.pii;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PiiDisambiguationEngine Tests")
class PiiDisambiguationEngineTest {

	@Test
	@DisplayName("private constructor can be invoked via reflection for utility class coverage")
	void privateConstructorCoverage() throws Exception {
		Constructor<PiiDisambiguationEngine> constructor = PiiDisambiguationEngine.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		PiiDisambiguationEngine instance = constructor.newInstance();
		assertThat(instance).isNotNull();
	}

	@Test
	@DisplayName("DisambiguationResult record accessors work properly")
	void disambiguationResultRecord() {
		PiiDisambiguationEngine.DisambiguationResult result =
				new PiiDisambiguationEngine.DisambiguationResult(PiiType.NIGERIAN_BVN, 0.99);
		assertThat(result.type()).isEqualTo(PiiType.NIGERIAN_BVN);
		assertThat(result.confidence()).isEqualTo(0.99);
	}

	@Test
	@DisplayName("returns empty on null, incorrect lengths, or non-digits")
	void invalidInputFormats() {
		assertThat(PiiDisambiguationEngine.disambiguate(null, "context", 0)).isEmpty();
		assertThat(PiiDisambiguationEngine.disambiguate("1234567890", "context", 0)).isEmpty(); // 10 digits
		assertThat(PiiDisambiguationEngine.disambiguate("123456789012", "context", 0)).isEmpty(); // 12 digits
		assertThat(PiiDisambiguationEngine.disambiguate("0803123456a", "context", 0)).isEmpty(); // letter
		assertThat(PiiDisambiguationEngine.disambiguate("0803-123456", "context", 0)).isEmpty(); // symbol
	}

	@Test
	@DisplayName("Tier 4: rejects low-entropy repetition and monotonic sequences")
	void rejectsLowEntropyAndMonotonic() {
		// Low entropy (< 1.5)
		assertThat(PiiDisambiguationEngine.disambiguate("11111111111", "call me", 0)).isEmpty();
		assertThat(PiiDisambiguationEngine.disambiguate("00000000000", "my phone", 0)).isEmpty();

		// Monotonic ascending and descending
		assertThat(PiiDisambiguationEngine.disambiguate("12345678901", "bvn", 0)).isEmpty();
		assertThat(PiiDisambiguationEngine.disambiguate("98765432109", "nin", 0)).isEmpty();
	}

	@Test
	@DisplayName("Tier 4: suppresses UNIX epoch timestamps when surrounded by time/date keywords")
	void suppressesUnixEpochTimestamps() {
		String contextWithTimestamp = "updated_at date created ts: 17123456789 in system";
		Optional<PiiDisambiguationEngine.DisambiguationResult> result =
				PiiDisambiguationEngine.disambiguate("17123456789", contextWithTimestamp, 27);
		assertThat(result).isEmpty();

		// Without timestamp keywords, falls through to Tier 3 context check
		String contextWithBvn = "customer account bvn is 17123456789 verified";
		Optional<PiiDisambiguationEngine.DisambiguationResult> bvnResult =
				PiiDisambiguationEngine.disambiguate("17123456789", contextWithBvn, 24);
		assertThat(bvnResult).isPresent();
		assertThat(bvnResult.get().type()).isEqualTo(PiiType.NIGERIAN_BVN);
	}

	@Test
	@DisplayName("Tier 1: resolves Nigerian NCC mobile numbers starting with 0")
	void resolvesNigerianMobileNumbers() {
		// MTN 0803
		Optional<PiiDisambiguationEngine.DisambiguationResult> mtn =
				PiiDisambiguationEngine.disambiguate("08031234567", "call me on 08031234567", 11);
		assertThat(mtn).isPresent();
		assertThat(mtn.get().type()).isEqualTo(PiiType.PHONE_NG_MOBILE);
		assertThat(mtn.get().confidence()).isEqualTo(0.98);

		// Glo 0705
		Optional<PiiDisambiguationEngine.DisambiguationResult> glo =
				PiiDisambiguationEngine.disambiguate("07051234567", "07051234567", 0);
		assertThat(glo).isPresent();
		assertThat(glo.get().type()).isEqualTo(PiiType.PHONE_NG_MOBILE);

		// Airtel 0902
		Optional<PiiDisambiguationEngine.DisambiguationResult> airtel =
				PiiDisambiguationEngine.disambiguate("09021234567", "reach out", 0);
		assertThat(airtel).isPresent();
		assertThat(airtel.get().type()).isEqualTo(PiiType.PHONE_NG_MOBILE);
	}

	@Test
	@DisplayName("Tier 1: resolves Nigerian NCC fixed lines (0201 Lagos, 0209 Abuja)")
	void resolvesNigerianFixedLines() {
		Optional<PiiDisambiguationEngine.DisambiguationResult> fixedLagos =
				PiiDisambiguationEngine.disambiguate("02011234567", "Lagos landline: 02011234567", 16);
		assertThat(fixedLagos).isPresent();
		assertThat(fixedLagos.get().type()).isEqualTo(PiiType.PHONE_NG_FIXED);
		assertThat(fixedLagos.get().confidence()).isEqualTo(0.95);
	}

	@Test
	@DisplayName("Tier 1: non-standard 0-prefixed number requires phone keyword context")
	void nonStandard0PrefixRequiresPhoneContext() {
		// Non-standard prefix "050" with phone keyword "whatsapp"
		String withKeyword = "send a message on whatsapp 05012345678 today";
		Optional<PiiDisambiguationEngine.DisambiguationResult> withKw =
				PiiDisambiguationEngine.disambiguate("05012345678", withKeyword, 27);
		assertThat(withKw).isPresent();
		assertThat(withKw.get().type()).isEqualTo(PiiType.PHONE_NG_MOBILE);
		assertThat(withKw.get().confidence()).isEqualTo(0.85);

		// Without phone keyword
		String withoutKeyword = "item id 05012345678 in order";
		Optional<PiiDisambiguationEngine.DisambiguationResult> withoutKw =
				PiiDisambiguationEngine.disambiguate("05012345678", withoutKeyword, 8);
		assertThat(withoutKw).isEmpty();
	}

	@Test
	@DisplayName("Tier 2: Prefix 22 defaults to BVN unless exclusive NIN keyword present")
	void prefix22BvnCluster() {
		// Neutral context -> BVN (0.90)
		Optional<PiiDisambiguationEngine.DisambiguationResult> neutral =
				PiiDisambiguationEngine.disambiguate("22123456789", "User identifier 22123456789", 16);
		assertThat(neutral).isPresent();
		assertThat(neutral.get().type()).isEqualTo(PiiType.NIGERIAN_BVN);
		assertThat(neutral.get().confidence()).isEqualTo(0.90);

		// With BVN keyword -> BVN (0.99)
		Optional<PiiDisambiguationEngine.DisambiguationResult> bvnKw =
				PiiDisambiguationEngine.disambiguate("22123456789", "Bank verification BVN: 22123456789", 22);
		assertThat(bvnKw).isPresent();
		assertThat(bvnKw.get().type()).isEqualTo(PiiType.NIGERIAN_BVN);
		assertThat(bvnKw.get().confidence()).isEqualTo(0.99);

		// With exclusive NIN keyword -> NIN (0.90)
		Optional<PiiDisambiguationEngine.DisambiguationResult> ninKw =
				PiiDisambiguationEngine.disambiguate("22123456789", "Citizenship NIMC NIN: 22123456789", 22);
		assertThat(ninKw).isPresent();
		assertThat(ninKw.get().type()).isEqualTo(PiiType.NIGERIAN_NIN);
		assertThat(ninKw.get().confidence()).isEqualTo(0.90);
	}

	@Test
	@DisplayName("Tier 3: Non-22 numbers starting with 1-9 require explicit context gating")
	void contextGatingForOtherLeadingDigits() {
		// With BVN keyword
		String bvnContext = "Please link your Bank Verification Number: 31234567890 for KYC";
		Optional<PiiDisambiguationEngine.DisambiguationResult> bvn =
				PiiDisambiguationEngine.disambiguate("31234567890", bvnContext, 43);
		assertThat(bvn).isPresent();
		assertThat(bvn.get().type()).isEqualTo(PiiType.NIGERIAN_BVN);
		assertThat(bvn.get().confidence()).isEqualTo(0.95);

		// With NIN keyword
		String ninContext = "Submit your National Identity Slip NIN: 31234567890 today";
		Optional<PiiDisambiguationEngine.DisambiguationResult> nin =
				PiiDisambiguationEngine.disambiguate("31234567890", ninContext, 40);
		assertThat(nin).isPresent();
		assertThat(nin.get().type()).isEqualTo(PiiType.NIGERIAN_NIN);
		assertThat(nin.get().confidence()).isEqualTo(0.95);

		// Ambiguous or missing context -> empty to avoid false-positive tagging
		String neutralContext = "The reference ID is 31234567890 in the database";
		assertThat(PiiDisambiguationEngine.disambiguate("31234567890", neutralContext, 20)).isEmpty();
		assertThat(PiiDisambiguationEngine.disambiguate("31234567890", null, 0)).isEmpty();

		// Both BVN and NIN keywords present for non-22 digits -> ambiguous, returns empty
		String bothKeywords = "BVN account and National Identity NIN: 31234567890";
		assertThat(PiiDisambiguationEngine.disambiguate("31234567890", bothKeywords, 37)).isEmpty();

		// Both BVN and NIN keywords present for 22 digits -> defaults to BVN 0.99
		String bothKeywords22 = "BVN account and National Identity NIN: 22123456789";
		Optional<PiiDisambiguationEngine.DisambiguationResult> res22 =
				PiiDisambiguationEngine.disambiguate("22123456789", bothKeywords22, 37);
		assertThat(res22).isPresent();
		assertThat(res22.get().type()).isEqualTo(PiiType.NIGERIAN_BVN);
		assertThat(res22.get().confidence()).isEqualTo(0.99);

		// 18-prefixed timestamp suppression with various timestamp keywords
		assertThat(PiiDisambiguationEngine.disambiguate("18123456789", "event time: 18123456789", 12)).isEmpty();
		assertThat(PiiDisambiguationEngine.disambiguate("18123456789", "record updated at 18123456789", 18)).isEmpty();
		assertThat(PiiDisambiguationEngine.disambiguate("18123456789", "record epoch at 18123456789", 16)).isEmpty();
		assertThat(PiiDisambiguationEngine.disambiguate("18123456789", "record created at 18123456789", 18)).isEmpty();
		assertThat(PiiDisambiguationEngine.disambiguate("18123456789", "record date at 18123456789", 15)).isEmpty();
		assertThat(PiiDisambiguationEngine.disambiguate("18123456789", "event ts 18123456789", 9)).isEmpty();
	}
}
