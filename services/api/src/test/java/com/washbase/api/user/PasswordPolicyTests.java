package com.washbase.api.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTests {

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "Short-1", "1234567" })
	void refusesFewerThanEightCharacters(String password) {
		assertThat(PasswordPolicy.violation(password)).contains("Use at least 8 characters.");
	}

	@Test
	void acceptsEightCharacters() {
		assertThat(PasswordPolicy.violation("12345678")).isEmpty();
	}

}
