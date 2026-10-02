package com.washbase.api.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EmailAddressesTests {

	@ParameterizedTest(name = "Rule: emails are matched case-insensitively (\"{0}\" = \"{1}\")")
	@CsvSource({ "admin@example.com, admin@example.com", "Admin@Example.com, admin@example.com",
			"' ADMIN@EXAMPLE.COM ', admin@example.com" })
	void trimsAndLowerCases(String input, String expected) {
		assertThat(EmailAddresses.normalize(input)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "Blank email \"{0}\" normalizes to null")
	@NullAndEmptySource
	@ValueSource(strings = { "   " })
	void blankIsNull(String input) {
		assertThat(EmailAddresses.normalize(input)).isNull();
	}

}
