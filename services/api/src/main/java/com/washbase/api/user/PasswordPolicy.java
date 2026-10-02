package com.washbase.api.user;

import java.util.Optional;

/**
 * The rules every new password must meet (CAR-21): at least {@value #MIN_LENGTH} characters, nothing more.
 * Shared by every Feature that sets a password.
 */
public final class PasswordPolicy {

	public static final int MIN_LENGTH = 8;

	public static final String TOO_SHORT = "Use at least " + MIN_LENGTH + " characters.";

	private PasswordPolicy() {
	}

	/** @return the message to show when {@code password} is unacceptable, or empty when it meets the policy */
	public static Optional<String> violation(String password) {
		if (password == null || password.length() < MIN_LENGTH) {
			return Optional.of(TOO_SHORT);
		}
		return Optional.empty();
	}

}
