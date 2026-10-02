package com.washbase.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link SignInAttempts#identifierKey} and {@link SignInAttempts#identifierHash} (CAR-19). */
class SignInAttemptsTests {

	@ParameterizedTest(name = "\"{0}\" is counted as {1}")
	@CsvSource(delimiter = '|', value = { "admin@example.com | email:admin@example.com",
			"'  Admin@Example.COM ' | email:admin@example.com", "09171234567 | mobile:+639171234567",
			"+639171234567 | mobile:+639171234567", "' 09171234567 ' | mobile:+639171234567",
			"'0917 123 4567' | other:0917 123 4567",
			"' SomeName ' | other:somename", "12345 | other:12345" })
	void identifierKey(String typed, String key) {
		assertThat(SignInAttempts.identifierKey(typed)).contains(key);
	}

	@ParameterizedTest(name = "Nothing is counted for a blank identifier ({0})")
	@NullAndEmptySource
	@ValueSource(strings = { "  ", "\t" })
	void blankIdentifierHasNoKey(String typed) {
		assertThat(SignInAttempts.identifierKey(typed)).isEmpty();
	}

	@Test
	@DisplayName("Every form of one identifier gets the same key")
	void formsShareKey() {
		assertThat(SignInAttempts.identifierKey("09171234567"))
			.isEqualTo(SignInAttempts.identifierKey("+639171234567"));
		assertThat(SignInAttempts.identifierKey("Admin@Example.com"))
			.isEqualTo(SignInAttempts.identifierKey("admin@example.com"));
	}

	@Test
	@DisplayName("The stored hash is the lowercase hex SHA-256 of the key")
	void identifierHash() {
		assertThat(SignInAttempts.identifierHash("abc"))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
		assertThat(SignInAttempts.identifierHash("email:admin@example.com")).matches("[0-9a-f]{64}")
			.doesNotContain("admin");
	}

}
