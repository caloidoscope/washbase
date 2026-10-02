package com.washbase.api.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PhoneNumbersTests {

	@ParameterizedTest(name = "Rule: \"{0}\" is the Philippine mobile number {1}")
	@CsvSource({ "09171234567, +639171234567", "+639171234567, +639171234567", "' 09171234567 ', +639171234567",
			"09991234567, +639991234567" })
	void normalizesBothFormats(String input, String expected) {
		assertThat(PhoneNumbers.normalize(input)).contains(expected);
	}

	@ParameterizedTest(name = "Rule: \"{0}\" is not a Philippine mobile number")
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "9171234567", "0917123456", "091712345678", "+63917123456", "+6391712345678",
			"639171234567", "08171234567", "+638171234567", "0917-123-4567", "0917 123 4567", "0917123456a",
			"+1 9171234567", "٠٩١٧١٢٣٤٥٦٧" })
	void rejectsAnythingElse(String input) {
		assertThat(PhoneNumbers.normalize(input)).isEmpty();
	}

}
